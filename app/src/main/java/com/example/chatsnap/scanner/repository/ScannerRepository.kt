package com.example.chatsnap.scanner.repository

import android.app.Activity
import android.content.IntentSender
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning

class ScannerRepository {

    // Configure ML Kit options: JPG/PDF output, Full ML features, gallery import.
    private val options = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(true)
        .setPageLimit(10)
        .setResultFormats(
            GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
            GmsDocumentScannerOptions.RESULT_FORMAT_PDF
        )
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .build()

    /**
     * Obtains the IntentSender task from Google Play Services ML Kit.
     * Starts the built-in automated scanner activity flow.
     */
    fun getStartScanIntent(activity: Activity): Task<IntentSender> {
        val client = GmsDocumentScanning.getClient(options)
        return client.getStartScanIntent(activity)
    }
}
