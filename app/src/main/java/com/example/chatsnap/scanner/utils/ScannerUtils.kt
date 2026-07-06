package com.example.chatsnap.scanner.utils

import android.graphics.*
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

object ScannerUtils {

    /**
     * Map points from View coordinates to actual Bitmap coordinates.
     * Takes into account ScaleType.FIT_CENTER alignment.
     */
    fun mapViewPointsToBitmapPoints(
        viewPoints: List<PointF>,
        viewWidth: Float,
        viewHeight: Float,
        bitmapWidth: Float,
        bitmapHeight: Float
    ): List<PointF> {
        val scale = min(viewWidth / bitmapWidth, viewHeight / bitmapHeight)
        val actualImageWidth = bitmapWidth * scale
        val actualImageHeight = bitmapHeight * scale

        val leftOffset = (viewWidth - actualImageWidth) / 2f
        val topOffset = (viewHeight - actualImageHeight) / 2f

        return viewPoints.map { point ->
            val mappedX = ((point.x - leftOffset) / scale).coerceIn(0f, bitmapWidth)
            val mappedY = ((point.y - topOffset) / scale).coerceIn(0f, bitmapHeight)
            PointF(mappedX, mappedY)
        }
    }

    /**
     * Map points from Bitmap coordinates back to View coordinates.
     */
    fun mapBitmapPointsToViewPoints(
        bitmapPoints: List<PointF>,
        viewWidth: Float,
        viewHeight: Float,
        bitmapWidth: Float,
        bitmapHeight: Float
    ): List<PointF> {
        val scale = min(viewWidth / bitmapWidth, viewHeight / bitmapHeight)
        val actualImageWidth = bitmapWidth * scale
        val actualImageHeight = bitmapHeight * scale

        val leftOffset = (viewWidth - actualImageWidth) / 2f
        val topOffset = (viewHeight - actualImageHeight) / 2f

        return bitmapPoints.map { point ->
            val viewX = (point.x * scale) + leftOffset
            val viewY = (point.y * scale) + topOffset
            PointF(viewX, viewY)
        }
    }

    /**
     * Warps a quad-shaped area inside originalBitmap to a flat, rectified rectangular Bitmap.
     */
    fun perspectiveWarp(originalBitmap: Bitmap, points: List<PointF>): Bitmap {
        if (points.size != 4) return originalBitmap

        val p0 = points[0] // TL
        val p1 = points[1] // TR
        val p2 = points[2] // BR
        val p3 = points[3] // BL

        // Calculate destination width
        val widthA = sqrt((p1.x - p0.x).pow(2) + (p1.y - p0.y).pow(2))
        val widthB = sqrt((p2.x - p3.x).pow(2) + (p2.y - p3.y).pow(2))
        val destWidth = max(widthA, widthB)

        // Calculate destination height
        val heightA = sqrt((p3.x - p0.x).pow(2) + (p3.y - p0.y).pow(2))
        val heightB = sqrt((p2.x - p1.x).pow(2) + (p2.y - p1.y).pow(2))
        val destHeight = max(heightA, heightB)

        val srcPoints = floatArrayOf(
            p0.x, p0.y,
            p1.x, p1.y,
            p2.x, p2.y,
            p3.x, p3.y
        )

        val dstPoints = floatArrayOf(
            0f, 0f,
            destWidth, 0f,
            destWidth, destHeight,
            0f, destHeight
        )

        val matrix = Matrix()
        matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)

        val resultBitmap = Bitmap.createBitmap(
            destWidth.toInt().coerceAtLeast(100),
            destHeight.toInt().coerceAtLeast(100),
            Bitmap.Config.ARGB_8888
        )
        
        val canvas = Canvas(resultBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(originalBitmap, matrix, paint)

        return resultBitmap
    }

