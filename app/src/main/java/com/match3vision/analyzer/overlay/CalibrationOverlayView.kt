package com.match3vision.analyzer.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.match3vision.analyzer.input.CalibrationTouch
import com.match3vision.analyzer.input.CoordinateSelfCheck

/**
 * Full-screen target for TESZT ÉRINTÉS.
 *
 * The finger lands on this window. Every action is consumed so the touch
 * does not reach the game. This view does not call dispatchGesture.
 */
class CalibrationOverlayView(
    context: Context,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val onRawUp: (rawX: Float, rawY: Float) -> Unit,
) : View(context) {

    private val scrim = Paint().apply { color = 0x88000000.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        val (ex, ey) = CalibrationTouch.expectedPoint(screenWidth, screenHeight)
        canvas.drawCircle(ex, ey, CoordinateSelfCheck.MEASURED_TOLERANCE_PX, ring)
        canvas.drawText(
            "Érintsd a jelet",
            ex,
            ey - CoordinateSelfCheck.MEASURED_TOLERANCE_PX - 24f,
            label,
        )
        canvas.drawText(
            "Nincs játékérintés",
            ex,
            ey + CoordinateSelfCheck.MEASURED_TOLERANCE_PX + 48f,
            label,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            onRawUp(event.rawX, event.rawY)
        }
        return true
    }
}
