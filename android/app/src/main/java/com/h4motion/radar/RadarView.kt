package com.h4motion.radar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Plan-position display. Sensor at the center, +Y (ahead) is up, +X is right.
 * Range rings are meters. Closing targets are amber. Inside 2.5 m they go red.
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#1F6B3A")
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#6639FF7A")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8FBF9A")
        textSize = 28f
    }
    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#144D28")
        strokeWidth = 2f
    }

    var rangeM: Int = 8
    var blips: List<RadarHub.Blip> = emptyList()

    private var sweepDeg = -90f
    private val tick = object : Runnable {
        override fun run() {
            sweepDeg = (sweepDeg + 1.8f) % 360f
            invalidate()
            postDelayed(this, 33L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(tick)
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#04140C"))
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.42f
        val rangeMm = (rangeM * 1000).coerceAtLeast(1000)

        canvas.drawLine(cx - radius, cy, cx + radius, cy, crossPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, crossPaint)

        val rings = if (rangeM <= 4) listOf(1, 2, 3, 4) else listOf(2, 4, 6, 8).filter { it <= rangeM }
        for (meters in rings) {
            val r = radius * meters / rangeM
            canvas.drawCircle(cx, cy, r, ringPaint)
            canvas.drawText("${meters}m", cx + 6f, cy - r + 24f, textPaint)
        }

        val nearR = radius * 2500f / rangeMm
        if (nearR < radius) {
            ringPaint.color = Color.parseColor("#5A2020")
            canvas.drawCircle(cx, cy, nearR, ringPaint)
            ringPaint.color = Color.parseColor("#1F6B3A")
        }

        val sweep = Math.toRadians(sweepDeg.toDouble())
        canvas.drawLine(
            cx, cy,
            cx + (sin(sweep) * radius).toFloat(),
            cy - (cos(sweep) * radius).toFloat(),
            sweepPaint
        )

        for (blip in blips) {
            val sx = cx + blip.xMm * radius / rangeMm
            val sy = cy - blip.yMm * radius / rangeMm
            val dist = hypot(blip.xMm.toDouble(), blip.yMm.toDouble())
            blipPaint.color = when {
                dist <= 2500.0 -> Color.parseColor("#FF4D4D")
                blip.spdCms < 0 -> Color.parseColor("#FFCC33")
                else -> Color.parseColor("#7DFFB3")
            }
            canvas.drawCircle(sx, sy, 10f, blipPaint)
            val label = "T${blip.slot + 1}"
            canvas.drawText(label, sx + 12f, sy - 8f, textPaint)
        }

        val arc = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(arc, 0f, 360f, false, ringPaint)
    }
}
