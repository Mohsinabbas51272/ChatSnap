package com.example.chatsnap

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.chatsnap.databinding.ActivitySignupBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class SignUpActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignupBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        binding.signupButton.setOnClickListener {
            val name = binding.nameET.text.toString().trim()
            val phone = binding.phoneET.text.toString().trim()
            val email = binding.emailET.text.toString().trim()
            val password = binding.passwordET.text.toString().trim()

            if (name.isEmpty() || phone.isEmpty() || email.isEmpty() || password.isEmpty()) {
                com.example.chatsnap.utils.ToastUtils.showToast(this, "Please fill all fields")
                return@setOnClickListener
            }

            auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        val userId = auth.currentUser?.uid
                        val userMap = hashMapOf(
                            "name" to name,
                            "phone" to phone,
                            "email" to email,
                            "uid" to userId,
                            "profileCompleted" to false,
                            "friends" to arrayListOf<String>()
                        )

                        userId?.let {
                            firestore.collection("users").document(it).set(userMap)
                                .addOnSuccessListener {
                                    com.example.chatsnap.utils.ToastUtils.showToast(this, "Account Created Successfully")
                                    // Navigate to Profile Setup after successful signup
                                    val intent = Intent(this, ProfileSetupActivity::class.java)
                                    intent.putExtra("name", name)
                                    startActivity(intent)
                                    finish()
                                }
                                .addOnFailureListener { e ->
                                    com.example.chatsnap.utils.ToastUtils.showToast(this, "Failed to save user data: ${e.message}")
                                }
                        }
                    } else {
                        com.example.chatsnap.utils.ToastUtils.showToast(this, "Authentication Failed: ${task.exception?.message}")
                    }
                }
        }

        binding.loginText.setOnClickListener {
            finish()
        }
    }
}