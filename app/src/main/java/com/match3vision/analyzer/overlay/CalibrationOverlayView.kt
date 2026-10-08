package com.match3vision.analyzer.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.match3vision.analyzer.input.CalibrationTarget
import com.match3vision.analyzer.input.CalibrationTouch
import com.match3vision.analyzer.input.CoordinateSelfCheck

/**
 * Full-screen target for TESZT ÉRINTÉS.
 *
 * The ring is drawn at [drawPoint] in this view's canvas. The caller compares
 * the raw finger to getLocationOnScreen of this view plus [drawPoint].
 * Another tap records again. KÉSZ closes the layer and does not record.
 * This view does not call dispatchGesture.
 */
class CalibrationOverlayView(
    context: Context,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val onRawUp: (rawX: Float, rawY: Float) -> Unit,
    private val onClose: () -> Unit,
) : View(context) {

    private val scrim = Paint().apply { color = 0xCC000000.toInt() }
    private val whiteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val redStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 18f
    }
    private val whiteStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 64f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val redLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        textSize = 64f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val instruction = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        textAlign = Paint.Align.CENTER
    }
    private val closePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 48f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val closeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB71C1C.toInt() }

    private var resultText: String = ""

    init {
        fitsSystemWindows = false
        setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, 0, 0, 0)
            insets
        }
    }

    /** Canvas centre of the ring. Not yet shifted by the window origin. */
    fun drawPoint(): Pair<Float, Float> =
        CalibrationTouch.expectedPoint(screenWidth, screenHeight)

    fun showResult(text: String) {
        resultText = text
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        val (ex, ey) = drawPoint()
        val ring = CalibrationTarget.RING_RADIUS_PX
        canvas.drawCircle(ex, ey, ring, redStroke)
        canvas.drawCircle(ex, ey, ring - 14f, whiteStroke)
        canvas.drawCircle(ex, ey, CoordinateSelfCheck.MEASURED_TOLERANCE_PX, whiteFill)
        canvas.drawCircle(ex, ey, 10f, redStroke)
        canvas.drawText(CalibrationTarget.LABEL, ex + 2f, ey - ring - 28f, redLabel)
        canvas.drawText(CalibrationTarget.LABEL, ex, ey - ring - 30f, label)
        canvas.drawText(CalibrationTarget.INSTRUCTION, width / 2f, 160f, instruction)
        if (resultText.isNotEmpty()) {
            canvas.drawText(resultText, width / 2f, ey + ring + 72f, label)
        }
        val close = closeRect()
        canvas.drawRoundRect(close, 16f, 16f, closeFill)
        canvas.drawText(CalibrationTarget.CLOSE_LABEL, close.centerX(), close.centerY() + 16f, closePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            if (closeRect().contains(event.x, event.y)) {
                onClose()
            } else {
                onRawUp(event.rawX, event.rawY)
            }
        }
        return true
    }

    private fun closeRect(): RectF {
        val w = 220f
        val h = 88f
        val left = (width - w) / 2f
        val top = 200f
        return RectF(left, top, left + w, top + h)
    }
}
