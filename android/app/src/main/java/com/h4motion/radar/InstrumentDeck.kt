package com.h4motion.radar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
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
 * Link mode, the first ENTER, the latest ENTER, and the closest contact.
 */
class InstrumentDeck @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var valueColor = Color.parseColor("#FFC14D")
    private var openingColor = Color.parseColor("#E7C48A")
    private var closingColor = Color.parseColor("#FFC14D")
    private var nearColor = Color.parseColor("#FF5A3C")
    private var labelColor = Color.parseColor("#C4893A")
    private var inactiveColor = Color.parseColor("#6E5430")
    private var activeInk = Color.parseColor("#FFC14D")

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#140F0A")
    }
    private val hotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2A1A0C")
    }
    private val modeHot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
        color = labelColor
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.16f
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = valueColor
        typeface = Typeface.MONOSPACE
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = inactiveColor
        typeface = Typeface.MONOSPACE
    }
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = valueColor
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.12f
    }

    /** White marks on black. The lit half of the mode bar flips to black on white. */
    fun setHighContrast(on: Boolean) {
        if (on) {
            valueColor = Color.WHITE
            openingColor = Color.WHITE
            closingColor = Color.parseColor("#FFE14A")
            nearColor = Color.parseColor("#FF2A2A")
            labelColor = Color.WHITE
            inactiveColor = Color.WHITE
            activeInk = Color.BLACK
            fillPaint.color = Color.BLACK
            hotFill.color = Color.BLACK
            modeHot.color = Color.WHITE
            linePaint.color = Color.WHITE
            linePaint.strokeWidth = 3f
            dimLine.color = Color.WHITE
            dimLine.strokeWidth = 2f
        } else {
            valueColor = Color.parseColor("#FFC14D")
            openingColor = Color.parseColor("#E7C48A")
            closingColor = Color.parseColor("#FFC14D")
            nearColor = Color.parseColor("#FF5A3C")
            labelColor = Color.parseColor("#C4893A")
            inactiveColor = Color.parseColor("#6E5430")
            activeInk = Color.parseColor("#FFC14D")
            fillPaint.color = Color.parseColor("#140F0A")
            hotFill.color = Color.parseColor("#2A1A0C")
            modeHot.color = Color.parseColor("#2A1A0C")
            linePaint.color = Color.parseColor("#A56B2A")
            linePaint.strokeWidth = 1.5f
            dimLine.color = Color.parseColor("#5C3E18")
            dimLine.strokeWidth = 1.5f
        }
        labelPaint.color = labelColor
        valuePaint.color = valueColor
        dimPaint.color = inactiveColor
        tagPaint.color = valueColor
        invalidate()
    }
    private val lampPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    var linkWifi: Boolean = false
        private set
    private var firstEnter: RadarHub.EnterMark? = null
    private var lastEnter: RadarHub.EnterMark? = null
    private var blips: List<RadarHub.Blip> = emptyList()
    private var rangeM: Int = 8
    private var sampleElapsedMs: Long = 0L
    private val ageTick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || windowVisibility != VISIBLE) return
            if (sampleElapsedMs != 0L) invalidate()
            postDelayed(this, 100L)
        }
    }

    fun bind(
        linkWifi: Boolean,
        firstEnter: RadarHub.EnterMark?,
        lastEnter: RadarHub.EnterMark?,
        blips: List<RadarHub.Blip>,
        rangeM: Int,
        sampleElapsedMs: Long
    ) {
        this.linkWifi = linkWifi
        this.firstEnter = firstEnter
        this.lastEnter = lastEnter
        this.blips = blips
        this.rangeM = rangeM.coerceAtLeast(1)
        this.sampleElapsedMs = sampleElapsedMs
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAgeTick()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ageTick)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startAgeTick() else removeCallbacks(ageTick)
    }

    private fun startAgeTick() {
        removeCallbacks(ageTick)
        postDelayed(ageTick, 100L)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(suggestedMinimumWidth)
        val height = (modeH() + gap() + bodyH()).toInt().coerceAtLeast(1)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        valuePaint.color = valueColor
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
            modeHot
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
        val ink = if (on) activeInk else inactiveColor
        paint.color = ink
        paint.textSize = dp(14f)
        paint.letterSpacing = 0.18f
        val lamp = dp(8f)
        val wordW = paint.measureText(word)
        val cx = rect.centerX() - (wordW + lamp + dp(8f)) / 2f
        val cy = rect.centerY() + dp(2f)
        lampPaint.style = if (on) Paint.Style.FILL else Paint.Style.STROKE
        lampPaint.strokeWidth = dp(1.5f)
        lampPaint.color = ink
        canvas.drawRect(cx, cy - lamp / 2f, cx + lamp, cy + lamp / 2f, lampPaint)
        val baseline = cy - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        canvas.drawText(word, cx + lamp + dp(8f), baseline, paint)
        paint.letterSpacing = 0f
        if (on) valuePaint.color = valueColor
        else dimPaint.color = inactiveColor
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
        val primary = closestBlip()
        plate(canvas, rect, hot = primary != null)
        clipped(canvas, rect) {
            fieldTitle(canvas, rect, "T1")
            val numX = rect.left + dp(8f)
            if (primary == null) {
                dimPaint.textSize = dp(14f)
                canvas.drawText("NO CONTACT", numX, rect.top + dp(78f), dimPaint)
                drawLatency(canvas, numX, rect.top + dp(122f))
                drawTape(canvas, rect, null, inactiveColor)
                return@clipped
            }
            val meters = hypot(primary.xMm.toDouble(), primary.yMm.toDouble()).toFloat() / 1000f
            val az = Math.toDegrees(atan2(primary.xMm.toDouble(), primary.yMm.toDouble())).toInt()
            val near = meters <= 2.5f
            val tone = when {
                near -> nearColor
                primary.spdCms < 0 -> closingColor
                else -> openingColor
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
            val heroY = rect.top + dp(54f)
            valuePaint.textSize = dp(30f)
            val heroW = valuePaint.measureText(num)
            canvas.drawText(num, numX, heroY, valuePaint)
            valuePaint.textSize = dp(12f)
            canvas.drawText("m", numX + heroW + dp(4f), heroY, valuePaint)

            valuePaint.color = valueColor
            valuePaint.textSize = dp(13f)
            canvas.drawText("AZ $az°", numX, rect.top + dp(80f), valuePaint)
            val spd = if (primary.spdCms > 0) "+${primary.spdCms}" else primary.spdCms.toString()
            canvas.drawText("$spd cm/s", numX, rect.top + dp(100f), valuePaint)
            drawLatency(canvas, numX, rect.top + dp(122f))
            drawTape(canvas, rect, meters, tone)
        }
        valuePaint.color = valueColor
    }

    /** Age of the picture on screen. A steady link sawtooths up to about 333 ms. */
    private fun drawLatency(canvas: Canvas, x: Float, baseline: Float) {
        val age = if (sampleElapsedMs == 0L) {
            -1L
        } else {
            (SystemClock.elapsedRealtime() - sampleElapsedMs).coerceAtLeast(0L)
        }
        val text = when {
            age < 0L -> "LAT --"
            age >= 10000L -> "LAT 10s+"
            age >= 1000L -> String.format(Locale.US, "LAT %.1fs", age / 1000.0)
            else -> String.format(Locale.US, "LAT %d ms", age)
        }
        val paint = if (age >= 1000L) valuePaint else dimPaint
        if (age >= 1000L) valuePaint.color = nearColor
        paint.textSize = dp(12f)
        canvas.drawText(text, x, baseline, paint)
        if (age >= 1000L) valuePaint.color = valueColor
    }

    private fun closestBlip(): RadarHub.Blip? {
        var best: RadarHub.Blip? = null
        var bestD = Long.MAX_VALUE
        for (blip in blips) {
            val d = blip.xMm.toLong() * blip.xMm + blip.yMm.toLong() * blip.yMm
            if (d < bestD) {
                bestD = d
                best = blip
            }
        }
        return best
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
        labelPaint.color = labelColor
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

    private fun bodyH(): Float = enterH() * 2f + gap()

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
