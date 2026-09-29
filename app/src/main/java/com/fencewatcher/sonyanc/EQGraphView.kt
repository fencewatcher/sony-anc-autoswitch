package com.fencewatcher.sonyanc

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * EQ frequency response graph.
 *
 * Redrawn to actually read as an equaliser: it sits transparent on the card
 * instead of painting its own opaque panel, uses the app's green accent rather
 * than a stray blue, shows a labelled -6..+6 dB axis, and fills under the curve.
 *
 * 10 bands (31Hz-16kHz), -6..+6 dB.
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

    // Everything below is sized in dp. The previous version used raw pixels,
    // which is why the axis labels and handles drifted out of proportion to the
    // rest of the UI on higher-density screens.
    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    // Palette: the app accent, not an unrelated blue.
    private val accent = Color.rgb(127, 212, 168)      // #7FD4A8
    private val gridMinor = Color.argb(38, 255, 255, 255)
    private val gridZero = Color.argb(96, 255, 255, 255)
    private val labelColor = Color.argb(150, 224, 224, 224)
    private val valueColor = Color.rgb(232, 240, 236)
    private val dimmed = Color.rgb(120, 120, 120)

    // Layout (dp)
    private val leftMargin = dp(34f)
    private val rightMargin = dp(10f)
    private val topMargin = dp(22f)
    private val bottomMargin = dp(26f)
    private val dotRadius = dp(5.5f)
    private val touchSlop = dp(28f)

    private val axisText = dp(10f)
    private val freqText = dp(9.5f)
    private val valueText = dp(10f)

    // Paint
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridMinor; strokeWidth = dp(1f)
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridZero; strokeWidth = dp(1.2f)
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.6f); color = Color.WHITE
    }
    private val axisLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = axisText; color = labelColor; textAlign = Paint.Align.RIGHT
    }
    private val freqLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = freqText; color = labelColor; textAlign = Paint.Align.CENTER
    }
    private val valueLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = valueText; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    // Touch state
    private var draggingBand = -1

    // ---- Coordinate mapping ----

    private fun plotWidth() = (width - leftMargin - rightMargin).coerceAtLeast(1f)
    private fun plotHeight() = (height - topMargin - bottomMargin).coerceAtLeast(1f)

    /** X position for band index (logarithmic scale). */
    private fun bandX(index: Int): Float {
        val minFreq = bandFreqs.first().toDouble()
        val maxFreq = bandFreqs.last().toDouble()
        val freq = bandFreqs[index].toDouble()
        val t = (Math.log(freq / minFreq) / Math.log(maxFreq / minFreq)).toFloat()
        return leftMargin + t * plotWidth()
    }

    /** Y position for dB value. */
    private fun bandY(value: Int): Float {
        val ratio = (value + 6) / 12f
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
        val zeroY = bandY(0)

        // Deliberately transparent: the surrounding card provides the surface, so
        // the graph no longer reads as a black box pasted onto it.
        val points = bandFreqs.indices.map { i -> PointF(bandX(i), bandY(bandValues[i])) }

        // Grid: minor lines every 3 dB, with the 0 dB baseline called out.
        for (db in -6..6 step 3) {
            val y = bandY(db)
            canvas.drawLine(leftMargin, y, leftMargin + pw, y, if (db == 0) zeroPaint else gridPaint)
            val txt = if (db > 0) "+$db" else "$db"
            canvas.drawText(txt, leftMargin - dp(6f), y + axisText / 3f, axisLabel)
        }

        // Vertical guides at each band, fading toward the top/bottom.
        for (i in bandFreqs.indices) {
            val x = bandX(i)
            canvas.drawLine(x, topMargin, x, topMargin + ph, gridPaint)
            val f = bandFreqs[i]
            val txt = if (f >= 1000) "${f / 1000}k" else "$f"
            canvas.drawText(txt, x, topMargin + ph + freqText + dp(6f), freqLabel)
        }

        if (points.size >= 2) {
            // Fill between the curve and the 0 dB baseline, faded out downward.
            val area = Path().apply {
                moveTo(points[0].x, zeroY)
                for (p in points) lineTo(p.x, p.y)
                lineTo(points.last().x, zeroY)
                close()
            }
            fillPaint.shader = LinearGradient(
                0f, topMargin, 0f, topMargin + ph,
                Color.argb(if (interactive) 66 else 26, 127, 212, 168),
                Color.argb(0, 127, 212, 168),
                Shader.TileMode.CLAMP,
            )
            canvas.drawPath(area, fillPaint)

            // Response curve, smooth through the band points.
            curvePaint.color = if (interactive) accent else dimmed
            curvePaint.alpha = if (interactive) 255 else 120
            val line = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) {
                    val prev = points[i - 1]
                    val cur = points[i]
                    val mid = (prev.x + cur.x) / 2f
                    cubicTo(mid, prev.y, mid, cur.y, cur.x, cur.y)
                }
            }
            canvas.drawPath(line, curvePaint)

            // Band handles.
            dotFill.color = if (interactive) accent else dimmed
            dotRing.color = if (interactive) Color.WHITE else dimmed
            valueLabel.color = if (interactive) valueColor else dimmed
            for ((i, p) in points.withIndex()) {
                val dragging = interactive && draggingBand == i
                val r = if (dragging) dotRadius * 1.5f else dotRadius
                if (dragging) {
                    canvas.drawCircle(
                        p.x, p.y, r + dp(5f),
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.argb(60, 127, 212, 168); style = Paint.Style.FILL
                        },
                    )
                }
                canvas.drawCircle(p.x, p.y, r, dotFill)
                canvas.drawCircle(p.x, p.y, r, dotRing)

                // Signed value, placed above a raised band and below a lowered
                // one so it never runs off the top or collides with the handle.
                val v = bandValues[i]
                val signed = if (v > 0) "+$v" else "$v"
                val above = v >= 0
                val ty = if (above) p.y - r - dp(4f) else p.y + r + valueText
                canvas.drawText(signed, p.x, ty, valueLabel)
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
                    val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    if (dist < bestDist && dist < touchSlop) {
                        bestDist = dist
                        best = i
                    }
                }
                if (best >= 0) {
                    draggingBand = best
                    // This view lives inside a ScrollView. Once a drag actually
                    // starts, the parent must stop intercepting — otherwise a
                    // vertical finger movement is stolen by the page scroll and
                    // the handle can never be set precisely.
                    parent?.requestDisallowInterceptTouchEvent(true)
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
                // Hand the gesture back so the page can scroll again.
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w * 0.52f).toInt().coerceAtLeast((180 * d).toInt())
        setMeasuredDimension(w, h)
    }
}
