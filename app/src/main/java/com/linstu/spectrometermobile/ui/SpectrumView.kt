package com.linstu.spectrometermobile.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.linstu.spectrometermobile.spectrometer.SpectrometerEngine

/**
 * 光谱图 —— 移植 VB ShowSpectrumGraph：
 *   网格刻度（X nm / Y 强度）、彩色波长填充、黑色曲线、峰值标记、校准点、十字光标。
 */
class SpectrumView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var engine: SpectrometerEngine? = null
    var showPeaks: Boolean = true
    var showTrimPoints: Boolean = true
    var cursorNm: Double = 0.0
        private set
    var cursorValue: Double = 0.0
        private set

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY; strokeWidth = 1f
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY; strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 22f
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 70, 0); strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED; strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY; strokeWidth = 1f; pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }

    private val leftPad = 90f
    private val topPad = 40f
    private val bottomPad = 50f
    private val rightPad = 20f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val e = engine ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val left = leftPad
        val right = w - rightPad
        val top = topPad
        val bottom = h - bottomPad
        val dw = right - left
        val dh = bottom - top

        canvas.drawColor(Color.rgb(240, 248, 255))

        if (e.visibleSamples.isEmpty() || e.maxVisibleValue <= 0f) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("无数据 / 黑帧", w / 2, h / 2, textPaint)
            return
        }

        // ---- Y 网格（0..100%） ----
        for (i in 0..100 step 10) {
            val y = bottom - i / 100f * dh
            canvas.drawLine(left, y, right, y, if (i % 50 == 0) axisPaint else gridPaint)
            val v = e.yToValue(y.toDouble(), bottom.toInt(), top.toInt())
            val label = if (v >= 100000) "${(v / 1000).toInt()}K" else v.toInt().toString()
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(label, left - 8, y + 8, textPaint)
        }

        // ---- X 网格（nm） ----
        val nmStart = e.xToNanometers(0, left.toInt(), dw.toInt())
        val nmEnd = e.xToNanometers(dw.toInt(), left.toInt(), dw.toInt())
        val step = when {
            nmEnd - nmStart > 800 -> 100
            nmEnd - nmStart > 400 -> 50
            nmEnd - nmStart > 200 -> 20
            else -> 10
        }
        var nm = (nmStart / step).toInt() * step
        while (nm <= nmEnd) {
            val x = e.nanometersToX(nm.toDouble(), left.toInt(), dw.toInt()).toFloat()
            if (x in left..right) {
                canvas.drawLine(x, top, x, bottom, if (nm % 100 == 0) axisPaint else gridPaint)
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(nm.toString(), x, h - 15, textPaint)
            }
            nm += step
        }
        canvas.drawLine(left, top, left, bottom, axisPaint)

        // ---- 彩色波长填充 ----
        if (e.useColors) {
            var oldX = left
            var oldY = bottom
            for (i in e.visibleSamples.indices) {
                val x = e.binToX(i, left.toInt(), dw.toInt()).toFloat()
                val nmX = e.xToNanometers(x.toInt(), left.toInt(), dw.toInt())
                val y = e.valueToY(e.visibleSamples[i].toDouble(), bottom.toInt(), top.toInt()).toFloat()
                fillPaint.color = e.wavelengthToColor(nmX)
                canvas.drawLine(x, bottom, x, y, fillPaint)
                oldX = x; oldY = y
            }
        }

        // ---- 黑色曲线 ----
        var oldX = left
        var oldY = bottom
        for (i in e.visibleSamples.indices) {
            val x = e.binToX(i, left.toInt(), dw.toInt()).toFloat()
            val y = e.valueToY(e.visibleSamples[i].toDouble(), bottom.toInt(), top.toInt()).toFloat()
            canvas.drawLine(oldX, oldY, x, y, curvePaint)
            oldX = x; oldY = y
        }

        // ---- 峰值标记 ----
        if (showPeaks) {
            for (p in e.findPeaks()) {
                val x = e.binToX(p, left.toInt(), dw.toInt()).toFloat()
                val y = e.valueToY(e.visibleSamples[p].toDouble(), bottom.toInt(), top.toInt()).toFloat()
                canvas.drawCircle(x, y, 8f, peakPaint)
                val nm = e.xToNanometers(x.toInt(), left.toInt(), dw.toInt())
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.color = Color.RED
                canvas.drawText("${"%.1f".format(nm)}nm", x, y - 16, textPaint)
                textPaint.color = Color.BLACK
            }
        }

        // ---- 校准点标记 ----
        if (showTrimPoints) {
            val bins = e.getCalibrationBins()
            val nms = e.getCalibrationNm()
            for (i in bins.indices) {
                val nm = nms[i]
                val x = e.nanometersToX(nm, left.toInt(), dw.toInt()).toFloat()
                if (x in left..right) {
                    canvas.drawLine(x, top, x, bottom, cursorPaint)
                    textPaint.textAlign = Paint.Align.CENTER
                    textPaint.color = Color.BLUE
                    canvas.drawText("▲${"%.0f".format(nm)}", x, top - 8, textPaint)
                    textPaint.color = Color.BLACK
                }
            }
        }

        // ---- 十字光标 ----
        if (cursorNm > 0) {
            val x = e.nanometersToX(cursorNm, left.toInt(), dw.toInt()).toFloat()
            if (x in left..right) {
                canvas.drawLine(x, top, x, bottom, cursorPaint)
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText("${"%.1f".format(cursorNm)}nm / ${cursorValue.toInt()}", x, bottom + 20, textPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val e = engine ?: return false
        val left = leftPad
        val dw = width - leftPad - rightPad
        val x = event.x - left
        if (event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_DOWN) {
            cursorNm = e.xToNanometers(x.toInt(), left.toInt(), dw.toInt())
            val bin = e.xToBin(x.toInt(), left.toInt(), dw.toInt())
            cursorValue = if (bin in e.visibleSamples.indices) e.visibleSamples[bin].toDouble() else 0.0
            invalidate()
            return true
        }
        return super.onTouchEvent(event)
    }
}
