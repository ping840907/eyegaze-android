package com.example.eyegaze.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Debug-only self test: runs the model on the official demo image (known gaze: both eyes looking
 * to the image-left and slightly down) using different crop strategies, so we can verify what
 * input the model actually expects. Trigger with:
 *   adb shell am start -n com.example.eyegaze/.ui.MainActivity --ez selftest true
 */
object SelfTest {
    private const val TAG = "SelfTest"

    // Eye centers measured on the 1920x1080 demo image (image-left eye, image-right eye)
    private val eyes = listOf("imgLeftEye" to (580f to 462f), "imgRightEye" to (1303f to 457f))
    private const val EYE_DIST = 723f

    fun run(context: Context) {
        val engine = TFLiteInferenceEngine()
        if (!engine.initialize(context)) {
            Log.e(TAG, "engine init failed")
            return
        }
        val src = context.assets.open("debug_demo.png").use { BitmapFactory.decodeStream(it) }
        val outDir = context.getExternalFilesDir(null) ?: context.filesDir
        Log.i(TAG, "src=${src.width}x${src.height} outDir=$outDir")

        val widthFactors = listOf(0.30f, 0.40f, 0.50f, 0.60f, 0.75f)
        for ((name, c) in eyes) {
            for (flip in listOf(false, true)) {
                for (wf in widthFactors) {
                    val cropW = EYE_DIST * wf
                    val cropH = cropW * 96f / 160f
                    val left = (c.first - cropW / 2f).toInt().coerceIn(0, src.width - 2)
                    val top = (c.second - cropH / 2f).toInt().coerceIn(0, src.height - 2)
                    val w = cropW.toInt().coerceAtMost(src.width - left)
                    val h = cropH.toInt().coerceAtMost(src.height - top)
                    var crop = Bitmap.createBitmap(src, left, top, w, h)
                    crop = Bitmap.createScaledBitmap(crop, 160, 96, true)
                    if (flip) {
                        val m = Matrix().apply { postScale(-1f, 1f, 80f, 48f) }
                        crop = Bitmap.createBitmap(crop, 0, 0, 160, 96, m, true)
                    }
                    val floats = toGrayFloats(crop)
                    val p = engine.predict(floats)
                    val lm = p.landmarks
                    var minX = 1e9f; var maxX = -1e9f; var minY = 1e9f; var maxY = -1e9f
                    lm?.forEach {
                        minX = minOf(minX, it[0]); maxX = maxOf(maxX, it[0])
                        minY = minOf(minY, it[1]); maxY = maxOf(maxY, it[1])
                    }
                    val tag = "${name}_flip${if (flip) 1 else 0}_w${(wf * 100).toInt()}"
                    Log.i(TAG, "$tag pitch=${p.pitch} yaw=${p.yaw} lmX=[$minX,$maxX] lmY=[$minY,$maxY]")
                    saveOverlay(crop, lm, File(outDir, "$tag.png"))
                }
            }
        }
        Log.i(TAG, "DONE")
        engine.close()
    }

    private fun toGrayFloats(bmp: Bitmap): FloatArray {
        val px = IntArray(160 * 96)
        bmp.getPixels(px, 0, 160, 0, 0, 160, 96)
        return FloatArray(px.size) {
            val p = px[it]
            (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
        }
    }

    private fun saveOverlay(crop: Bitmap, lm: Array<FloatArray>?, file: File) {
        val big = Bitmap.createScaledBitmap(crop, 480, 288, true).copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(big)
        val paint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
        lm?.forEachIndexed { i, pt ->
            // hypothesis C (cyan): pt = (row, col) in the 48x80 heatmap grid
            paint.color = if (i < 8) Color.YELLOW else Color.CYAN
            canvas.drawCircle(pt[1] / 80f * 480f, pt[0] / 48f * 288f, 5f, paint)
        }
        FileOutputStream(file).use { big.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
