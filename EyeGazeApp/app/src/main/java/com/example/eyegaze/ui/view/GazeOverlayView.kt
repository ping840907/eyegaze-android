package com.example.eyegaze.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot

class GazeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var gazeX: Float = -1f
    private var gazeY: Float = -1f
    private val gazeHistory = mutableListOf<PointF>()
    private val maxHistory = 18

    var showTrail: Boolean = true
    var showInteractiveTargets: Boolean = true
    var landmarks: Array<FloatArray>? = null

    // Dwell targets for testing gaze accuracy
    data class DwellTarget(val label: String, val normX: Float, val normY: Float, var dwellProgress: Float = 0f)
    val targets = listOf(
        DwellTarget("Target 1", 0.2f, 0.25f),
        DwellTarget("Target 2", 0.8f, 0.25f),
        DwellTarget("Target 3", 0.2f, 0.75f),
        DwellTarget("Target 4", 0.8f, 0.75f)
    )

    private val dotPaint = Paint().apply {
        color = Color.parseColor("#FF3D00")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val haloPaint = Paint().apply {
        color = Color.parseColor("#4DFF3D00")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val ringPaint = Paint().apply {
        color = Color.parseColor("#FF6D00")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val trailPaint = Paint().apply {
        color = Color.parseColor("#99FF3D00")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val targetBgPaint = Paint().apply {
        color = Color.parseColor("#331E293B")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val targetBorderPaint = Paint().apply {
        color = Color.parseColor("#8000E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val targetActivePaint = Paint().apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val landmarkPaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun updateGaze(x: Float, y: Float, eyeLandmarks: Array<FloatArray>?) {
        gazeX = x
        gazeY = y
        landmarks = eyeLandmarks

        if (x > 0 && y > 0) {
            gazeHistory.add(PointF(x, y))
            if (gazeHistory.size > maxHistory) {
                gazeHistory.removeAt(0)
            }

            // Update interactive target dwelling
            val w = width.toFloat()
            val h = height.toFloat()
            val hitRadius = 90f

            for (target in targets) {
                val tx = target.normX * w
                val ty = target.normY * h
                val dist = hypot((x - tx).toDouble(), (y - ty).toDouble()).toFloat()
                if (dist < hitRadius) {
                    target.dwellProgress = (target.dwellProgress + 0.08f).coerceAtMost(1f)
                } else {
                    target.dwellProgress = (target.dwellProgress - 0.04f).coerceAtLeast(0f)
                }
            }
        }

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Draw interactive dwell targets
        if (showInteractiveTargets) {
            for (t in targets) {
                val tx = t.normX * w
                val ty = t.normY * h
                val radius = 70f

                canvas.drawCircle(tx, ty, radius, targetBgPaint)
                canvas.drawCircle(tx, ty, radius, targetBorderPaint)

                if (t.dwellProgress > 0f) {
                    val activeRadius = radius * t.dwellProgress
                    val activePaint = Paint(targetActivePaint).apply {
                        alpha = (t.dwellProgress * 200).toInt()
                    }
                    canvas.drawCircle(tx, ty, activeRadius, activePaint)
                }

                canvas.drawText(t.label, tx, ty + 10f, textPaint)
            }
        }

        if (gazeX <= 0 || gazeY <= 0) return

        // 2. Draw gaze trail
        if (showTrail && gazeHistory.size > 1) {
            for (i in 0 until gazeHistory.size - 1) {
                val p1 = gazeHistory[i]
                val p2 = gazeHistory[i + 1]
                val alpha = ((i + 1).toFloat() / gazeHistory.size * 180).toInt()
                trailPaint.alpha = alpha
                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, trailPaint)
            }
        }

        // 3. Draw current gaze cursor
        // Outer glowing halo
        canvas.drawCircle(gazeX, gazeY, 44f, haloPaint)
        // Outer ring
        canvas.drawCircle(gazeX, gazeY, 26f, ringPaint)
        // Crosshair ticks
        canvas.drawLine(gazeX - 32f, gazeY, gazeX - 16f, gazeY, ringPaint)
        canvas.drawLine(gazeX + 16f, gazeY, gazeX + 32f, gazeY, ringPaint)
        canvas.drawLine(gazeX, gazeY - 32f, gazeX, gazeY - 16f, ringPaint)
        canvas.drawLine(gazeX, gazeY + 16f, gazeX, gazeY + 32f, ringPaint)
        // Solid center point
        canvas.drawCircle(gazeX, gazeY, 9f, dotPaint)

        // 4. Draw Eye Landmarks Mini-HUD (34 points) if available
        landmarks?.let { lms ->
            val boxW = 160f
            val boxH = 96f
            val boxLeft = w - boxW - 24f
            val boxTop = 80f
            val boxRect = RectF(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)

            val boxBg = Paint().apply {
                color = Color.parseColor("#B30F172A")
                style = Paint.Style.FILL
            }
            val boxStroke = Paint().apply {
                color = Color.parseColor("#4D00E5FF")
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            canvas.drawRoundRect(boxRect, 12f, 12f, boxBg)
            canvas.drawRoundRect(boxRect, 12f, 12f, boxStroke)

            for (lm in lms) {
                if (lm.size >= 2) {
                    // landmark = (row, col) in the 48x80 heatmap grid
                    val px = boxLeft + (lm[1] / 80f) * boxW
                    val py = boxTop + (lm[0] / 48f) * boxH
                    canvas.drawCircle(px, py, 2.5f, landmarkPaint)
                }
            }
        }
    }
}
