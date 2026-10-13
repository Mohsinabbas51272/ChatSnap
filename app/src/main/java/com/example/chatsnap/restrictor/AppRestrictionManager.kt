package com.example.chatsnap.restrictor

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

object AppRestrictionManager {

    private const val PREFS_NAME = "chatsnap_app_restrictor_prefs"
    private const val KEY_ENABLED = "restrictor_enabled"
    private const val KEY_RESTRICTED_PACKAGES = "restricted_packages"

    // In-memory flag to prevent repeated intent launches while fake dialog is open
    @Volatile
    var isBlockScreenActive: Boolean = false

    @Volatile
    var lastBlockedPackage: String? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isFeatureEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun setFeatureEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            startBlockerService(context)
        } else {
            stopBlockerService(context)
        }
    }

    fun getRestrictedPackages(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_RESTRICTED_PACKAGES, emptySet()) ?: emptySet()
    }

    fun isPackageRestricted(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (!isFeatureEnabled(context)) return false
        // Never restrict our own app
        if (packageName == context.packageName) return false
        return getRestrictedPackages(context).contains(packageName)
    }

    fun addRestrictedPackage(context: Context, packageName: String) {
        val current = getRestrictedPackages(context).toMutableSet()
        current.add(packageName)
        getPrefs(context).edit().putStringSet(KEY_RESTRICTED_PACKAGES, current).apply()
        if (isFeatureEnabled(context)) {
            startBlockerService(context)
        }
    }

    fun removeRestrictedPackage(context: Context, packageName: String) {
        val current = getRestrictedPackages(context).toMutableSet()
        current.remove(packageName)
        getPrefs(context).edit().putStringSet(KEY_RESTRICTED_PACKAGES, current).apply()
    }

    fun setRestricted(context: Context, packageName: String, restrict: Boolean) {
        if (restrict) {
            addRestrictedPackage(context, packageName)
        } else {
            removeRestrictedPackage(context, packageName)
        }
    }

    /**
     * Checks if Usage Access permission is granted
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if Draw Over Other Apps (Overlay) permission is granted
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * Check if Accessibility service is enabled
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        return try {
            val serviceName = "${context.packageName}/${AppBlockerAccessibilityService::class.java.canonicalName}"
            val accessibilityEnabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED, 0
            )
            if (accessibilityEnabled == 1) {
                val settingValue = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                settingValue?.split(":")?.any { it.equals(serviceName, ignoreCase = true) } == true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun openUsageStatsSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    fun openOverlaySettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
        }
    }

    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // fallback
        }
    }

    fun startBlockerService(context: Context) {
        if (!isFeatureEnabled(context)) return
        try {
            val intent = Intent(context, AppBlockerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopBlockerService(context: Context) {
        try {
            val intent = Intent(context, AppBlockerService::class.java)
            context.stopService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
