package com.example.eyegaze.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.PI

/**
 * Builds the model input for ONE eye: a 160x96 grayscale crop, centred on the eye,
 * de-rotated so the inter-eye line is horizontal, scaled so the eye fills the frame
 * (verified on the official demo image: crop width ~= 0.55 * inter-eye distance).
 */
object EyeRegionExtractor {
    const val TARGET_WIDTH = 160
    const val TARGET_HEIGHT = 96

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * @param angleRad angle of the inter-eye line in image coordinates (y down)
     * @param cropW    crop width in source pixels (height = cropW * 0.6)
     */
    fun extract(frame: Bitmap, cx: Float, cy: Float, angleRad: Float, cropW: Float): FloatArray {
        val dst = Bitmap.createBitmap(TARGET_WIDTH, TARGET_HEIGHT, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply {
            postTranslate(-cx, -cy)
            postRotate((-angleRad * 180.0 / PI).toFloat())
            val s = TARGET_WIDTH / cropW
            postScale(s, s)
            postTranslate(TARGET_WIDTH / 2f, TARGET_HEIGHT / 2f)
        }
        Canvas(dst).drawBitmap(frame, m, paint)

        val px = IntArray(TARGET_WIDTH * TARGET_HEIGHT)
        dst.getPixels(px, 0, TARGET_WIDTH, 0, 0, TARGET_WIDTH, TARGET_HEIGHT)
        dst.recycle()
        return FloatArray(px.size) {
            val p = px[it]
            (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
        }
    }
}
