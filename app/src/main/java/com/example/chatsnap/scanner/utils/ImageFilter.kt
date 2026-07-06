package com.example.chatsnap.scanner.utils

import android.graphics.*
import com.example.chatsnap.scanner.ui.ImageFilterType

object ImageFilter {

    /**
     * Applies the requested image filter to the source bitmap.
     * Returns a new filtered bitmap, leaving the original bitmap unchanged.
     */
    fun applyFilter(sourceBitmap: Bitmap, filterType: ImageFilterType): Bitmap {
        if (filterType == ImageFilterType.ORIGINAL) {
            // Return a copy so the caller can recycle safely
            return sourceBitmap.copy(sourceBitmap.config ?: Bitmap.Config.ARGB_8888, true)
        }

        val resultBitmap = Bitmap.createBitmap(
            sourceBitmap.width,
            sourceBitmap.height,
            sourceBitmap.config ?: Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(resultBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val colorMatrix = ColorMatrix()
        when (filterType) {
            ImageFilterType.COLOR -> {
                // Enhance colors: slightly increase contrast (1.25) and brightness (10)
                val contrast = 1.25f
                val brightness = 15f
                colorMatrix.set(floatArrayOf(
                    contrast, 0f, 0f, 0f, brightness,
                    0f, contrast, 0f, 0f, brightness,
                    0f, 0f, contrast, 0f, brightness,
                    0f, 0f, 0f, 1f, 0f
                ))
            }
            ImageFilterType.GRAYSCALE -> {
                // Remove all saturation
                colorMatrix.setSaturation(0f)
            }
            ImageFilterType.BLACK_WHITE -> {
                // Convert to grayscale first, then apply high contrast thresholding
                val grayscaleMatrix = ColorMatrix()
                grayscaleMatrix.setSaturation(0f)
                
                val scale = 12f
                val translate = -6f * 255f
                val thresholdMatrix = ColorMatrix(floatArrayOf(
                    scale, 0f, 0f, 0f, translate,
                    0f, scale, 0f, 0f, translate,
                    0f, 0f, scale, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                ))
                
                colorMatrix.setConcat(thresholdMatrix, grayscaleMatrix)
            }
            else -> {}
        }

        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(sourceBitmap, 0f, 0f, paint)
        return resultBitmap
    }
}
