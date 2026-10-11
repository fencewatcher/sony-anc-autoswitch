package com.fencewatcher.sonyanc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent battery samples per device, and the time-to-empty estimator
 * built on top of them.
 *
 * Samples come from two sources: the MDR poll (precise, but only while the
 * RFCOMM link is up) and the Android Bluetooth stack, which receives battery
 * over the standard profiles whenever the headphones are connected at all —
 * including when the MDR link is down or the service is stopped.
 *
 * Estimation is deliberately conservative. A charge event (a level that
 * rises) ends the current discharge segment; only the segment since the last
 * charge supports a slope. The result is tiered:
 *   - null            → not enough data / charging / stale   → shown as "--"
 *   - reliable=false  → computable but thin                  → "≈ 5h"
 *   - reliable=true   → sustained, recent, gap-free          → "≈ 4h 12m"
 */
object BatteryHistory {

    const val SOURCE_MDR = 0
    const val SOURCE_SYSTEM = 1

    private const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000
    private const val MAX_SAMPLES_PER_DEVICE = 3000

    /** Identical level inside this window adds no information. */
    private const val DEDUPE_WINDOW_MS = 30_000L

    /** Minimum span of a discharge segment before a slope is believable. */
    private const val MIN_SEGMENT_SPAN_MS = 10L * 60 * 1000

    /** Span/drop thresholds for the "reliable" tier. */
    private const val RELIABLE_SPAN_MS = 45L * 60 * 1000
    private const val RELIABLE_MIN_DROP = 3

    /** Newest sample older than this makes any estimate "less accurate". */
    private const val FRESH_WINDOW_MS = 30L * 60 * 1000

    /** Newest sample older than this: too stale to extrapolate at all. */
    private const val STALE_WINDOW_MS = 6L * 60 * 60 * 1000

    /** A silent stretch this long inside a segment means the headphones were
     *  away; a slope across it underestimates the drain. */
    private const val MAX_GAP_MS = 2L * 60 * 60 * 1000

    data class Sample(val t: Long, val p: Int, val source: Int)

    data class Estimate(val minutes: Int, val reliable: Boolean)

    private val lock = Any()
    private val byDevice = LinkedHashMap<String, MutableList<Sample>>()
    private var loaded = false

    fun record(context: Context, address: String, percent: Int, source: Int) {
        if (percent !in 1..100 || address.isBlank()) return
        synchronized(lock) {
            loadLocked(context)
            val list = byDevice.getOrPut(address) { mutableListOf() }
            val now = System.currentTimeMillis()
            if (list.isNotEmpty()) {
                val last = list.last()
                if (now - last.t < DEDUPE_WINDOW_MS && last.p == percent) return
            }
            list.add(Sample(now, percent, source))
            while (list.isNotEmpty() && now - list.first().t > MAX_AGE_MS) list.removeAt(0)
            while (list.size > MAX_SAMPLES_PER_DEVICE) list.removeAt(0)
            saveLocked(context)
        }
    }

    /** Time-to-empty in minutes for [address], or null when it cannot be provided. */
    fun estimate(address: String): Estimate? {
        synchronized(lock) {
            val list = byDevice[address] ?: return null
            if (list.size < 3) return null

            // The current discharge segment: everything since the last rise —
            // a rise means charging started, which resets the estimate.
            var start = 0
            for (i in 1 until list.size) if (list[i].p > list[i - 1].p) start = i
            // Trim a leading plateau: equal consecutive levels carry no slope,
            // and after a charge the level can sit pinned at 100 for a while —
            // counting that time would dilute the drain rate.
            while (start < list.size - 1 && list[start].p == list[start + 1].p) start++
            if (list.size - start < 3) return null
            val segment = list.subList(start, list.size)

            val spanMs = segment.last().t - segment.first().t
            if (spanMs < MIN_SEGMENT_SPAN_MS) return null
            val drop = segment.first().p - segment.last().p
            if (drop < 1) return null   // flat (charging, or idle at a wall)
            val ratePerHour = drop.toDouble() * 3_600_000.0 / spanMs
            if (ratePerHour <= 0.0) return null

            val minutes = (segment.last().p / ratePerHour * 60.0).toInt().coerceIn(0, 24 * 60)
            val ageMs = System.currentTimeMillis() - segment.last().t
            if (ageMs > STALE_WINDOW_MS) return null

            var maxGapMs = 0L
            for (i in 1 until segment.size) {
                maxGapMs = maxOf(maxGapMs, segment[i].t - segment[i - 1].t)
            }
            val reliable = spanMs >= RELIABLE_SPAN_MS &&
                drop >= RELIABLE_MIN_DROP &&
                ageMs <= FRESH_WINDOW_MS &&
                maxGapMs <= MAX_GAP_MS

            return Estimate(minutes, reliable)
        }
    }

    /** "≈ 4h 12m" when reliable; "≈ 5h" (no minutes) when the data is thin. */
    fun format(e: Estimate): String {
        val h = e.minutes / 60
        val m = e.minutes % 60
        return if (e.reliable) {
            if (h > 0) "≈ ${h}h ${m}m" else "≈ ${m}m"
        } else {
            "≈ ${kotlin.math.ceil(e.minutes / 60.0).toInt().coerceAtLeast(1)}h"
        }
    }

    private fun storeFile(context: Context) = File(context.filesDir, "battery_history.json")

    private fun loadLocked(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = storeFile(context)
            if (!f.exists()) return
            val obj = JSONObject(f.readText())
            for (key in obj.keys()) {
                val arr = obj.optJSONArray(key) ?: continue
                val list = mutableListOf<Sample>()
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    list.add(Sample(s.optLong("t"), s.optInt("p"), s.optInt("s", SOURCE_MDR)))
                }
                byDevice[key] = list
            }
        } catch (_: Exception) {
            // A broken history file costs the estimate, not the app.
        }
    }

    private fun saveLocked(context: Context) {
        try {
            val obj = JSONObject()
            for ((key, list) in byDevice) {
                val arr = JSONArray()
                for (s in list) {
                    arr.put(JSONObject().put("t", s.t).put("p", s.p).put("s", s.source))
                }
                obj.put(key, arr)
            }
            val f = storeFile(context)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(obj.toString())
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }
}
