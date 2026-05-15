package com.example.chatsnap

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.example.chatsnap.adapters.MediaGalleryAdapter
import com.example.chatsnap.databinding.ActivityMediaGalleryBinding
import com.example.chatsnap.models.Message
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

class MediaGalleryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMediaGalleryBinding
    private lateinit var db: FirebaseFirestore
    private lateinit var adapter: MediaGalleryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = FirebaseFirestore.getInstance()
        val chatId = intent.getStringExtra("chatId") ?: ""
        val partnerName = intent.getStringExtra("partnerName") ?: "Media"

        binding.toolbar.title = "Shared with $partnerName"
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        loadMedia(chatId)
    }

    private fun setupRecyclerView() {
        adapter = MediaGalleryAdapter(emptyList()) { message ->
            val intent = Intent(this, MediaViewerActivity::class.java)
            intent.putExtra("mediaUrl", message.content)
            intent.putExtra("mediaType", message.type)
            startActivity(intent)
        }
        binding.rvMediaGallery.layoutManager = GridLayoutManager(this, 3)
        binding.rvMediaGallery.adapter = adapter
    }

    private fun loadMedia(chatId: String) {
        if (chatId.isEmpty()) return

        db.collection("messages")
            .whereEqualTo("conversationId", chatId)
            .whereIn("type", listOf("IMAGE", "VIDEO", "SNAP"))
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    val mediaList = snapshot.toObjects(Message::class.java)
                    adapter.updateData(mediaList)
                }
            }
    }
}
