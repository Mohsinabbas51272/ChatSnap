package com.example.chatsnap

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.chatsnap.databinding.ActivityPrivacySettingsBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class PrivacySettingsActivity : BaseActivity() {
    private lateinit var binding: ActivityPrivacySettingsBinding
    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        setupToolbar()
        loadSettings()

        binding.switchActiveStatus.setOnCheckedChangeListener { _, isChecked ->
            updateSetting("showOnline", isChecked)
        }

        binding.switchReadReceipts.setOnCheckedChangeListener { _, isChecked ->
            updateSetting("readReceipts", isChecked)
        }

        binding.btnDeleteAccount.setOnClickListener {
            Toast.makeText(this, "This feature will be available soon", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun loadSettings() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    binding.switchActiveStatus.isChecked = doc.getBoolean("showOnline") ?: true
                    binding.switchReadReceipts.isChecked = doc.getBoolean("readReceipts") ?: true
                }
            }
    }

    private fun updateSetting(key: String, value: Any) {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid).update(key, value)
            .addOnFailureListener {
                Toast.makeText(this, "Failed to update setting", Toast.LENGTH_SHORT).show()
            }
    }
}
