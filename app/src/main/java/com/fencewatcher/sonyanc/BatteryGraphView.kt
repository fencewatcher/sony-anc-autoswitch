package com.fencewatcher.sonyanc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Battery level over time, drawn from the persisted samples.
 *
 * Deliberately not a chart library: the app already ships one hand-drawn view
 * (the EQ curve), and a single line with gridlines does not justify a
 * dependency. Gaps longer than the estimator's own gap limit are drawn as
 * breaks rather than joined, because a line across an absence is a claim the
 * data does not support.
 */
class BatteryGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 170, 255)
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    // A separate Paint: `line.apply { color = … }` would return the same object
    // and recolour the discharge line too.
    private val chargeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(127, 212, 168)
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val grid = Paint().apply { color = Color.argb(28, 255, 255, 255); strokeWidth = 1f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(136, 136, 136)
        textSize = 10f * resources.displayMetrics.scaledDensity
    }

    private var samples: List<BatteryHistory.Sample> = emptyList()
    private var startMs = 0L
    private var endMs = 0L

    private val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())

    fun show(items: List<BatteryHistory.Sample>, windowStart: Long, windowEnd: Long) {
        samples = items.filter { it.t in windowStart..windowEnd }.sortedBy { it.t }
        startMs = windowStart
        endMs = windowEnd
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padLeft = 26f * resources.displayMetrics.density
        val padBottom = 18f * resources.displayMetrics.density
        val padTop = 8f * resources.displayMetrics.density
        val left = padLeft
        val right = width - 4f
        val top = padTop
        val bottom = height - padBottom
        if (right <= left || bottom <= top) return

        // Grid at every quarter, with the two labels that matter.
        for (i in 0..4) {
            val y = bottom - (bottom - top) * i / 4f
            canvas.drawLine(left, y, right, y, grid)
            if (i == 0 || i == 2 || i == 4) {
                canvas.drawText("${i * 25}", 0f, y + label.textSize / 3f, label)
            }
        }

        // Day boundaries, labelled.
        val cal = Calendar.getInstance().apply {
            timeInMillis = startMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }
        while (cal.timeInMillis < endMs) {
            val t = cal.timeInMillis
            val x = left + (right - left) * (t - startMs).toFloat() / (endMs - startMs)
            canvas.drawLine(x, top, x, bottom, grid)
            canvas.drawText(dayFormat.format(Date(t)), x + 2f, height - 4f, label)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        if (samples.isEmpty()) {
            label.textSize = 12f * resources.displayMetrics.scaledDensity
            canvas.drawText("No samples in this window yet", left + 8f, (top + bottom) / 2f, label)
            return
        }

        fun xAt(t: Long) = left + (right - left) * (t - startMs).toFloat() / (endMs - startMs)
        fun yAt(p: Int) = bottom - (bottom - top) * p / 100f

        var path = Path()
        var lastRising = false
        for ((i, s) in samples.withIndex()) {
            val rising = i > 0 && s.p > samples[i - 1].p
            val gap = i > 0 && (s.t - samples[i - 1].t) > MAX_GAP_MS
            if (i == 0 || gap) {
                if (!path.isEmpty) canvas.drawPath(path, if (lastRising) chargeLine else line)
                path = Path()
                path.moveTo(xAt(s.t), yAt(s.p))
            } else {
                if (rising != lastRising && !path.isEmpty) {
                    // Split at the direction change so charging reads as its own
                    // stretch instead of a colour that flips mid-line.
                    canvas.drawPath(path, if (lastRising) chargeLine else line)
                    path = Path()
                    path.moveTo(xAt(samples[i - 1].t), yAt(samples[i - 1].p))
                }
                path.lineTo(xAt(s.t), yAt(s.p))
            }
            lastRising = rising
        }
        if (!path.isEmpty) canvas.drawPath(path, if (lastRising) chargeLine else line)
    }

    private companion object {
        /** Matches the estimator: a longer silence is an absence, not a slope. */
        const val MAX_GAP_MS = 2L * 60 * 60 * 1000
    }
}
