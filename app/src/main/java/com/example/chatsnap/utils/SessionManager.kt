package com.example.chatsnap.utils

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID

object SessionManager {
    private const val PREF_NAME = "chatsnap_session_prefs"
    private const val KEY_SESSION_ID = "current_session_id"

    fun getLocalSessionId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SESSION_ID, null)
    }

    fun saveLocalSessionId(context: Context, sessionId: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SESSION_ID, sessionId).apply()
    }

    fun clearLocalSessionId(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_SESSION_ID).apply()
    }

    /**
     * Initializes a new session on login or signup.
     */
    fun startNewSession(context: Context, userId: String, callback: (Boolean) -> Unit = {}) {
        val newSessionId = UUID.randomUUID().toString()
        saveLocalSessionId(context, newSessionId)
        
        FirebaseFirestore.getInstance().collection("users").document(userId)
            .update("sessionId", newSessionId)
            .addOnSuccessListener {
                callback(true)
            }
            .addOnFailureListener {
                // If field doesn't exist yet, merge
                FirebaseFirestore.getInstance().collection("users").document(userId)
                    .set(mapOf("sessionId" to newSessionId), com.google.firebase.firestore.SetOptions.merge())
                    .addOnCompleteListener { task ->
                        callback(task.isSuccessful)
                    }
            }
    }
}
