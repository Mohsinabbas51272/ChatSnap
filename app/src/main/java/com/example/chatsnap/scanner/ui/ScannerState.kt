package com.example.chatsnap.scanner.ui

import android.graphics.Bitmap
import android.net.Uri

enum class ScannerMode {
    DASHBOARD, CAMERA, CROP, PREVIEW
}

enum class ImageFilterType {
    ORIGINAL, COLOR, GRAYSCALE, BLACK_WHITE
}

data class ScannerState(
    val mode: ScannerMode = ScannerMode.DASHBOARD,
    val originalBitmap: Bitmap? = null,
    val croppedBitmap: Bitmap? = null,
    val previewBitmap: Bitmap? = null,
    val activeFilter: ImageFilterType = ImageFilterType.ORIGINAL,
    val activeRotation: Float = 0f,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val saveSuccessUri: Uri? = null,
    val saveSuccessMimeType: String? = null, // "image/jpeg" or "application/pdf"
    val savedFilePath: String? = null        // Human-readable path for display
)
