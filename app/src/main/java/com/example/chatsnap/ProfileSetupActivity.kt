package com.example.chatsnap

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import coil.load
import com.example.chatsnap.databinding.ActivityProfileSetupBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import id.zelory.compressor.Compressor
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

class ProfileSetupActivity : BaseActivity() {
    private lateinit var binding: ActivityProfileSetupBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var storage: FirebaseStorage
    private var selectedImageUri: Uri? = null

    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            binding.ivProfilePic.setImageURI(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()
        storage = FirebaseStorage.getInstance()

        loadExistingProfile()

        val nameFromSignUp = intent.getStringExtra("name")
        if (nameFromSignUp != null) binding.etName.setText(nameFromSignUp)

        binding.fabAddPhoto.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        binding.btnSaveProfile.setOnClickListener {
            val name = binding.etName.text.toString().trim()
            if (name.isEmpty()) {
                binding.etName.error = "Please enter your name"
                return@setOnClickListener
            }

            handleProfileSave(name)
        }
    }

    private fun handleProfileSave(name: String) {
        val userId = auth.currentUser?.uid ?: return
        showLoading(true)

        lifecycleScope.launch {
            try {
                var imageUrl: String? = null
                
                if (selectedImageUri != null) {
                    imageUrl = uploadProfileImage(userId, selectedImageUri!!)
                }

                saveProfileToFirestore(userId, name, imageUrl)
            } catch (e: Exception) {
                showLoading(false)
                Toast.makeText(this@ProfileSetupActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun uploadProfileImage(userId: String, uri: Uri): String {
        val inputStream = contentResolver.openInputStream(uri)
        val bytes = inputStream?.readBytes() ?: throw Exception("Failed to read image")
        
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val out = java.io.ByteArrayOutputStream()
        // Resize to 300x300 and compress for small footprint in Firestore
        val resized = android.graphics.Bitmap.createScaledBitmap(bitmap, 300, 300, true)
        resized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, out)
        
        val base64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.DEFAULT)
        return "data:image/jpeg;base64,$base64"
    }

    private fun showLoading(isLoading: Boolean) {
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.btnSaveProfile.isEnabled = !isLoading
        binding.fabAddPhoto.isEnabled = !isLoading
        binding.etName.isEnabled = !isLoading
    }

    private fun saveProfileToFirestore(userId: String, name: String, imageUrl: String?) {
        val userUpdates = mutableMapOf<String, Any>(
            "name" to name,
            "profileCompleted" to true
        )
        imageUrl?.let { userUpdates["profileImageUrl"] = it }

        // Use set with merge instead of update to handle missing documents safely
        firestore.collection("users").document(userId)
            .set(userUpdates, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener {
                showLoading(false)
                Toast.makeText(this, "Profile Updated", Toast.LENGTH_SHORT).show()
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            .addOnFailureListener { e ->
                showLoading(false)
                Toast.makeText(this, "Firestore Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun loadExistingProfile() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid).get().addOnSuccessListener { doc ->
            if (doc.exists()) {
                val name = doc.getString("name")
                val imageUrl = doc.getString("profileImageUrl")
                
                binding.etName.setText(name)
                if (!imageUrl.isNullOrEmpty()) {
                    if (imageUrl.startsWith("data:image") || imageUrl.length > 1000) {
                        try {
                            val cleanBase64 = imageUrl.substringAfter(",")
                            val decodedString: ByteArray = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                            val decodedByte = android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                            binding.ivProfilePic.setImageBitmap(decodedByte)
                        } catch (e: Exception) {
                            binding.ivProfilePic.load(imageUrl)
                        }
                    } else {
                        binding.ivProfilePic.load(imageUrl) {
                            crossfade(true)
                            placeholder(R.drawable.ic_launcher_foreground)
                        }
                    }
                }
            }
        }
    }
}