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

class MediaGalleryActivity : BaseActivity() {
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
        val isGroup = intent.getBooleanExtra("isGroup", false)

        binding.toolbar.title = "Shared with $partnerName"
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        loadMedia(chatId, isGroup)
    }

    private fun setupRecyclerView() {
        adapter = MediaGalleryAdapter(emptyList()) { message ->
            val intent = Intent(this, MediaViewerActivity::class.java)
            val url = if (message.mediaUrl.isNullOrEmpty()) message.content else message.mediaUrl
            intent.putExtra("mediaUrl", url)
            intent.putExtra("mediaType", message.type)
            startActivity(intent)
        }
        binding.rvMediaGallery.layoutManager = GridLayoutManager(this, 3)
        binding.rvMediaGallery.adapter = adapter
    }

    private fun loadMedia(chatId: String, isGroup: Boolean) {
        if (chatId.isEmpty()) return

        val collectionName = if (isGroup) "groupMessages" else "messages"
        db.collection(collectionName)
            .whereEqualTo("conversationId", chatId)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    val allMsgs = snapshot.toObjects(Message::class.java)
                    val mediaTypes = setOf("IMAGE", "VIDEO", "SNAP", "DOCUMENT")
                    val filtered = allMsgs.filter { it.type in mediaTypes && !it.isDeleted }
                        .sortedByDescending { getTimestampLong(it.timestamp) }
                    adapter.updateData(filtered)
                }
            }
    }

    private fun getTimestampLong(timestamp: Any?): Long {
        return when (timestamp) {
            is com.google.firebase.Timestamp -> timestamp.toDate().time
            is Long -> timestamp
            is Map<*, *> -> (timestamp["seconds"] as? Long ?: 0L) * 1000
            else -> 0L
        }
    }
}
