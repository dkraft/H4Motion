package com.h4motion.radar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Amber instrument plates under the scope.
 * Link mode, the first ENTER, the latest ENTER, and the live T1 contact.
 */
class InstrumentDeck @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val amber = Color.parseColor("#FFC14D")
    private val amberSoft = Color.parseColor("#E7C48A")
    private val nearRed = Color.parseColor("#FF5A3C")
    private val labelAmber = Color.parseColor("#C4893A")

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#140F0A")
    }
    private val hotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2A1A0C")
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#A56B2A")
    }
    private val dimLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#5C3E18")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelAmber
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.16f
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = amber
        typeface = Typeface.MONOSPACE
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6E5430")
        typeface = Typeface.MONOSPACE
    }
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = amber
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.12f
    }
    private val lampPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    var linkWifi: Boolean = false
        private set
    private var firstEnter: RadarHub.EnterMark? = null
    private var lastEnter: RadarHub.EnterMark? = null
    private var blips: List<RadarHub.Blip> = emptyList()
    private var rangeM: Int = 8

    fun bind(
        linkWifi: Boolean,
        firstEnter: RadarHub.EnterMark?,
        lastEnter: RadarHub.EnterMark?,
        blips: List<RadarHub.Blip>,
        rangeM: Int
    ) {
        val relayout = extraCount() != extrasOf(blips)
        this.linkWifi = linkWifi
        this.firstEnter = firstEnter
        this.lastEnter = lastEnter
        this.blips = blips
        this.rangeM = rangeM.coerceAtLeast(1)
        if (relayout) requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(suggestedMinimumWidth)
        val height = (modeH() + gap() + bodyH()).toInt().coerceAtLeast(1)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        valuePaint.color = amber
        valuePaint.letterSpacing = 0f
        val mode = RectF(0f, 0f, width.toFloat(), modeH())
        drawMode(canvas, mode)

        val top = mode.bottom + gap()
        val col = (width - gap()) / 2f
        val enter = enterH()
        drawEnter(canvas, RectF(0f, top, col, top + enter), "FIRST ENTER", firstEnter)
        drawEnter(
            canvas,
            RectF(0f, top + enter + gap(), col, top + enter * 2f + gap()),
            "LAST ENTER",
            lastEnter
        )
        drawTarget(canvas, RectF(col + gap(), top, width.toFloat(), top + bodyH()))
    }

    private fun drawMode(canvas: Canvas, rect: RectF) {
        canvas.drawRect(rect, fillPaint)
        val mid = rect.centerX()
        val active = if (linkWifi) {
            RectF(mid, rect.top, rect.right, rect.bottom)
        } else {
            RectF(rect.left, rect.top, mid, rect.bottom)
        }
        val inset = dp(2f)
        canvas.drawRect(
            active.left + inset,
            active.top + inset,
            active.right - inset,
            active.bottom - inset,
            hotFill
        )
        canvas.drawRect(rect, linePaint)
        frame(canvas, rect, linePaint)
        var tick = rect.left + dp(14f)
        val tickTop = rect.top + dp(4f)
        while (tick < rect.right - dp(12f)) {
            canvas.drawLine(tick, tickTop, tick, tickTop + dp(3f), linePaint)
            tick += dp(11f)
        }
        canvas.drawLine(mid, rect.top + dp(10f), mid, rect.bottom - dp(8f), dimLine)
        drawChoice(canvas, RectF(rect.left, rect.top, mid, rect.bottom), "LORA", !linkWifi)
        drawChoice(canvas, RectF(mid, rect.top, rect.right, rect.bottom), "WIFI", linkWifi)
    }

    private fun drawChoice(canvas: Canvas, rect: RectF, word: String, on: Boolean) {
        val paint = if (on) valuePaint else dimPaint
        paint.textSize = dp(14f)
        paint.letterSpacing = 0.18f
        val lamp = dp(8f)
        val wordW = paint.measureText(word)
        val cx = rect.centerX() - (wordW + lamp + dp(8f)) / 2f
        val cy = rect.centerY() + dp(2f)
        lampPaint.style = if (on) Paint.Style.FILL else Paint.Style.STROKE
        lampPaint.strokeWidth = dp(1.5f)
        lampPaint.color = if (on) amber else Color.parseColor("#6E5430")
        canvas.drawRect(cx, cy - lamp / 2f, cx + lamp, cy + lamp / 2f, lampPaint)
        val baseline = cy - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        canvas.drawText(word, cx + lamp + dp(8f), baseline, paint)
        paint.letterSpacing = 0f
    }

    private fun drawEnter(canvas: Canvas, rect: RectF, title: String, mark: RadarHub.EnterMark?) {
        plate(canvas, rect, hot = mark != null)
        clipped(canvas, rect) {
            fieldTitle(canvas, rect, title)
            if (mark == null) {
                dimPaint.textSize = dp(15f)
                canvas.drawText("STANDBY", rect.left + dp(8f), rect.top + dp(42f), dimPaint)
                dimPaint.textSize = dp(13f)
                canvas.drawText("--", rect.left + dp(8f), rect.top + dp(64f), dimPaint)
            } else {
                valuePaint.textSize = dp(16f)
                val whenText = Instant.ofEpochMilli(mark.atMs)
                    .atZone(ZoneId.systemDefault())
                    .format(clock)
                canvas.drawText(whenText, rect.left + dp(8f), rect.top + dp(42f), valuePaint)
                valuePaint.textSize = dp(13f)
                val where = String.format(Locale.US, "%.1f m   AZ %d°", mark.meters, mark.azimuthDeg)
                canvas.drawText(where, rect.left + dp(8f), rect.top + dp(64f), valuePaint)
            }
        }
    }

    private fun drawTarget(canvas: Canvas, rect: RectF) {
        val primary = blips.firstOrNull { it.slot == 0 } ?: blips.firstOrNull()
        plate(canvas, rect, hot = primary != null)
        clipped(canvas, rect) {
            fieldTitle(canvas, rect, "T1")
            if (primary == null) {
                dimPaint.textSize = dp(14f)
                canvas.drawText("NO CONTACT", rect.left + dp(8f), rect.top + dp(78f), dimPaint)
                drawTape(canvas, rect, null, Color.parseColor("#6E5430"))
                return@clipped
            }
            val meters = hypot(primary.xMm.toDouble(), primary.yMm.toDouble()).toFloat() / 1000f
            val az = Math.toDegrees(atan2(primary.xMm.toDouble(), primary.yMm.toDouble())).toInt()
            val near = meters <= 2.5f
            val tone = when {
                near -> nearRed
                primary.spdCms < 0 -> amber
                else -> amberSoft
            }
            val motion = when {
                near -> "NEAR"
                primary.spdCms < 0 -> "CLOSING"
                primary.spdCms > 0 -> "OPENING"
                else -> "HOLD"
            }
            tagPaint.color = tone
            tagPaint.textSize = dp(11f)
            val tagW = tagPaint.measureText(motion)
            canvas.drawText(motion, rect.right - tagW - dp(8f), rect.top + dp(14f), tagPaint)

            valuePaint.color = tone
            val num = String.format(Locale.US, "%.1f", meters)
            val numX = rect.left + dp(8f)
            val heroY = rect.top + dp(54f)
            valuePaint.textSize = dp(30f)
            val heroW = valuePaint.measureText(num)
            canvas.drawText(num, numX, heroY, valuePaint)
            valuePaint.textSize = dp(12f)
            canvas.drawText("m", numX + heroW + dp(4f), heroY, valuePaint)

            valuePaint.color = amber
            valuePaint.textSize = dp(13f)
            canvas.drawText("AZ $az°", numX, rect.top + dp(80f), valuePaint)
            val spd = if (primary.spdCms > 0) "+${primary.spdCms}" else primary.spdCms.toString()
            canvas.drawText("$spd cm/s", numX, rect.top + dp(100f), valuePaint)

            val rest = blips.filter { it.slot != primary.slot }.take(2)
            dimPaint.textSize = dp(11f)
            val step = dp(14f)
            val room = rect.right - numX - dp(8f)
            var y = rect.bottom - dp(34f) - (rest.size - 1).coerceAtLeast(0) * step
            for (blip in rest) {
                val d = hypot(blip.xMm.toDouble(), blip.yMm.toDouble()) / 1000.0
                val baz = Math.toDegrees(atan2(blip.xMm.toDouble(), blip.yMm.toDouble())).toInt()
                val full = String.format(Locale.US, "T%d  %.1f m  AZ %d°  %+d", blip.slot + 1, d, baz, blip.spdCms)
                val text = if (dimPaint.measureText(full) <= room) {
                    full
                } else {
                    String.format(Locale.US, "T%d  %.1f m  AZ %d°", blip.slot + 1, d, baz)
                }
                canvas.drawText(text, numX, y, dimPaint)
                y += step
            }
            drawTape(canvas, rect, meters, tone)
        }
        valuePaint.color = amber
    }

    private fun drawTape(canvas: Canvas, rect: RectF, meters: Float?, tone: Int) {
        val left = rect.left + dp(10f)
        val right = rect.right - dp(10f)
        val y = rect.bottom - dp(8f)
        canvas.drawLine(left, y, right, y, dimLine)
        val ticks = 4
        for (i in 0..ticks) {
            val x = left + (right - left) * i / ticks.toFloat()
            val h = if (i == 0 || i == ticks || i == ticks / 2) dp(5f) else dp(3f)
            canvas.drawLine(x, y - h, x, y, dimLine)
        }
        dimPaint.textSize = dp(8f)
        val end = rangeM.toString()
        canvas.drawText("0", left, y - dp(7f), dimPaint)
        canvas.drawText(end, right - dimPaint.measureText(end), y - dp(7f), dimPaint)
        if (meters == null) return
        val t = (meters / rangeM.toFloat()).coerceIn(0f, 1f)
        val x = left + (right - left) * t
        lampPaint.style = Paint.Style.FILL
        lampPaint.color = tone
        canvas.drawRect(x - dp(1.5f), y - dp(10f), x + dp(1.5f), y + dp(2f), lampPaint)
    }

    private fun fieldTitle(canvas: Canvas, rect: RectF, title: String) {
        labelPaint.color = labelAmber
        labelPaint.textSize = dp(8f)
        labelPaint.letterSpacing = 0.16f
        canvas.drawText(title, rect.left + dp(8f), rect.top + dp(14f), labelPaint)
        val y = rect.top + dp(20f)
        canvas.drawLine(rect.left + dp(8f), y, rect.right - dp(8f), y, dimLine)
    }

    private fun plate(canvas: Canvas, rect: RectF, hot: Boolean) {
        canvas.drawRect(rect, if (hot) hotFill else fillPaint)
        val edge = if (hot) linePaint else dimLine
        canvas.drawRect(rect, edge)
        frame(canvas, rect, edge)
    }

    private fun frame(canvas: Canvas, rect: RectF, paint: Paint) {
        val inner = dp(3f)
        canvas.drawRect(
            rect.left + inner,
            rect.top + inner,
            rect.right - inner,
            rect.bottom - inner,
            dimLine
        )
        brackets(canvas, rect, paint)
    }

    private fun brackets(canvas: Canvas, rect: RectF, paint: Paint) {
        val arm = dp(9f)
        canvas.drawLine(rect.left, rect.top, rect.left + arm, rect.top, paint)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + arm, paint)
        canvas.drawLine(rect.right, rect.top, rect.right - arm, rect.top, paint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + arm, paint)
        canvas.drawLine(rect.left, rect.bottom, rect.left + arm, rect.bottom, paint)
        canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - arm, paint)
        canvas.drawLine(rect.right, rect.bottom, rect.right - arm, rect.bottom, paint)
        canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - arm, paint)
    }

    private inline fun clipped(canvas: Canvas, rect: RectF, block: () -> Unit) {
        canvas.save()
        canvas.clipRect(rect.left + 1f, rect.top + 1f, rect.right - 1f, rect.bottom - 1f)
        block()
        canvas.restore()
    }

    private fun modeH(): Float = dp(44f)

    private fun gap(): Float = dp(6f)

    private fun enterH(): Float = dp(74f)

    private fun bodyH(): Float = enterH() * 2f + gap() + extraCount() * dp(16f)

    private fun extraCount(): Int = extrasOf(blips)

    private fun extrasOf(items: List<RadarHub.Blip>): Int {
        return (items.size - if (items.isEmpty()) 0 else 1).coerceIn(0, 2)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
