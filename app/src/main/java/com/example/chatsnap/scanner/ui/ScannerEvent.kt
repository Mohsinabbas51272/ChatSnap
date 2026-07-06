package com.example.chatsnap.scanner.ui

import android.graphics.Bitmap
import android.net.Uri

sealed class ScannerEvent {
    object LaunchSmartScanner : ScannerEvent()
    object OpenCustomCamera : ScannerEvent()
    data class ImageCaptured(val bitmap: Bitmap) : ScannerEvent()
    data class ImageSelectedFromGallery(val bitmap: Bitmap) : ScannerEvent()
    data class CropCompleted(
        val viewPoints: List<android.graphics.PointF>,
        val viewWidth: Float,
        val viewHeight: Float
    ) : ScannerEvent()
    object CancelCrop : ScannerEvent()
    data class FilterChanged(val filter: ImageFilterType) : ScannerEvent()
    object RotateLeft : ScannerEvent()
    object RotateRight : ScannerEvent()
    object Retake : ScannerEvent()
    object SaveAsJpg : ScannerEvent()
    object SaveAsPdf : ScannerEvent()
    object ShareClicked : ScannerEvent()
    object ErrorDismissed : ScannerEvent()
    object NavigateBack : ScannerEvent()
    object SaveResultHandled : ScannerEvent()  // Clears saveSuccessUri after consumption
    data class MlKitScanSuccess(val jpegs: List<Uri>, val pdf: Uri?) : ScannerEvent()
    data class ScanFailed(val error: String) : ScannerEvent()
}
