package com.example.chatsnap.scanner.utils

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

object PdfUtils {

    /**
     * Compiles a list of image bitmaps into a single PDF document.
     * Runs offline and writes the output directly to the specified file.
     */
    fun createPdf(bitmaps: List<Bitmap>, outputFile: File) {
        val pdfDocument = PdfDocument()

        try {
            bitmaps.forEachIndexed { index, bitmap ->
                // Create a page info matching the dimensions of the bitmap
                val pageInfo = PdfDocument.PageInfo.Builder(
                    bitmap.width,
                    bitmap.height,
                    index + 1
                ).create()
                
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
                
                pdfDocument.finishPage(page)
            }

            FileOutputStream(outputFile).use { fos ->
                pdfDocument.writeTo(fos)
            }
        } finally {
            pdfDocument.close()
        }
    }
}
