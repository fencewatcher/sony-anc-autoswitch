package com.fencewatcher.sonyanc

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Custom EQ frequency response graph.
 * Draws a grid, frequency response curve, and draggable band dots.
 * 10 bands (31Hz–16kHz), -6..+6 dB range.
 */
class EQGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    // 10 band frequencies in Hz
    val bandFreqs = intArrayOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    /** Current band values (-6..+6). Must be 10 elements. */
    var bandValues = IntArray(10) { 0 }
        set(v) {
            if (v.size == 10) { field = v; invalidate() }
        }

    /** Whether the user can drag the dots. */
    var interactive = true
        set(v) { field = v; invalidate() }

    /** Callback when a band value changes via drag: (bandIndex, newValue). */
    var onBandChanged: ((Int, Int) -> Unit)? = null

    // Colors
    private val bgColor = Color.rgb(30, 30, 30)
    private val gridColor = Color.rgb(60, 60, 60)
    private val lineColor = Color.rgb(80, 200, 255)
    private val lineAlpha = 160
    private val dotFillColor = Color.rgb(80, 200, 255)
    private val dotStrokeColor = Color.WHITE
    private val labelColor = Color.rgb(160, 160, 160)
    private val disabledColor = Color.rgb(100, 100, 100)
    private val centerLineColor = Color.rgb(80, 80, 80)

    // Layout
    private val leftMargin = 48f
    private val rightMargin = 16f
    private val topMargin = 24f
    private val bottomMargin = 40f
    private val dotRadius = 18f
    private val touchSlop = 36f

    // Paint objects
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gridColor; strokeWidth = 1f }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = centerLineColor; strokeWidth = 1f }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor; strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 80, 200, 255); style = Paint.Style.FILL
    }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dotFillColor; style = Paint.Style.FILL
    }
    private val dotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dotStrokeColor; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor; textSize = 28f; textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 24f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val valuePaintDisabled = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = disabledColor; textSize = 24f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    // Touch state
    private var draggingBand = -1
    private var dragStartY = 0f
    private var dragStartValue = 0

    // ---- Coordinate mapping ----

    private fun plotWidth() = width - leftMargin - rightMargin
    private fun plotHeight() = height - topMargin - bottomMargin
    private fun plotCenter() = topMargin + plotHeight() / 2f

    /** X position for band index (logarithmic scale). */
    private fun bandX(index: Int): Float {
        val minFreq = bandFreqs.first().toFloat()
        val maxFreq = bandFreqs.last().toFloat()
        val freq = bandFreqs[index].toFloat()
        return leftMargin + (Math.log((freq / minFreq).toDouble()) / Math.log((maxFreq / minFreq).toDouble())).toFloat() * plotWidth()
    }

    /** Y position for dB value. */
    private fun bandY(value: Int): Float {
        val range = 12f  // -6 to +6
        val ratio = (value + 6) / range
        return topMargin + plotHeight() * (1f - ratio)
    }

    /** Value from Y position. */
    private fun valueFromY(y: Float): Int {
        val ratio = 1f - (y - topMargin) / plotHeight()
        return (ratio * 12f - 6).toInt().coerceIn(-6, 6)
    }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        val pw = plotWidth()
        val ph = plotHeight()
        val cx = plotCenter()

        // Fill background
        canvas.drawColor(bgColor)

        // Horizontal grid lines (every 3dB)
        for (db in -6..6 step 3) {
            val y = bandY(db)
            canvas.drawLine(leftMargin, y, leftMargin + pw, y, gridPaint)
            // dB label
            if (db == 0) {
                labelPaint.color = centerLineColor
                canvas.drawText("0", leftMargin - 8f, y + 10f, labelPaint)
                labelPaint.color = labelColor
            } else if (db % 6 == 0) {
                canvas.drawText("$db", leftMargin - 8f, y + 10f, labelPaint)
            }
        }
        // Center line (0 dB) — slightly thicker
        val centerY = bandY(0)
        canvas.drawLine(leftMargin, centerY, leftMargin + pw, centerY, centerPaint)

        // Vertical lines at each band
        for (i in bandFreqs.indices) {
            val x = bandX(i)
            canvas.drawLine(x, topMargin, x, topMargin + ph, gridPaint)
            // Frequency label
            val freqLabel = when {
                bandFreqs[i] >= 1000 -> "${bandFreqs[i] / 1000}k"
                else -> "${bandFreqs[i]}"
            }
            canvas.drawText(freqLabel, x, topMargin + ph + 20f, labelPaint)
        }

        // Build response curve points
        val points = bandFreqs.indices.map { i ->
            PointF(bandX(i), bandY(bandValues[i]))
        }

        // Fill under curve
        if (points.size >= 2) {
            val path = Path()
            path.moveTo(points[0].x, centerY)
            for (p in points) path.lineTo(p.x, p.y)
            path.lineTo(points.last().x, centerY)
            path.close()
            canvas.drawPath(path, fillPaint)
        }

        // Draw curve line
        if (points.size >= 2) {
            curvePaint.alpha = if (interactive) lineAlpha else 80
            val path = Path()
            path.moveTo(points[0].x, points[0].y)
            // Smooth cubic bezier through points
            for (i in 1 until points.size) {
                val prev = points[i - 1]
                val cur = points[i]
                val ctrlX1 = (prev.x + cur.x) / 2
                val ctrlX2 = ctrlX1
                path.cubicTo(ctrlX1, prev.y, ctrlX2, cur.y, cur.x, cur.y)
            }
            canvas.drawPath(path, curvePaint)

            // Draw dots
            val dotFill = if (interactive) dotFill else Paint(dotFill).apply { color = disabledColor; style = Paint.Style.FILL }
            val dotStroke = if (interactive) dotStroke else Paint(dotStroke).apply { color = disabledColor; style = Paint.Style.STROKE; strokeWidth = 2f }
            for ((i, p) in points.withIndex()) {
                // Highlight the dragged dot
                if (interactive && draggingBand == i) {
                    val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb(60, 80, 200, 255)
                        style = Paint.Style.FILL
                    }
                    canvas.drawCircle(p.x, p.y, dotRadius + 8f, glow)
                }
                canvas.drawCircle(p.x, p.y, dotRadius, dotFill)
                canvas.drawCircle(p.x, p.y, dotRadius, dotStroke)
                // Value label above dot
                val vp = if (interactive) valuePaint else valuePaintDisabled
                canvas.drawText("${bandValues[i]}", p.x, p.y - dotRadius - 6f, vp)
            }
        }
    }

    // ---- Touch ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Find nearest band
                var best = -1
                var bestDist = Float.MAX_VALUE
                for (i in bandFreqs.indices) {
                    val dx = event.x - bandX(i)
                    val dy = event.y - bandY(bandValues[i])
                    val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    if (d < bestDist && d < touchSlop) {
                        bestDist = d
                        best = i
                    }
                }
                if (best >= 0) {
                    draggingBand = best
                    dragStartY = event.y
                    dragStartValue = bandValues[best]
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingBand >= 0) {
                    val newValue = valueFromY(event.y)
                    if (newValue != bandValues[draggingBand]) {
                        bandValues[draggingBand] = newValue
                        onBandChanged?.invoke(draggingBand, newValue)
                        invalidate()
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingBand = -1
                invalidate()
                return true
            }
        }
        return false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w * 0.45f).toInt().coerceAtLeast(200)
        setMeasuredDimension(w, h)
    }
}