package com.example.chatsnap

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatsnap.adapters.SelectFriendsAdapter
import com.example.chatsnap.databinding.ActivityForwardBinding
import com.example.chatsnap.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class ForwardActivity : BaseActivity() {

    private lateinit var binding: ActivityForwardBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    
    private var allFriends: List<User> = emptyList()
    private val selectedFriends = mutableSetOf<User>()

    // Message details to forward
    private var msgContent: String = ""
    private var msgType: String = "TEXT"
    private var msgMediaUrl: String? = null
    private var msgLatitude: Double? = null
    private var msgLongitude: Double? = null
    private var msgFileName: String? = null
    private var msgIsSnap: Boolean = false
    private var msgPollQuestion: String = ""
    private var msgPollOptions: List<String> = emptyList()
    private var msgEffect: String = "NONE"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityForwardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Parse message data to forward
        intent?.let {
            msgContent = it.getStringExtra("msg_content") ?: ""
            msgType = it.getStringExtra("msg_type") ?: "TEXT"
            msgMediaUrl = it.getStringExtra("msg_media_url")
            if (it.hasExtra("msg_latitude")) {
                msgLatitude = it.getDoubleExtra("msg_latitude", 0.0)
            }
            if (it.hasExtra("msg_longitude")) {
                msgLongitude = it.getDoubleExtra("msg_longitude", 0.0)
            }
            msgFileName = it.getStringExtra("msg_file_name")
            msgIsSnap = it.getBooleanExtra("msg_is_snap", false)
            msgPollQuestion = it.getStringExtra("msg_poll_question") ?: ""
            msgPollOptions = it.getStringArrayListExtra("msg_poll_options") ?: emptyList()
            msgEffect = it.getStringExtra("msg_effect") ?: "NONE"
        }

        setupUI()
        loadFriends()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }

        // Setup preview text
        val preview = when (msgType) {
            "IMAGE" -> "🖼️ Photo message"
            "VIDEO" -> "🎥 Video message"
            "AUDIO" -> "🎵 Voice message"
            "LOCATION" -> "📍 Location"
            "DOCUMENT" -> "📄 File: $msgFileName"
            "SNAP" -> "⚡ Snap"
            "POLL" -> "📊 Poll: $msgPollQuestion"
            else -> msgContent
        }
        binding.tvMsgPreview.text = "Forwarding: $preview"

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterFriends(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnForward.setOnClickListener {
            forwardMessageToSelected()
        }
    }

    private fun loadFriends() {
        val currentUid = auth.currentUser?.uid ?: return
        db.collection("users").document(currentUid).get().addOnSuccessListener { doc ->
            @Suppress("UNCHECKED_CAST")
            val friendIds = doc.get("friends") as? List<String> ?: emptyList()
            if (friendIds.isEmpty()) {
                Toast.makeText(this, "No friends list found to forward to.", Toast.LENGTH_SHORT).show()
                binding.btnForward.isEnabled = false
                return@addOnSuccessListener
            }

            db.collection("users").whereIn("uid", friendIds).get().addOnSuccessListener { snapshot ->
                allFriends = snapshot.toObjects(User::class.java)
                updateFriendsAdapter(allFriends)
            }
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Failed to load friends: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateFriendsAdapter(friendsList: List<User>) {
        binding.rvFriends.layoutManager = LinearLayoutManager(this)
        binding.rvFriends.adapter = SelectFriendsAdapter(friendsList) { friend, isSelected ->
            if (isSelected) {
                selectedFriends.add(friend)
            } else {
                selectedFriends.remove(friend)
            }
            updateForwardButton()
        }
    }

    private fun filterFriends(query: String) {
        val filtered = if (query.isEmpty()) {
            allFriends
        } else {
            allFriends.filter { it.name.contains(query, ignoreCase = true) }
        }
        updateFriendsAdapter(filtered)
    }

    private fun updateForwardButton() {
        val count = selectedFriends.size
        binding.btnForward.text = "Forward to $count friend" + (if (count == 1) "" else "s")
        binding.btnForward.isEnabled = count > 0
    }

    private fun forwardMessageToSelected() {
        val currentUid = auth.currentUser?.uid ?: return
        if (selectedFriends.isEmpty()) return

        binding.btnForward.isEnabled = false
        binding.btnForward.text = "Sending..."

        var completed = 0
        val total = selectedFriends.size

        for (friend in selectedFriends) {
            val chatId = generateChatId(currentUid, friend.uid)
            val messageId = db.collection("messages").document().id

            val messageData = hashMapOf(
                "messageId" to messageId,
                "senderId" to currentUid,
                "receiverId" to friend.uid,
                "conversationId" to chatId,
                "content" to msgContent,
                "type" to msgType,
                "mediaUrl" to msgMediaUrl,
                "latitude" to msgLatitude,
                "longitude" to msgLongitude,
                "fileName" to msgFileName,
                "timestamp" to System.currentTimeMillis(),
                "status" to "SENT",
                "viewed" to false,
                "isGroup" to false,
                "isDeleted" to false,
                "isSnap" to (msgIsSnap || msgType == "SNAP"),
                "pollQuestion" to msgPollQuestion,
                "pollOptions" to msgPollOptions,
                "pollVotes" to hashMapOf<String, Int>(),
                "effect" to msgEffect
            )

            db.collection("messages").document(messageId).set(messageData)
                .addOnCompleteListener {
                    completed++
                    if (completed == total) {
                        Toast.makeText(this, "Message forwarded successfully!", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
        }
    }

    private fun generateChatId(id1: String, id2: String): String {
        return if (id1 < id2) "${id1}_${id2}" else "${id2}_${id1}"
    }
}
