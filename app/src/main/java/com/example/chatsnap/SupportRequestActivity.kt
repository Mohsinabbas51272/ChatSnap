package com.example.chatsnap

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.chatsnap.databinding.ActivitySupportRequestBinding
import com.example.chatsnap.models.SupportRequest
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class SupportRequestActivity : BaseActivity() {
    private lateinit var binding: ActivitySupportRequestBinding
    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySupportRequestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        firestore = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        binding.toolbar.setNavigationOnClickListener { finish() }

        // Hide "View Replies" if not logged in
        if (auth.currentUser == null) {
            binding.btnViewReplies.visibility = android.view.View.GONE
        }

        binding.btnSubmit.setOnClickListener {
            submitRequest()
        }

        binding.btnViewReplies.setOnClickListener {
            startActivity(Intent(this, SupportRepliesActivity::class.java))
        }
    }

    private fun submitRequest() {
        val title = binding.etTitle.text.toString().trim()
        val message = binding.etMessage.text.toString().trim()
        val contact = binding.etContact.text.toString().trim()
        val user = auth.currentUser

        if (title.isEmpty() || message.isEmpty()) {
            Toast.makeText(this, "Please fill in title and message", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnSubmit.isEnabled = false
        val requestId = firestore.collection("supportRequests").document().id
        val request = SupportRequest(
            id = requestId,
            uid = user?.uid ?: "GUEST",
            userName = user?.displayName ?: "Guest User",
            title = title,
            message = message,
            contact = contact,
            status = "open"
        )

        firestore.collection("supportRequests").document(requestId).set(request)
            .addOnSuccessListener {
                Toast.makeText(this, "Support request submitted! We will contact you via $contact", Toast.LENGTH_LONG).show()
                finish()
            }
            .addOnFailureListener {
                binding.btnSubmit.isEnabled = true
                Toast.makeText(this, "Failed to submit: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }
}
