package com.example.chatsnap

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatsnap.adapters.ConversationsAdapter
import com.example.chatsnap.models.Conversation
import com.example.chatsnap.models.Message
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.example.chatsnap.databinding.ActivityShareReceiverBinding

class ShareReceiverActivity : BaseActivity() {

    private lateinit var binding: ActivityShareReceiverBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private lateinit var adapter: ConversationsAdapter
    private val conversationMap = mutableMapOf<String, Conversation>()
    private var allConversations: List<Conversation> = emptyList()

    private var sharedText: String? = null
    private var sharedUri: Uri? = null
    private var sharedMimeType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShareReceiverBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val currentUid = auth.currentUser?.uid
        if (currentUid == null) {
            Toast.makeText(this, "Please login to ChatSnap first", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        parseIncomingIntent()
        setupUI()
        loadConversations(currentUid)
    }

    private fun parseIncomingIntent() {
        val action = intent?.action
        val type = intent?.type ?: ""
        sharedMimeType = type

        when (action) {
            Intent.ACTION_SEND -> {
                if (type.startsWith("text/")) {
                    sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    binding.tvPreview.text = sharedText ?: "Shared text"
                    binding.ivPreview.visibility = View.GONE

                    val url = extractUrl(sharedText)
                    if (url != null) {
                        binding.btnDownloadVideo.visibility = View.VISIBLE
                        binding.btnDownloadVideo.setOnClickListener {
                            val downloadIntent = Intent(this, DownloaderActivity::class.java).apply {
                                putExtra("download_url", url)
                            }
                            startActivity(downloadIntent)
                            finish()
                        }
                    }
                } else if (type.startsWith("image/")) {
                    sharedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                    binding.tvPreview.text = "Sharing an image"
                    if (sharedUri != null) {
                        binding.ivPreview.visibility = View.VISIBLE
                        binding.ivPreview.setImageURI(sharedUri)
                    }
                } else {
                    sharedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                    val fileName = sharedUri?.lastPathSegment ?: "file"
                    binding.tvPreview.text = "Sharing: $fileName"
                    binding.ivPreview.visibility = View.GONE
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                // For now just take the first item
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!uris.isNullOrEmpty()) {
                    sharedUri = uris[0]
                    binding.tvPreview.text = "Sharing ${uris.size} file(s)"
                    if (type.startsWith("image/")) {
                        binding.ivPreview.visibility = View.VISIBLE
                        binding.ivPreview.setImageURI(sharedUri)
                    }
                }
            }
        }
    }

    private fun extractUrl(text: String?): String? {
        if (text == null) return null
        val words = text.split("\\s+".toRegex())
        for (word in words) {
            val w = word.trim()
            if (w.startsWith("http://", ignoreCase = true) || w.startsWith("https://", ignoreCase = true)) {
                return w
            }
        }
        return null
    }

    private fun setupUI() {
        binding.btnClose.setOnClickListener { finish() }

        adapter = ConversationsAdapter(emptyList(), onClick = { conversation ->
            forwardToChat(conversation)
        })
        binding.rvConversations.layoutManager = LinearLayoutManager(this)
        binding.rvConversations.adapter = adapter

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterConversations(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun filterConversations(query: String) {
        if (query.isEmpty()) {
            adapter.updateData(allConversations)
        } else {
            val filtered = allConversations.filter {
                it.partnerName.contains(query, ignoreCase = true)
            }
            adapter.updateData(filtered)
        }
    }

    private fun loadConversations(uid: String) {
        binding.progressBar.visibility = View.VISIBLE

        // Load 1-on-1 messages
        db.collection("messages").whereEqualTo("senderId", uid)
            .addSnapshotListener { snapshot, e ->
                if (e == null) updateConversations(snapshot?.toObjects(Message::class.java) ?: emptyList(), uid)
            }
        db.collection("messages").whereEqualTo("receiverId", uid)
            .addSnapshotListener { snapshot, e ->
                if (e == null) updateConversations(snapshot?.toObjects(Message::class.java) ?: emptyList(), uid)
            }

        // Load groups
        db.collection("groups").whereArrayContains("memberIds", uid)
            .addSnapshotListener { snapshot, _ ->
                snapshot?.forEach { groupDoc ->
                    val groupId = groupDoc.id
                    val groupName = groupDoc.getString("name") ?: "Group"
                    val groupPhoto = groupDoc.getString("groupImageUrl")
                    val conv = Conversation(
                        partnerId = groupId,
                        partnerName = groupName,
                        partnerPhotoUrl = groupPhoto,
                        isGroup = true
                    )
                    conversationMap[groupId] = conv
                    refreshList()
                }
            }
    }

    private fun updateConversations(messages: List<Message>, currentUserId: String) {
        messages.forEach { msg ->
            val isGroup = msg.isGroup || !msg.conversationId.contains("_")
            val partnerId = if (isGroup) msg.conversationId else (if (msg.senderId == currentUserId) msg.receiverId else msg.senderId)
            if (partnerId.isEmpty()) return@forEach

            val msgTime = try { msg.timestamp.toString().toLong() } catch (_: Exception) { 0L }
            val existing = conversationMap[partnerId]

            if (existing == null || msgTime > existing.lastMessageTimestamp) {
                val newConv = Conversation(
                    partnerId = partnerId,
                    lastMessage = msg.content,
                    lastMessageTimestamp = msgTime,
                    lastMessageSenderId = msg.senderId,
                    lastMessageType = msg.type,
                    isGroup = isGroup
                )
                conversationMap[partnerId] = newConv
                if (isGroup) {
                    fetchGroupDetails(partnerId)
                } else {
                    fetchPartnerDetails(partnerId)
                }
            }
        }
        refreshList()
    }

    private fun fetchPartnerDetails(partnerId: String) {
        db.collection("users").document(partnerId).get().addOnSuccessListener { doc ->
            if (doc.exists()) {
                val name = doc.getString("name") ?: "User"
                val photo = doc.getString("profileImageUrl")
                val existing = conversationMap[partnerId] ?: return@addOnSuccessListener
                conversationMap[partnerId] = existing.copy(partnerName = name, partnerPhotoUrl = photo)
                refreshList()
            }
        }
    }

    private fun fetchGroupDetails(groupId: String) {
        db.collection("groups").document(groupId).get().addOnSuccessListener { doc ->
            if (doc.exists()) {
                val name = doc.getString("name") ?: "Group"
                val photo = doc.getString("groupImageUrl")
                val existing = conversationMap[groupId] ?: return@addOnSuccessListener
                conversationMap[groupId] = existing.copy(partnerName = name, partnerPhotoUrl = photo)
                refreshList()
            }
        }
    }

    private fun refreshList() {
        binding.progressBar.visibility = View.GONE
        allConversations = conversationMap.values
            .sortedByDescending { it.lastMessageTimestamp }
        adapter.updateData(allConversations)
    }

    private fun forwardToChat(conversation: Conversation) {
        val chatIntent = Intent(this, ChatActivity::class.java)

        if (conversation.isGroup) {
            chatIntent.putExtra("groupId", conversation.partnerId)
            chatIntent.putExtra("groupName", conversation.partnerName)
        } else {
            chatIntent.putExtra("receiverId", conversation.partnerId)
            chatIntent.putExtra("receiverName", conversation.partnerName)
        }

        // Pass shared content
        if (sharedText != null) {
            chatIntent.putExtra("shared_text", sharedText)
        }
        if (sharedUri != null) {
            chatIntent.putExtra("shared_uri", sharedUri.toString())
            chatIntent.putExtra("shared_mime", sharedMimeType ?: "application/octet-stream")
        }

        chatIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(chatIntent)
        finish()
    }
}
