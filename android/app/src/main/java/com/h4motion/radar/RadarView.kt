package com.h4motion.radar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 120° plan-position display. The RD-03D looks forward about ±60°.
 * The sensor is the bottom apex, +Y (ahead) is up, and +X is right.
 * A return outside that wedge is drawn on the nearest edge.
 * Range rings are meters. Closing targets are amber. Inside 2.5 m they go red.
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var backdrop = Color.parseColor("#04140C")
    private var ringColor = Color.parseColor("#1F6B3A")
    private var nearRing = Color.parseColor("#5A2020")
    private var blipNear = Color.parseColor("#FF4D4D")
    private var blipClose = Color.parseColor("#FFCC33")
    private var blipOpen = Color.parseColor("#7DFFB3")
    private var blipRadius = 10f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0B2816")
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = ringColor
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = ringColor
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
    private val sensorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#7DFFB3")
    }

    /** Black field, white marks. Near stays red and a closing return stays yellow. */
    fun setHighContrast(on: Boolean) {
        if (on) {
            backdrop = Color.BLACK
            fillPaint.color = Color.BLACK
            ringColor = Color.WHITE
            nearRing = Color.parseColor("#FF2A2A")
            ringPaint.strokeWidth = 4f
            edgePaint.strokeWidth = 5f
            crossPaint.color = Color.WHITE
            crossPaint.strokeWidth = 3f
            textPaint.color = Color.WHITE
            sensorPaint.color = Color.WHITE
            blipNear = Color.parseColor("#FF2A2A")
            blipClose = Color.parseColor("#FFE14A")
            blipOpen = Color.WHITE
            blipRadius = 14f
        } else {
            backdrop = Color.parseColor("#04140C")
            fillPaint.color = Color.parseColor("#0B2816")
            ringColor = Color.parseColor("#1F6B3A")
            nearRing = Color.parseColor("#5A2020")
            ringPaint.strokeWidth = 2f
            edgePaint.strokeWidth = 3f
            crossPaint.color = Color.parseColor("#144D28")
            crossPaint.strokeWidth = 2f
            textPaint.color = Color.parseColor("#8FBF9A")
            sensorPaint.color = Color.parseColor("#7DFFB3")
            blipNear = Color.parseColor("#FF4D4D")
            blipClose = Color.parseColor("#FFCC33")
            blipOpen = Color.parseColor("#7DFFB3")
            blipRadius = 10f
        }
        ringPaint.color = ringColor
        edgePaint.color = ringColor
        invalidate()
    }

    var rangeM: Int = 8
        set(value) {
            field = value
            invalidate()
        }

    var blips: List<RadarHub.Blip> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(suggestedMinimumWidth)
        val padX = dp(4f)
        val padTop = dp(10f)
        val padBottom = dp(14f)
        val usable = (width - 2f * padX).coerceAtLeast(1f)
        val rangePx = usable / (2f * sin(WEDGE_HALF_RAD).toFloat())
        val want = (padTop + rangePx + padBottom).toInt().coerceAtLeast(1)
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> want.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
            else -> want
        }
        setMeasuredDimension(width, height.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(backdrop)
        val padX = dp(4f)
        val padTop = dp(10f)
        val padBottom = dp(14f)
        val cx = width / 2f
        val cy = height - padBottom
        val byWidth = (width - 2f * padX) / (2f * sin(WEDGE_HALF_RAD).toFloat())
        val byHeight = (cy - padTop).coerceAtLeast(1f)
        val rangePx = min(byWidth, byHeight)
        val rangeMm = (rangeM * 1000).coerceAtLeast(1000).toDouble()
        val arc = RectF(cx - rangePx, cy - rangePx, cx + rangePx, cy + rangePx)

        canvas.drawArc(arc, WEDGE_START, WEDGE_SWEEP, true, fillPaint)
        canvas.drawLine(cx, cy, cx, cy - rangePx, crossPaint)

        val rings = if (rangeM <= 4) listOf(1, 2, 3, 4) else listOf(2, 4, 6, 8).filter { it <= rangeM }
        for (meters in rings) {
            if (meters >= rangeM) continue
            val r = rangePx * meters / rangeM
            val ring = RectF(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(ring, WEDGE_START, WEDGE_SWEEP, false, ringPaint)
            canvas.drawText("${meters}m", cx + 8f, cy - r + textPaint.textSize, textPaint)
        }
        canvas.drawText("${rangeM}m", cx + 8f, cy - rangePx + textPaint.textSize, textPaint)

        val nearPx = rangePx * 2500f / rangeMm.toFloat()
        if (nearPx < rangePx) {
            val near = RectF(cx - nearPx, cy - nearPx, cx + nearPx, cy + nearPx)
            ringPaint.color = nearRing
            canvas.drawArc(near, WEDGE_START, WEDGE_SWEEP, false, ringPaint)
            ringPaint.color = ringColor
        }

        canvas.drawArc(arc, WEDGE_START, WEDGE_SWEEP, true, edgePaint)

        for (blip in blips) {
            val plot = clampToForwardWedge(blip.xMm.toDouble(), blip.yMm.toDouble(), rangeMm)
            val sx = cx + (plot.xMm / rangeMm * rangePx).toFloat()
            val sy = cy - (plot.yMm / rangeMm * rangePx).toFloat()
            val dist = hypot(blip.xMm.toDouble(), blip.yMm.toDouble())
            blipPaint.color = when {
                dist <= 2500.0 -> blipNear
                blip.spdCms < 0 -> blipClose
                else -> blipOpen
            }
            canvas.drawCircle(sx, sy, blipRadius, blipPaint)
        }

        canvas.drawCircle(cx, cy, 5f, sensorPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

private const val WEDGE_HALF_RAD = Math.PI / 3.0

/** Android arc angles: 0° is 3 o'clock, sweep is clockwise. -150°..-30° is ±60° around up. */
private const val WEDGE_START = -150f
private const val WEDGE_SWEEP = 120f

/** A point kept inside the forward sector, in the same millimeter frame as the radar. */
internal data class WedgePoint(val xMm: Double, val yMm: Double)

internal fun clampToForwardWedge(xMm: Double, yMm: Double, rangeMm: Double): WedgePoint {
    if (rangeMm <= 0.0) return WedgePoint(0.0, 0.0)
    val r = hypot(xMm, yMm)
    if (r == 0.0) return WedgePoint(0.0, 0.0)
    val az = atan2(xMm, yMm)
    if (abs(az) <= WEDGE_HALF_RAD) {
        val along = min(r, rangeMm)
        return WedgePoint(along * sin(az), along * cos(az))
    }
    val ray = if (az > 0.0) WEDGE_HALF_RAD else -WEDGE_HALF_RAD
    val along = (r * cos(az - ray)).coerceIn(0.0, rangeMm)
    return WedgePoint(along * sin(ray), along * cos(ray))
}
