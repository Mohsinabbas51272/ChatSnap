package com.example.chatsnap.scanner.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

class CameraManager(
    private val context: Context,
    private val previewView: PreviewView,
    private val lifecycleOwner: LifecycleOwner
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var flashMode: Int = ImageCapture.FLASH_MODE_OFF

    /**
     * Initializes CameraX and binds the viewfinder and capture use cases.
     */
    fun startCamera(onReady: () -> Unit = {}) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
                onReady()
            } catch (e: Exception) {
                Log.e("CameraManager", "Failed to start camera provider", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        // 1. Viewfinder preview use case
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(previewView.surfaceProvider)
        }

        // 2. High-quality image capture use case
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flashMode)
            .build()

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
        } catch (e: Exception) {
            Log.e("CameraManager", "Lifecycle use-case binding failed", e)
        }
    }

    /**
     * Toggles between flash modes and reports changes to update icons.
     */
    fun toggleFlash(onStatusChanged: (Int) -> Unit) {
        flashMode = if (flashMode == ImageCapture.FLASH_MODE_OFF) {
            ImageCapture.FLASH_MODE_ON
        } else {
            ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        onStatusChanged(flashMode)
    }

    fun getFlashMode(): Int = flashMode

    /**
     * Captures a high-quality frame to a temp file, adjusts the rotation, and loads it as a Bitmap.
     */
    fun capturePhoto(onSuccess: (Bitmap) -> Unit, onError: (ImageCaptureException) -> Unit) {
        val captureUseCase = imageCapture ?: return
        val cacheFile = File(context.cacheDir, "temp_scan_capture.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(cacheFile).build()

        captureUseCase.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    try {
                        val bitmap = loadCorrectlyOrientedBitmap(cacheFile)
                        onSuccess(bitmap)
                    } catch (e: Exception) {
                        onError(ImageCaptureException(ImageCapture.ERROR_UNKNOWN, "Failed to load captured image", e))
                    } finally {
                        if (cacheFile.exists()) {
                            cacheFile.delete()
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception)
                }
            }
        )
    }

    private fun loadCorrectlyOrientedBitmap(file: File): Bitmap {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        val exif = ExifInterface(file.absolutePath)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }
        
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
