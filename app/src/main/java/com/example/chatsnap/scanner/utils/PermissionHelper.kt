package com.example.chatsnap.scanner.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object PermissionHelper {

    const val CAMERA_PERMISSION_REQUEST_CODE = 1001

    /**
     * Checks if camera permission is already granted.
     */
    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Standard request for camera permissions.
     */
    fun requestCameraPermission(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST_CODE
        )
    }

    /**
     * Checks if we should explain why camera permission is needed.
     */
    fun shouldShowRationale(activity: Activity): Boolean {
        return ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.CAMERA
        )
    }

    /**
     * Displays a Material 3 dialog justifying camera requirements before requesting.
     */
    fun showPermissionRationaleDialog(activity: Activity, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Camera Access Required")
            .setMessage("ChatSnap needs camera permission to capture document pages for scan and crop.")
            .setPositiveButton("Grant Access") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Launches the system settings for this app, in case permission was permanently denied.
     */
    fun openAppSettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
        }
        activity.startActivity(intent)
    }
}
