package com.example.chatsnap.utils

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

object SessionManager {
    private const val PREF_NAME = "chatsnap_session_prefs"
    private const val KEY_PREFIX_SESSION_ID = "session_id_"

    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "unknown_device"
        } catch (e: Exception) {
            "unknown_device"
        }
    }

    fun getLocalSessionId(context: Context, uid: String): String? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PREFIX_SESSION_ID + uid, null)
    }

    fun saveLocalSessionId(context: Context, uid: String, sessionId: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PREFIX_SESSION_ID + uid, sessionId).apply()
    }

    fun clearLocalSessionId(context: Context, uid: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_PREFIX_SESSION_ID + uid).apply()
    }

    /**
     * Initializes a new session on login, signup, or account switch.
     * Uses deviceId + timestamp/UUID so a session belongs to a device.
     */
    fun startNewSession(context: Context, userId: String, callback: (Boolean) -> Unit = {}) {
        val deviceId = getDeviceId(context)
        val newSessionId = "${deviceId}_${System.currentTimeMillis()}"
        saveLocalSessionId(context, userId, newSessionId)

        val updates = mapOf(
            "sessionId" to newSessionId,
            "deviceId" to deviceId
        )

        FirebaseFirestore.getInstance().collection("users").document(userId)
            .set(updates, SetOptions.merge())
            .addOnCompleteListener { task ->
                callback(task.isSuccessful)
            }
    }
}

