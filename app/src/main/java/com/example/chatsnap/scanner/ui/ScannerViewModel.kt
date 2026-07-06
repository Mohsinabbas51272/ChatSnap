package com.example.chatsnap.scanner.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatsnap.scanner.utils.FileUtils
import com.example.chatsnap.scanner.utils.ImageFilter
import com.example.chatsnap.scanner.utils.PdfUtils
import com.example.chatsnap.scanner.utils.ScannerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ScannerViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ScannerState())
    val state: StateFlow<ScannerState> = _state.asStateFlow()

    fun onEvent(event: ScannerEvent) {
        when (event) {
            is ScannerEvent.LaunchSmartScanner -> {
                _state.update { it.copy(isLoading = true) }
            }
            is ScannerEvent.OpenCustomCamera -> {
                _state.update { it.copy(mode = ScannerMode.CAMERA) }
            }
            is ScannerEvent.ImageCaptured -> {
                _state.update {
                    it.copy(
                        originalBitmap = event.bitmap,
                        mode = ScannerMode.CROP,
                        isLoading = false
                    )
                }
            }
            is ScannerEvent.ImageSelectedFromGallery -> {
                _state.update {
                    it.copy(
                        originalBitmap = event.bitmap,
                        mode = ScannerMode.CROP
                    )
                }
            }
            is ScannerEvent.CropCompleted -> {
                performCrop(event.viewPoints, event.viewWidth, event.viewHeight)
            }
            is ScannerEvent.CancelCrop -> {
                _state.update { it.copy(mode = ScannerMode.CAMERA, originalBitmap = null) }
            }
            is ScannerEvent.FilterChanged -> {
                applyFilterAndRotation(event.filter, _state.value.activeRotation)
            }
            is ScannerEvent.RotateLeft -> {
                val newRotation = (_state.value.activeRotation - 90f) % 360f
                applyFilterAndRotation(_state.value.activeFilter, newRotation)
            }
            is ScannerEvent.RotateRight -> {
                val newRotation = (_state.value.activeRotation + 90f) % 360f
                applyFilterAndRotation(_state.value.activeFilter, newRotation)
            }
            is ScannerEvent.Retake -> {
                clearBitmaps()
                _state.update { it.copy(mode = ScannerMode.CAMERA) }
            }
            is ScannerEvent.SaveAsJpg -> {
                saveDocument(saveAsPdf = false)
            }
            is ScannerEvent.SaveAsPdf -> {
                saveDocument(saveAsPdf = true)
            }
            is ScannerEvent.ShareClicked -> {
                // Handled directly in Activity using current state's saveSuccessUri
            }
            is ScannerEvent.ErrorDismissed -> {
                _state.update { it.copy(errorMessage = null) }
            }
            is ScannerEvent.NavigateBack -> {
                handleNavigationBack()
            }
            is ScannerEvent.SaveResultHandled -> {
                // Clear save result so it doesn't re-trigger on next state emission
                _state.update {
                    it.copy(
                        saveSuccessUri = null,
                        saveSuccessMimeType = null,
                        savedFilePath = null
                    )
                }
            }
            is ScannerEvent.MlKitScanSuccess -> {
                handleMlKitSuccess(event.jpegs, event.pdf)
            }
            is ScannerEvent.ScanFailed -> {
                _state.update { it.copy(isLoading = false, errorMessage = event.error) }
            }
        }
    }

    /**
     * Copies ML Kit temporary result URIs to our local Documents folder so they persist.
     */
    private fun handleMlKitSuccess(jpegs: List<Uri>, pdf: Uri?) {
        val context = getApplication<Application>()
        _state.update { it.copy(isLoading = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Prefer PDF if available, otherwise copy first JPEG
                if (pdf != null) {
                    val localFile = FileUtils.createTimestampedFile(context, ".pdf")
                    context.contentResolver.openInputStream(pdf)?.use { input ->
                        localFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    val localUri = FileUtils.getUriForFile(context, localFile)
                    withContext(Dispatchers.Main) {
                        _state.update {
                            it.copy(
                                isLoading = false,
                                saveSuccessUri = localUri,
                                saveSuccessMimeType = "application/pdf",
                                savedFilePath = localFile.absolutePath
                            )
                        }
                    }
                } else if (jpegs.isNotEmpty()) {
                    val localFile = FileUtils.createTimestampedFile(context, ".jpg")
                    context.contentResolver.openInputStream(jpegs.first())?.use { input ->
                        localFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    val localUri = FileUtils.getUriForFile(context, localFile)
                    withContext(Dispatchers.Main) {
                        _state.update {
                            it.copy(
                                isLoading = false,
                                saveSuccessUri = localUri,
                                saveSuccessMimeType = "image/jpeg",
                                savedFilePath = localFile.absolutePath
                            )
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _state.update {
                            it.copy(isLoading = false, errorMessage = "No scan results returned")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ScannerViewModel", "Failed to save ML Kit result: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to save scan: ${e.localizedMessage}"
                        )
                    }
                }
            }
        }
    }

    private fun performCrop(viewPoints: List<PointF>, viewWidth: Float, viewHeight: Float) {
        val original = _state.value.originalBitmap ?: return
        _state.update { it.copy(isLoading = true) }

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val mappedPoints = ScannerUtils.mapViewPointsToBitmapPoints(
                    viewPoints = viewPoints,
                    viewWidth = viewWidth,
                    viewHeight = viewHeight,
                    bitmapWidth = original.width.toFloat(),
                    bitmapHeight = original.height.toFloat()
                )

                val cropped = ScannerUtils.perspectiveWarp(original, mappedPoints)

                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            croppedBitmap = cropped,
                            previewBitmap = cropped.copy(cropped.config ?: Bitmap.Config.ARGB_8888, true),
                            activeFilter = ImageFilterType.ORIGINAL,
                            activeRotation = 0f,
                            mode = ScannerMode.PREVIEW,
                            isLoading = false
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("ScannerViewModel", "Cropping failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Perspective warp failed: ${e.localizedMessage}"
                        )
                    }
                }
            }
        }
    }

    private fun applyFilterAndRotation(filter: ImageFilterType, rotation: Float) {
        val cropped = _state.value.croppedBitmap ?: return
        _state.update { it.copy(isLoading = true) }

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val filtered = ImageFilter.applyFilter(cropped, filter)
                
                val finalBitmap = if (rotation == 0f) {
                    filtered
                } else {
                    val matrix = Matrix().apply { postRotate(rotation) }
                    val rotated = Bitmap.createBitmap(
                        filtered, 0, 0, filtered.width, filtered.height, matrix, true
                    )
                    if (rotated != filtered) {
                        filtered.recycle()
                    }
                    rotated
                }

                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            previewBitmap = finalBitmap,
                            activeFilter = filter,
                            activeRotation = rotation,
                            isLoading = false
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("ScannerViewModel", "Filtering failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to process image: ${e.localizedMessage}"
                        )
                    }
                }
            }
        }
    }

    private fun saveDocument(saveAsPdf: Boolean) {
        val preview = _state.value.previewBitmap ?: return
        _state.update { it.copy(isLoading = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val extension = if (saveAsPdf) ".pdf" else ".jpg"
                val file = FileUtils.createTimestampedFile(getApplication(), extension)

                if (saveAsPdf) {
                    PdfUtils.createPdf(listOf(preview), file)
                } else {
                    FileUtils.saveBitmapAsJpg(preview, file)
                }

                val uri = FileUtils.getUriForFile(getApplication(), file)
                val mime = if (saveAsPdf) "application/pdf" else "image/jpeg"

                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            saveSuccessUri = uri,
                            saveSuccessMimeType = mime,
                            savedFilePath = file.absolutePath
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("ScannerViewModel", "Saving file failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to save file: ${e.localizedMessage}"
                        )
                    }
                }
            }
        }
    }

    private fun handleNavigationBack() {
        val currentMode = _state.value.mode
        val nextMode = when (currentMode) {
            ScannerMode.PREVIEW -> ScannerMode.CROP
            ScannerMode.CROP -> ScannerMode.CAMERA
            ScannerMode.CAMERA -> ScannerMode.DASHBOARD
            ScannerMode.DASHBOARD -> null
        }
        
        if (nextMode != null) {
            _state.update { it.copy(mode = nextMode) }
        }
    }

    private fun clearBitmaps() {
        _state.value.originalBitmap?.recycle()
        _state.value.croppedBitmap?.recycle()
        _state.value.previewBitmap?.recycle()
        _state.update {
            it.copy(
                originalBitmap = null,
                croppedBitmap = null,
                previewBitmap = null,
                activeFilter = ImageFilterType.ORIGINAL,
                activeRotation = 0f,
                saveSuccessUri = null,
                saveSuccessMimeType = null,
                savedFilePath = null
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        clearBitmaps()
    }
}