    /**
     * Fast diagonal edge-detection heuristic to find the 4 corners of a document.
     * Falls back to a centered 15% inset rectangle if detection fails.
     */
    fun detectDocumentCorners(bitmap: Bitmap): List<PointF> {
        val w = bitmap.width
        val h = bitmap.height
        
        // Default fallback points
        val defaultTL = PointF(w * 0.15f, h * 0.15f)
        val defaultTR = PointF(w * 0.85f, h * 0.15f)
        val defaultBR = PointF(w * 0.85f, h * 0.85f)
        val defaultBL = PointF(w * 0.15f, h * 0.85f)

        try {
            // Downscale to process quickly
            val targetW = 240
            val targetH = 320
            val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, false)
            
            // Analyze corner pixels to find background luminance
            var bgSum = 0
            var bgCount = 0
            for (y in 0..10) {
                for (x in 0..10) {
                    bgSum += getLuminance(scaled.getPixel(x, y))
                    bgSum += getLuminance(scaled.getPixel(targetW - 1 - x, y))
                    bgSum += getLuminance(scaled.getPixel(x, targetH - 1 - y))
                    bgSum += getLuminance(scaled.getPixel(targetW - 1 - x, targetH - 1 - y))
                    bgCount += 4
                }
            }
            val bgAvg = bgSum / bgCount.coerceAtLeast(1)
            
            // Standard document paper is white (high luminance).
            // We search for a shift from dark background to light document.
            val threshold = (bgAvg + 30).coerceIn(110, 220)

            var tl = PointF(0f, 0f)
            var tr = PointF(targetW.toFloat(), 0f)
            var br = PointF(targetW.toFloat(), targetH.toFloat())
            var bl = PointF(0f, targetH.toFloat())

            // 1. Scan from top-left diagonally
            var found = false
            for (step in 1..80) {
                val x = (step * 2.4).toInt().coerceIn(0, targetW - 1)
                val y = (step * 3.2).toInt().coerceIn(0, targetH - 1)
                if (getLuminance(scaled.getPixel(x, y)) > threshold) {
                    tl = PointF(x.toFloat(), y.toFloat())
                    found = true
                    break
                }
            }
            if (!found) tl = PointF(targetW * 0.15f, targetH * 0.15f)

            // 2. Scan from top-right diagonally
            found = false
            for (step in 1..80) {
                val x = (targetW - 1 - (step * 2.4)).toInt().coerceIn(0, targetW - 1)
                val y = (step * 3.2).toInt().coerceIn(0, targetH - 1)
                if (getLuminance(scaled.getPixel(x, y)) > threshold) {
                    tr = PointF(x.toFloat(), y.toFloat())
                    found = true
                    break
                }
            }
            if (!found) tr = PointF(targetW * 0.85f, targetH * 0.15f)

            // 3. Scan from bottom-right diagonally
            found = false
            for (step in 1..80) {
                val x = (targetW - 1 - (step * 2.4)).toInt().coerceIn(0, targetW - 1)
                val y = (targetH - 1 - (step * 3.2)).toInt().coerceIn(0, targetH - 1)
                if (getLuminance(scaled.getPixel(x, y)) > threshold) {
                    br = PointF(x.toFloat(), y.toFloat())
                    found = true
                    break
                }
            }
            if (!found) br = PointF(targetW * 0.85f, targetH * 0.85f)

            // 4. Scan from bottom-left diagonally
            found = false
            for (step in 1..80) {
                val x = (step * 2.4).toInt().coerceIn(0, targetW - 1)
                val y = (targetH - 1 - (step * 3.2)).toInt().coerceIn(0, targetH - 1)
                if (getLuminance(scaled.getPixel(x, y)) > threshold) {
                    bl = PointF(x.toFloat(), y.toFloat())
                    found = true
                    break
                }
            }
            if (!found) bl = PointF(targetW * 0.15f, targetH * 0.85f)

            scaled.recycle()

            // Map the coordinates back to full image bounds
            val scaleX = w.toFloat() / targetW
            val scaleY = h.toFloat() / targetH

            return listOf(
                PointF(tl.x * scaleX, tl.y * scaleY),
                PointF(tr.x * scaleX, tr.y * scaleY),
                PointF(br.x * scaleX, br.y * scaleY),
                PointF(bl.x * scaleX, bl.y * scaleY)
            )

        } catch (e: Exception) {
            Log.e("ScannerUtils", "Autocorner detection failed: ${e.message}", e)
            return listOf(defaultTL, defaultTR, defaultBR, defaultBL)
        }
    }

    private fun getLuminance(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        // Standard luminance formula
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }
}
