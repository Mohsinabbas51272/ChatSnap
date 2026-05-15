package com.example.chatsnap

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.chatsnap.databinding.ActivityLoginBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class LoginActivity : BaseActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        // If user is already logged in, verify profile status
        if (auth.currentUser != null) {
            checkProfileCompletion(auth.currentUser!!.uid)
        }

        binding.loginButton.setOnClickListener {
            val input = binding.emailET.text.toString().trim()
            val password = binding.passwordET.text.toString().trim()

            if (input.isEmpty() || password.isEmpty()) {
                com.example.chatsnap.utils.ToastUtils.showToast(this, "Please enter email/phone and password")
                return@setOnClickListener
            }

            binding.loginButton.isEnabled = false

            if (android.util.Patterns.EMAIL_ADDRESS.matcher(input).matches()) {
                // Login with Email
                loginWithEmail(input, password)
            } else {
                // Login with Phone Number (finding email associated with phone)
                loginWithPhone(input, password)
            }
        }

        binding.forgotPasswordText.setOnClickListener {
            showForgotPasswordDialog()
        }

        binding.createAccountText.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }

        binding.tvPrivacy.setOnClickListener {
            val intent = Intent(this, StaticContentActivity::class.java)
            intent.putExtra("title", "Privacy Policy")
            intent.putExtra("content", "Your privacy is our priority. We do not share your data with third parties without your consent. All chats are encrypted.")
            startActivity(intent)
        }

        binding.tvTerms.setOnClickListener {
            val intent = Intent(this, StaticContentActivity::class.java)
            intent.putExtra("title", "Terms of Service")
            intent.putExtra("content", "By using ChatSnap, you agree to follow our community guidelines. Harassment and illegal activities are strictly prohibited.")
            startActivity(intent)
        }

        binding.tvHelp.setOnClickListener {
            // Open Help & Support for login issues
            startActivity(Intent(this, SupportRequestActivity::class.java))
        }

        // Entry Animations
        com.example.chatsnap.utils.AnimUtils.fadeInAndSlideUp(binding.brandContainer, 200)
        com.example.chatsnap.utils.AnimUtils.fadeInAndSlideUp(binding.loginCard, 400)
        com.example.chatsnap.utils.AnimUtils.fadeInAndSlideUp(binding.createAccountText, 600)
    }

    private fun showForgotPasswordDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Reset Password")
        val input = EditText(this)
        input.hint = "Enter your email"
        builder.setView(input)
        builder.setPositiveButton("Send") { _, _ ->
            val email = input.text.toString().trim()
            if (email.isNotEmpty()) {
                auth.sendPasswordResetEmail(email).addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Toast.makeText(this, "Reset link sent to your email", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Error: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    private fun showInfoDialog(title: String, content: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(content)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun loginWithEmail(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    checkProfileCompletion(auth.currentUser!!.uid)
                } else {
                    binding.loginButton.isEnabled = true
                    com.example.chatsnap.utils.ToastUtils.showToast(this, "Login Failed: ${task.exception?.message}")
                }
            }
    }

    private fun loginWithPhone(phone: String, password: String) {
        // Find user by phone in Firestore to get their email
        firestore.collection("users")
            .whereEqualTo("phone", phone)
            .get()
            .addOnSuccessListener { documents ->
                if (documents.isEmpty) {
                    binding.loginButton.isEnabled = true
                    com.example.chatsnap.utils.ToastUtils.showToast(this, "Phone number not found")
                } else {
                    val email = documents.documents[0].getString("email")
                    if (email != null) {
                        loginWithEmail(email, password)
                    } else {
                        binding.loginButton.isEnabled = true
                        com.example.chatsnap.utils.ToastUtils.showToast(this, "Internal error: No email for this phone")
                    }
                }
            }
            .addOnFailureListener {
                binding.loginButton.isEnabled = true
                com.example.chatsnap.utils.ToastUtils.showToast(this, "Error: ${it.message}")
            }
    }

    private fun checkProfileCompletion(userId: String) {
        firestore.collection("users").document(userId).get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val isBlocked = document.getBoolean("isBlocked") ?: false
                    if (isBlocked) {
                        auth.signOut()
                        com.example.chatsnap.utils.ToastUtils.showToast(this, "Your account has been suspended by the administrator.")
                        binding.loginButton.isEnabled = true
                        return@addOnSuccessListener
                    }

                    val isCompleted = document.getBoolean("profileCompleted") ?: false
                    if (isCompleted) {
                        startActivity(Intent(this, MainActivity::class.java))
                    } else {
                        startActivity(Intent(this, ProfileSetupActivity::class.java))
                    }
                    finish()
                } else {
                    startActivity(Intent(this, ProfileSetupActivity::class.java))
                    finish()
                }
            }
            .addOnFailureListener { e ->
                binding.loginButton.isEnabled = true
                com.example.chatsnap.utils.ToastUtils.showToast(this, "Error checking profile: ${e.message}")
            }
    }
}