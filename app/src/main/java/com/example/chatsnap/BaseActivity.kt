package com.example.chatsnap

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.chatsnap.utils.ThemeManager

abstract class BaseActivity : AppCompatActivity() {
    private var maintenanceListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var sessionListener: com.google.firebase.firestore.ListenerRegistration? = null

    companion object {
        @Volatile
        private var isSignOutPending = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        checkMaintenanceModeCommon()
    }

    override fun finish() {
        super.finish()
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    override fun onResume() {
        super.onResume()
        updateUserPresence(true)
        checkSessionCommon()
    }

    override fun onPause() {
        super.onPause()
        updateUserPresence(false)
        sessionListener?.remove()
    }

    override fun onDestroy() {
        super.onDestroy()
        maintenanceListener?.remove()
        sessionListener?.remove()
    }

    protected fun animateView(view: android.view.View) {
        view.alpha = 0f
        view.translationY = 50f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(500)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun updateUserPresence(isOnline: Boolean) {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        val updates = hashMapOf<String, Any>(
            "online" to isOnline,
            "lastSeen" to System.currentTimeMillis()
        )
        com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .update(updates)
    }

    private fun checkSessionCommon() {
        val currentClassName = this::class.java.simpleName
        val bypassList = setOf("LoginActivity", "SignUpActivity", "SplashActivity", "LandingActivity", "AdminActivity")
        if (currentClassName in bypassList) return

        val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
        val currentUid = auth.currentUser?.uid ?: return

        // 1. If local session ID is empty/null, initialize it
        val localSessionId = com.example.chatsnap.utils.SessionManager.getLocalSessionId(this)
        if (localSessionId.isNullOrEmpty()) {
            val newSessionId = java.util.UUID.randomUUID().toString()
            com.example.chatsnap.utils.SessionManager.saveLocalSessionId(this, newSessionId)
            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("users").document(currentUid)
                .update("sessionId", newSessionId)
        }

        // 2. Listen for session ID changes in Firestore
        sessionListener?.remove()
        sessionListener = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("users").document(currentUid)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                
                val firestoreSessionId = snapshot.getString("sessionId")
                val currentLocalSessionId = com.example.chatsnap.utils.SessionManager.getLocalSessionId(this)
                
                if (firestoreSessionId != null && currentLocalSessionId != null && firestoreSessionId != currentLocalSessionId) {
                    if (!isSignOutPending) {
                        isSignOutPending = true
                        auth.signOut()
                        com.example.chatsnap.utils.SessionManager.clearLocalSessionId(this)
                        
                        android.widget.Toast.makeText(
                            this,
                            "Your account has been logged in on another device.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        
                        val intent = android.content.Intent(this, LoginActivity::class.java).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        startActivity(intent)
                        finishAffinity()
                        isSignOutPending = false
                    }
                }
            }
    }

    private fun checkMaintenanceModeCommon() {
        val currentClassName = this::class.java.simpleName
        val bypassList = setOf("LoginActivity", "SignUpActivity", "SplashActivity", "LandingActivity", "AdminActivity")
        if (currentClassName in bypassList) return

        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        val auth = com.google.firebase.auth.FirebaseAuth.getInstance()

        maintenanceListener = db.collection("config").document("admin")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                
                val maintenanceMode = snapshot.getBoolean("maintenanceMode") ?: false
                val adminUid = snapshot.getString("adminUid")
                val currentUid = auth.currentUser?.uid

                if (maintenanceMode && currentUid != null && currentUid != adminUid) {
                    db.collection("users").document(currentUid).get().addOnSuccessListener { userDoc ->
                        val isAdmin = userDoc.getBoolean("isAdmin") ?: false
                        if (!isAdmin) {
                            showMaintenanceDialog()
                        }
                    }
                }
            }
    }

    private fun showMaintenanceDialog() {
        if (isFinishing || isDestroyed) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Under Maintenance")
            .setMessage("ChatSnap is currently undergoing scheduled maintenance. Please try again later.")
            .setCancelable(false)
            .setPositiveButton("Close App") { _, _ ->
                finishAffinity()
            }
            .show()
    }
}
