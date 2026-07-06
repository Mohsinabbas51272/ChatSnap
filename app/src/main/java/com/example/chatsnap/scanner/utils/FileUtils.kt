package com.example.chatsnap.scanner.utils

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object FileUtils {

    /**
     * Creates a new empty file in the app-specific Documents directory.
     * Generates a filename like: Scan_YYYY_MM_DD_HH_MM_SS.extension
     */
    fun createTimestampedFile(context: Context, extension: String): File {
        val timestamp = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US).format(Date())
        val fileName = "Scan_$timestamp$extension"
        val documentsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        if (documentsDir != null && !documentsDir.exists()) {
            documentsDir.mkdirs()
        }
        return File(documentsDir, fileName)
    }

    /**
     * Saves a bitmap image as a JPEG to the specified output file.
     */
    fun saveBitmapAsJpg(bitmap: Bitmap, outputFile: File, quality: Int = 90) {
        FileOutputStream(outputFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos)
        }
    }

    /**
     * Converts a File to a content URI using the app's FileProvider.
     */
    fun getUriForFile(context: Context, file: File): Uri {
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, file)
    }
}
