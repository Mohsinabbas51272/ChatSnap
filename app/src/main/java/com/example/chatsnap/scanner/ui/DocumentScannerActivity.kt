package com.example.chatsnap.scanner.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.example.chatsnap.BaseActivity
import androidx.camera.core.ImageCapture
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatsnap.R
import com.example.chatsnap.databinding.ActivityDocumentScannerBinding
import com.example.chatsnap.scanner.repository.ScannerRepository
import com.example.chatsnap.scanner.utils.CameraManager
import com.example.chatsnap.scanner.utils.PermissionHelper
import com.example.chatsnap.scanner.utils.ScannerUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DocumentScannerActivity : BaseActivity() {

    private lateinit var binding: ActivityDocumentScannerBinding
    private val viewModel: ScannerViewModel by viewModels()
    private val repository = ScannerRepository()

    private var cameraManager: CameraManager? = null
    private var filterAdapter: FilterAdapter? = null

    private var isLaunchedFromChat = false
    private var pendingShare = false
    private var isCameraStarted = false  // Guard against repeated camera init

    // ML Kit Scanner Launcher
    private val mlKitScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                if (scanningResult != null) {
                    val pdfUri = scanningResult.pdf?.uri
                    val jpegUris = scanningResult.pages?.map { page -> page.imageUri } ?: emptyList()
                    viewModel.onEvent(ScannerEvent.MlKitScanSuccess(jpegUris, pdfUri))
                } else {
                    viewModel.onEvent(ScannerEvent.ScanFailed("No scan results returned from ML Kit"))
                }
            } catch (e: Exception) {
                viewModel.onEvent(ScannerEvent.ScanFailed("Failed to parse ML Kit results: ${e.localizedMessage}"))
            }
        } else {
            viewModel.onEvent(ScannerEvent.ScanFailed("Smart ML Scan cancelled"))
        }
    }

    // System Gallery Picker Launcher
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            loadBitmapFromGallery(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDocumentScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isLaunchedFromChat = intent.getBooleanExtra("launched_from_chat", false)

        setupToolbar()
        setupClickListeners()
        setupFiltersRecyclerView()
        observeViewModelState()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
    }

    private fun setupClickListeners() {
        // Dashboard state clicks
        binding.btnSmartScan.setOnClickListener {
            startMlKitSmartScan()
        }
        binding.btnManualScan.setOnClickListener {
            viewModel.onEvent(ScannerEvent.OpenCustomCamera)
        }

        // Camera state clicks
        binding.btnCancelCamera.setOnClickListener {
            viewModel.onEvent(ScannerEvent.NavigateBack)
        }
        binding.btnFlashToggle.setOnClickListener {
            cameraManager?.toggleFlash { flashMode ->
                val flashIcon = if (flashMode == ImageCapture.FLASH_MODE_ON) {
                    android.R.drawable.stat_sys_phone_call
                } else {
                    android.R.drawable.ic_lock_power_off
                }
                binding.btnFlashToggle.setImageResource(flashIcon)
            }
        }
        binding.btnGalleryPicker.setOnClickListener {
            pickImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnCapture.setOnClickListener {
            captureCameraPhoto()
        }

        // Crop state clicks
        binding.btnCancelCrop.setOnClickListener {
            viewModel.onEvent(ScannerEvent.CancelCrop)
        }
        binding.btnAutoCrop.setOnClickListener {
            triggerAutoCropDetection()
        }
        binding.btnWarpNext.setOnClickListener {
            val viewPoints = binding.cropOverlay.getCropPoints()
            val containerWidth = binding.cropOverlay.width.toFloat()
            val containerHeight = binding.cropOverlay.height.toFloat()
            viewModel.onEvent(ScannerEvent.CropCompleted(viewPoints, containerWidth, containerHeight))
        }

        // Preview state clicks (inside layoutPreviewInclude)
        val previewInclude = binding.layoutPreviewInclude
        previewInclude.btnRotateLeft.setOnClickListener {
            viewModel.onEvent(ScannerEvent.RotateLeft)
        }
        previewInclude.btnRotateRight.setOnClickListener {
            viewModel.onEvent(ScannerEvent.RotateRight)
        }
        previewInclude.btnRetake.setOnClickListener {
            viewModel.onEvent(ScannerEvent.Retake)
        }
        previewInclude.btnSaveJpg.setOnClickListener {
            pendingShare = false
            viewModel.onEvent(ScannerEvent.SaveAsJpg)
        }
        previewInclude.btnSavePdf.setOnClickListener {
            pendingShare = false
            viewModel.onEvent(ScannerEvent.SaveAsPdf)
        }
        previewInclude.btnShare.setOnClickListener {
            pendingShare = true
            viewModel.onEvent(ScannerEvent.SaveAsPdf) // Save first, then share on success
        }
    }

    private fun setupFiltersRecyclerView() {
        val filters = listOf(
            ImageFilterType.ORIGINAL,
            ImageFilterType.COLOR,
            ImageFilterType.GRAYSCALE,
            ImageFilterType.BLACK_WHITE
        )
        filterAdapter = FilterAdapter(filters) { selectedFilter ->
            viewModel.onEvent(ScannerEvent.FilterChanged(selectedFilter))
            filterAdapter?.selectFilter(selectedFilter)
        }
        binding.layoutPreviewInclude.rvFilters.apply {
            layoutManager = LinearLayoutManager(
                this@DocumentScannerActivity,
                LinearLayoutManager.HORIZONTAL,
                false
            )
            adapter = filterAdapter
        }
    }

    private fun observeViewModelState() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    updateUiMode(state.mode)
                    updateLoadingState(state.isLoading)

                    // Error presentation
                    state.errorMessage?.let { error ->
                        showErrorSnackbar(error)
                        viewModel.onEvent(ScannerEvent.ErrorDismissed)
                    }

                    // Crop screen layout configuration
                    if (state.mode == ScannerMode.CROP && state.originalBitmap != null) {
                        binding.ivCropBackground.setImageBitmap(state.originalBitmap)
                        
                        if (binding.cropOverlay.points.all { it.x == 0f && it.y == 0f }) {
                            binding.cropOverlay.viewTreeObserver.addOnGlobalLayoutListener(
                                object : ViewTreeObserver.OnGlobalLayoutListener {
                                    override fun onGlobalLayout() {
                                        binding.cropOverlay.viewTreeObserver.removeOnGlobalLayoutListener(this)
                                        triggerAutoCropDetection()
                                    }
                                }
                            )
                        }
                    }

                    // Preview screen image configuration
                    if (state.mode == ScannerMode.PREVIEW && state.previewBitmap != null) {
                        binding.layoutPreviewInclude.ivPreview.setImageBitmap(state.previewBitmap)
                        filterAdapter?.selectFilter(state.activeFilter)
                    }

                    // Save result - consume ONCE and clear
                    state.saveSuccessUri?.let { uri ->
                        handleSaveSuccess(uri, state.saveSuccessMimeType, state.savedFilePath)
                        viewModel.onEvent(ScannerEvent.SaveResultHandled) // Clear to prevent re-trigger
                    }
                }
            }
        }
    }

    private fun updateUiMode(mode: ScannerMode) {
        binding.layoutDashboard.visibility = if (mode == ScannerMode.DASHBOARD) View.VISIBLE else View.GONE
        binding.layoutCamera.visibility = if (mode == ScannerMode.CAMERA) View.VISIBLE else View.GONE
        binding.layoutCrop.visibility = if (mode == ScannerMode.CROP) View.VISIBLE else View.GONE
        binding.layoutPreviewInclude.root.visibility = if (mode == ScannerMode.PREVIEW) View.VISIBLE else View.GONE

        when (mode) {
            ScannerMode.DASHBOARD -> {
                binding.toolbar.title = "Document Scanner"
                binding.toolbar.setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel)
                // Reset camera flag when leaving camera
                isCameraStarted = false
            }
            ScannerMode.CAMERA -> {
                binding.toolbar.title = "Manual Capture"
                binding.toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert)
                // Only start camera ONCE per camera session
                if (!isCameraStarted) {
                    isCameraStarted = true
                    setupCameraManager()
                }
            }
            ScannerMode.CROP -> {
                binding.toolbar.title = "Adjust Boundaries"
                binding.toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert)
                isCameraStarted = false
            }
            ScannerMode.PREVIEW -> {
                binding.toolbar.title = "Enhance & Save"
                binding.toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert)
                isCameraStarted = false
            }
        }
    }

    private fun updateLoadingState(isLoading: Boolean) {
        // Future: show/hide progress overlay
    }

    private fun setupCameraManager() {
        if (cameraManager == null) {
            cameraManager = CameraManager(this, binding.viewFinder, this)
        }
        if (!PermissionHelper.hasCameraPermission(this)) {
            PermissionHelper.requestCameraPermission(this)
        } else {
            // Delay camera start until PreviewView is laid out
            binding.viewFinder.post {
                cameraManager?.startCamera()
            }
        }
    }

    private fun startMlKitSmartScan() {
        viewModel.onEvent(ScannerEvent.LaunchSmartScanner)
        repository.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                val request = IntentSenderRequest.Builder(intentSender).build()
                mlKitScannerLauncher.launch(request)
            }
            .addOnFailureListener { exception ->
                viewModel.onEvent(ScannerEvent.ScanFailed("Smart Scanner unavailable: ${exception.localizedMessage}"))
            }
    }

    private fun captureCameraPhoto() {
        viewModel.onEvent(ScannerEvent.LaunchSmartScanner) // loading state
        cameraManager?.capturePhoto(
            onSuccess = { bitmap ->
                viewModel.onEvent(ScannerEvent.ImageCaptured(bitmap))
            },
            onError = { exception ->
                viewModel.onEvent(ScannerEvent.ScanFailed("Camera capture failed: ${exception.localizedMessage}"))
            }
        )
    }

    private fun loadBitmapFromGallery(uri: Uri) {
        viewModel.onEvent(ScannerEvent.LaunchSmartScanner)
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
                if (bitmap != null) {
                    viewModel.onEvent(ScannerEvent.ImageSelectedFromGallery(bitmap))
                } else {
                    viewModel.onEvent(ScannerEvent.ScanFailed("Could not open gallery file"))
                }
            } catch (e: Exception) {
                viewModel.onEvent(ScannerEvent.ScanFailed("Failed to load gallery image: ${e.localizedMessage}"))
            }
        }
    }

    private fun triggerAutoCropDetection() {
        val original = viewModel.state.value.originalBitmap ?: return
        lifecycleScope.launch {
            val bitmapPoints = withContext(Dispatchers.Default) {
                ScannerUtils.detectDocumentCorners(original)
            }
            val viewWidth = binding.cropOverlay.width.toFloat()
            val viewHeight = binding.cropOverlay.height.toFloat()
            
            val viewPoints = ScannerUtils.mapBitmapPointsToViewPoints(
                bitmapPoints = bitmapPoints,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                bitmapWidth = original.width.toFloat(),
                bitmapHeight = original.height.toFloat()
            )
            binding.cropOverlay.setCropPoints(viewPoints)
        }
    }

    private fun handleSaveSuccess(uri: Uri, mimeType: String?, filePath: String?) {
        if (pendingShare) {
            pendingShare = false
            shareScannedFile(uri, mimeType ?: "application/pdf")
            return
        }

        if (isLaunchedFromChat) {
            // Return result to ChatActivity
            val resultIntent = Intent().apply {
                putExtra("scanned_file_uri", uri.toString())
                putExtra("scanned_file_type", if (mimeType == "application/pdf") "DOCUMENT" else "IMAGE")
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
            return
        }

        // Show a dialog with saved file path and options to Open, Share, or dismiss
        val displayPath = filePath ?: "Internal Storage/Documents"
        val fileType = if (mimeType == "application/pdf") "PDF" else "JPG"

        MaterialAlertDialogBuilder(this)
            .setTitle("✅ Document Saved!")
            .setMessage("Saved as $fileType:\n\n📂 $displayPath")
            .setPositiveButton("Share") { _, _ ->
                shareScannedFile(uri, mimeType ?: "application/pdf")
            }
            .setNeutralButton("Open") { _, _ ->
                openSavedFile(uri, mimeType ?: "*/*")
            }
            .setNegativeButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }

    private fun shareScannedFile(uri: Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Scanned Document"))
    }

    private fun openSavedFile(uri: Uri, mimeType: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No app available to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showErrorSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction("Dismiss") {}
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionHelper.CAMERA_PERMISSION_REQUEST_CODE) {
            if (PermissionHelper.hasCameraPermission(this)) {
                binding.viewFinder.post {
                    cameraManager?.startCamera()
                }
            } else {
                if (PermissionHelper.shouldShowRationale(this)) {
                    PermissionHelper.showPermissionRationaleDialog(this) {
                        PermissionHelper.requestCameraPermission(this)
                    }
                } else {
                    Snackbar.make(
                        binding.root,
                        "Camera permission is required to scan manually. Please enable it in Settings.",
                        Snackbar.LENGTH_INDEFINITE
                    ).setAction("Settings") {
                        PermissionHelper.openAppSettings(this)
                    }.show()
                }
            }
        }
    }

    override fun onBackPressed() {
        val currentMode = viewModel.state.value.mode
        if (currentMode == ScannerMode.DASHBOARD) {
            super.onBackPressed()
        } else {
            viewModel.onEvent(ScannerEvent.NavigateBack)
        }
    }
}
