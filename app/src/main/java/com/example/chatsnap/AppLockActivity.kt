package com.example.chatsnap

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.example.chatsnap.databinding.ActivityAppLockBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.Executor

class AppLockActivity : BaseActivity() {
    private lateinit var binding: ActivityAppLockBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val expectedCode = intent.getStringExtra("EXPECTED_CODE")
        
        if (expectedCode == null) {
            // This should not happen if the intent is formed correctly.
            // If it happens, we finish to avoid locking the user out of the app.
            finish()
            return
        }

        setupBiometric()

        binding.btnUnlock.setOnClickListener {
            val enteredCode = binding.etLockCode.text.toString().trim()
            if (enteredCode == expectedCode) {
                finish()
            } else {
                Toast.makeText(this, "Incorrect Code", Toast.LENGTH_SHORT).show()
                binding.etLockCode.text?.clear()
            }
        }

        binding.btnBiometric.setOnClickListener {
            biometricPrompt.authenticate(promptInfo)
        }

        // Auto-show biometric if available
        if (isBiometricAvailable()) {
            binding.btnBiometric.visibility = View.VISIBLE
            biometricPrompt.authenticate(promptInfo)
        } else {
            binding.btnBiometric.visibility = View.GONE
        }
    }

    private fun setupBiometric() {
        executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // Toast.makeText(applicationContext, "Auth error: $errString", Toast.LENGTH_SHORT).show()
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    finish()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(applicationContext, "Authentication failed", Toast.LENGTH_SHORT).show()
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("ChatSnap Locked")
            .setSubtitle("Authenticate to open ChatSnap")
            .setNegativeButtonText("Use Passcode")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
    }

    private fun isBiometricAvailable(): Boolean {
        val biometricManager = BiometricManager.from(this)
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }

    override fun onBackPressed() {
        // Prevent going back to skip lock
        finishAffinity()
    }
}
