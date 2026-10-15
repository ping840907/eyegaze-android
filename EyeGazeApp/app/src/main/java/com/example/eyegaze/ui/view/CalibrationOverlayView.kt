package com.example.eyegaze.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.example.eyegaze.model.CalibrationPoint

class CalibrationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentTarget: CalibrationPoint? = null
    private var progress: Float = 0f
    private var isGazeStable: Boolean = false
    private val completedPoints = mutableListOf<CalibrationPoint>()

    private var pulseFactor: Float = 1f
    private val animator = ValueAnimator.ofFloat(0.85f, 1.25f).apply {
        duration = 800
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulseFactor = it.animatedValue as Float
            invalidate()
        }
    }

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#E60F172A")
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#15FFFFFF")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val auraPaint = Paint().apply {
        color = Color.parseColor("#4D00E5FF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val ringPaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val ringBgPaint = Paint().apply {
        color = Color.parseColor("#26FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val centerDotPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val completedPaint = Paint().apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    init {
        animator.start()
    }

    fun updateTarget(
        target: CalibrationPoint?,
        curProgress: Float,
        completed: List<CalibrationPoint>,
        stable: Boolean = false
    ) {
        currentTarget = target
        progress = curProgress
        isGazeStable = stable
        completedPoints.clear()
        completedPoints.addAll(completed)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Subtle guide grid
        canvas.drawLine(w * 0.5f, 0f, w * 0.5f, h, gridPaint)
        canvas.drawLine(0f, h * 0.5f, w, h * 0.5f, gridPaint)

        // Draw completed points
        for (pt in completedPoints) {
            val cx = pt.normalizedX * w
            val cy = pt.normalizedY * h
            canvas.drawCircle(cx, cy, 14f, completedPaint)
        }

        // Draw current target
        val target = currentTarget ?: return
        val cx = target.normalizedX * w
        val cy = target.normalizedY * h

        val baseRadius = 48f
        val auraRadius = baseRadius * 1.5f * pulseFactor

        // Color coding based on stability
        if (isGazeStable) {
            auraPaint.color = Color.parseColor("#4D00E676") // Bright green
            ringPaint.color = Color.parseColor("#00E676")
            centerDotPaint.color = Color.parseColor("#00E676")
        } else {
            auraPaint.color = Color.parseColor("#4DFF9100") // Amber
            ringPaint.color = Color.parseColor("#FF9100")
            centerDotPaint.color = Color.WHITE
        }

        // Aura
        canvas.drawCircle(cx, cy, auraRadius, auraPaint)

        // Background Track Ring
        canvas.drawCircle(cx, cy, baseRadius, ringBgPaint)

        // Progress Arc
        val rect = RectF(cx - baseRadius, cy - baseRadius, cx + baseRadius, cy + baseRadius)
        val sweepAngle = progress * 360f
        canvas.drawArc(rect, -90f, sweepAngle, false, ringPaint)

        // Center Pulsing Dot
        canvas.drawCircle(cx, cy, 12f * pulseFactor, centerDotPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }
}
