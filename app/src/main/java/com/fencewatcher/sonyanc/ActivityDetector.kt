package com.fencewatcher.sonyanc

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlin.math.sqrt

/**
 * On-device activity classification, so routines can react to what the wearer is
 * doing without sending anything anywhere.
 *
 * Sony's Adaptive Sound Control uses its own `ishinlib` classifier
 * (`STAY / LONG_STAY / WALK / RUN / VEHICLE / BICYCLE`). That library is
 * proprietary and partly native, so this is an independent implementation from
 * the raw accelerometer and step counter. It deliberately uses no Play Services
 * and no location permission: the point of this app is that it works offline.
 *
 * Classification is heuristic, not a model:
 *
 *  - step cadence separates walking from running;
 *  - the standard deviation of the acceleration magnitude separates motion from
 *    stillness (gravity is constant, so it drops out of the variance);
 *  - sustained motion without steps is treated as a vehicle.
 *
 * The vehicle case is the weakest of the four. A bus and a bumpy walk can look
 * alike to one accelerometer, so a class change is only emitted after it wins
 * two consecutive windows, and callers should treat VEHICLE as best-effort.
 */
class ActivityDetector(
    context: Context,
    private val onActivity: (Activity) -> Unit,
) {

    enum class Activity(val id: String) {
        STILL("still"),
        WALK("walk"),
        RUN("run"),
        VEHICLE("vehicle"),
    }

    companion object {
        private const val TAG = "ActivityDetector"

        /** Classification window. Long enough to contain a few strides. */
        private const val WINDOW_MS = 10_000L

        /** How often the window is classified. */
        private const val STEP_MS = 5_000L

        /** A class must win this many consecutive windows before it is emitted. */
        private const val CONFIRMATIONS = 2

        /** Steps per minute at or above this is a run. */
        private const val RUN_CADENCE = 110.0

        /** Steps per minute at or above this is a walk. */
        private const val WALK_CADENCE = 30.0

        /** Magnitude std-dev below this is stillness. */
        private const val STILL_DEVIATION = 0.25

        /** Magnitude std-dev above this, with no steps, reads as a vehicle. */
        private const val MOTION_DEVIATION = 0.60
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val stepCounter: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private val thread = HandlerThread("activity-detector").apply { start() }
    private val handler = Handler(thread.looper)

    /** (timestamp ms, acceleration magnitude) samples inside the window. */
    private val samples = ArrayDeque<Pair<Long, Float>>()

    /** Step timestamps inside the window. */
    private val steps = ArrayDeque<Long>()

    private var running = false
    private var lastEmitted: Activity? = null
    private var candidate: Activity? = null
    private var candidateCount = 0

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    val magnitude = sqrt(x * x + y * y + z * z)
                    synchronized(samples) {
                        samples.addLast(event.timestamp / 1_000_000L to magnitude)
                        trim(event.timestamp / 1_000_000L)
                    }
                }

                Sensor.TYPE_STEP_DETECTOR -> {
                    val now = System.currentTimeMillis()
                    synchronized(steps) {
                        steps.addLast(now)
                        while (steps.isNotEmpty() && steps.first() < now - WINDOW_MS) {
                            steps.removeFirst()
                        }
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val classify = object : Runnable {
        override fun run() {
            if (!running) return
            evaluate()
            handler.postDelayed(this, STEP_MS)
        }
    }

    fun start() {
        if (running) return
        if (accelerometer == null) {
            Log.w(TAG, "No accelerometer — activity automation unavailable")
            return
        }
        running = true
        sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME, handler)
        // The step detector is a bonus: without it walking still shows up as
        // motion, but the walk/run split is lost.
        stepCounter?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
        }
        handler.postDelayed(classify, STEP_MS)
        Log.d(TAG, "Started (step detector=${stepCounter != null})")
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(classify)
        sensorManager.unregisterListener(listener)
        synchronized(samples) { samples.clear() }
        synchronized(steps) { steps.clear() }
        candidate = null
        candidateCount = 0
        Log.d(TAG, "Stopped")
    }

    private fun trim(now: Long) {
        while (samples.isNotEmpty() && samples.first().first < now - WINDOW_MS) {
            samples.removeFirst()
        }
    }

    private fun evaluate() {
        val now = System.currentTimeMillis()
        val snapshot: List<Float>
        val stepCount: Int
        synchronized(samples) {
            trim(now)
            snapshot = samples.map { it.second }
        }
        synchronized(steps) {
            while (steps.isNotEmpty() && steps.first() < now - WINDOW_MS) {
                steps.removeFirst()
            }
            stepCount = steps.size
        }
        if (snapshot.size < 10) return

        val mean = snapshot.average().toFloat()
        var variance = 0.0
        for (v in snapshot) {
            val d = v - mean
            variance += d * d
        }
        val deviation = sqrt(variance / snapshot.size)

        val cadence = stepCount * 60_000.0 / WINDOW_MS

        val observed = when {
            cadence >= RUN_CADENCE -> Activity.RUN
            cadence >= WALK_CADENCE -> Activity.WALK
            deviation < STILL_DEVIATION -> Activity.STILL
            deviation > MOTION_DEVIATION -> Activity.VEHICLE
            // Between the two thresholds: keep the previous class rather than
            // flapping on a single noisy window.
            else -> lastEmitted ?: Activity.STILL
        }

        if (observed == candidate) {
            candidateCount++
        } else {
            candidate = observed
            candidateCount = 1
        }

        if (candidateCount >= CONFIRMATIONS && observed != lastEmitted) {
            lastEmitted = observed
            Log.d(
                TAG,
                "Activity → $observed (cadence=${"%.0f".format(cadence)}/min, σ=${"%.2f".format(deviation)})",
            )
            onActivity(observed)
        }
    }
}
