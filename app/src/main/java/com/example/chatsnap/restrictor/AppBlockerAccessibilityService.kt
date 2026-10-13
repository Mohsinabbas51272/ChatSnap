package com.example.chatsnap.restrictor

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class AppBlockerAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkgName = event.packageName?.toString() ?: return

        if (pkgName == packageName) {
            return
        }

        if (AppRestrictionManager.isPackageRestricted(this, pkgName)) {
            if (!AppRestrictionManager.isBlockScreenActive) {
                AppRestrictionManager.lastBlockedPackage = pkgName
                AppRestrictionManager.isBlockScreenActive = true

                val intent = Intent(this, FakeServerDownActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
            }
        } else {
            // Switched away to home or unblocked app
            AppRestrictionManager.lastBlockedPackage = null
            AppRestrictionManager.isBlockScreenActive = false
        }
    }

    override fun onInterrupt() {}
}
