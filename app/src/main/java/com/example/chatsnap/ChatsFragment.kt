package com.example.chatsnap

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatsnap.adapters.ConversationsAdapter
import com.example.chatsnap.databinding.FragmentChatsBinding
import com.example.chatsnap.models.Conversation
import com.example.chatsnap.models.Message
import com.example.chatsnap.models.User
import com.example.chatsnap.utils.SearchableFragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

class ChatsFragment : Fragment(), SearchableFragment {
    private var _binding: FragmentChatsBinding? = null
    private val binding get() = _binding!!
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var adapter: ConversationsAdapter
    private val conversationMap = mutableMapOf<String, Conversation>()
    private val secretPartnerIds = mutableSetOf<String>()
    private var viewMode = "CHATS"
    private var currentUser: User? = null
    private var dismissedBroadcastId: String? = null

    companion object {
        private val userCache = mutableMapOf<String, Triple<String, String?, Boolean>>() // partnerId -> Triple(name, photo, isAdmin)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        setupRecyclerView()
        setupTabs()
        loadCurrentUser()
        loadSecretConversations()
        loadConversations()
        loadGroups()
        loadBroadcasts()
        setupBroadcastDismiss()
    }

    private fun loadGroups() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("groups")
            .whereArrayContains("memberIds", uid)
            .addSnapshotListener { snapshot, _ ->
                snapshot?.forEach { doc ->
                    val group = doc.toObject(com.example.chatsnap.models.Group::class.java)
                    val conv = Conversation(
                        partnerId = group.id,
                        partnerName = group.name,
                        partnerPhotoUrl = group.groupImageUrl,
                        lastMessage = group.lastMessage,
                        lastMessageTimestamp = group.lastMessageTimestamp,
                        isGroup = true
                    )
                    conversationMap[group.id] = conv
                }
                refreshAdapter()
            }
    }

    // ═══════════════════════════════════════════
    //  ADMIN BROADCASTS (Separate from chats)
    // ═══════════════════════════════════════════
    private fun loadBroadcasts() {
        firestore.collection("broadcasts")
            .whereEqualTo("isActive", true)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snapshot, _ ->
                if (_binding == null) return@addSnapshotListener
                val latest = snapshot?.documents?.firstOrNull()
                if (latest != null) {
                    val broadcastId = latest.getString("broadcastId") ?: latest.id
                    val content = latest.getString("content") ?: ""
                    if (content.isNotEmpty() && broadcastId != dismissedBroadcastId) {
                        binding.tvBroadcastMessage.text = content
                        binding.cardBroadcast.visibility = android.view.View.VISIBLE
                        binding.cardBroadcast.tag = broadcastId
                    } else {
                        binding.cardBroadcast.visibility = android.view.View.GONE
                    }
                } else {
                    binding.cardBroadcast.visibility = android.view.View.GONE
                }
            }
    }

    private fun setupBroadcastDismiss() {
        binding.btnDismissBroadcast.setOnClickListener {
            dismissedBroadcastId = binding.cardBroadcast.tag as? String
            binding.cardBroadcast.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    if (_binding != null) {
                        binding.cardBroadcast.visibility = android.view.View.GONE
                        binding.cardBroadcast.alpha = 1f
                    }
                }
                .start()
        }
    }

    private fun loadCurrentUser() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid).addSnapshotListener { doc, _ ->
            if (doc != null && doc.exists()) {
                currentUser = doc.toObject(User::class.java)
            }
        }
    }

    private fun setupTabs() {
        binding.toggleGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnTabChats -> {
                        viewMode = "CHATS"
                        refreshAdapter()
                    }
                    R.id.btnTabGroups -> {
                        viewMode = "GROUPS"
                        refreshAdapter()
                    }
                    R.id.btnTabSecret -> {
                        handleSecretTabClick()
                    }
                }
            }
        }
    }

    private fun handleSecretTabClick() {
        if (currentUser?.secretPassword == null) {
            showSecretSetupDialog()
        } else {
            showSecretLoginDialog()
        }
    }

    private fun showSecretSetupDialog() {
        val input = android.widget.EditText(requireContext())
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        input.hint = "Enter 4+ characters"
        
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Setup Secret Lock")
            .setMessage("Set a password to protect your hidden chats.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val pwd = input.text.toString()
                if (pwd.length >= 4) {
                    val uid = auth.currentUser?.uid ?: return@setPositiveButton
                    firestore.collection("users").document(uid).update("secretPassword", pwd)
                        .addOnSuccessListener {
                            viewMode = "SECRET"
                            refreshAdapter()
                        }
                } else {
                    android.widget.Toast.makeText(context, "Password too short", android.widget.Toast.LENGTH_SHORT).show()
                    binding.toggleGroup.check(R.id.btnTabChats)
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                binding.toggleGroup.check(R.id.btnTabChats)
            }
            .show()
    }

    private fun showSecretLoginDialog() {
        val input = android.widget.EditText(requireContext())
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        input.hint = "Password"
        
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Unlock Secret Chats")
            .setView(input)
            .setPositiveButton("Unlock") { _, _ ->
                if (input.text.toString() == currentUser?.secretPassword) {
                    viewMode = "SECRET"
                    refreshAdapter()
                } else {
                    android.widget.Toast.makeText(context, "Incorrect password", android.widget.Toast.LENGTH_SHORT).show()
                    binding.toggleGroup.check(R.id.btnTabChats)
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                binding.toggleGroup.check(R.id.btnTabChats)
            }
            .show()
    }

    private fun loadSecretConversations() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("secretConversations")
            .whereEqualTo("userId", uid)
            .addSnapshotListener { snapshot, _ ->
                secretPartnerIds.clear()
                snapshot?.forEach { doc ->
                    val partnerId = doc.getString("partnerId")
                    if (partnerId != null) secretPartnerIds.add(partnerId)
                }
                refreshAdapter()
            }
    }

    private fun setupRecyclerView() {
        adapter = ConversationsAdapter(emptyList(), onClick = { conversation ->
            val intent = Intent(requireContext(), ChatActivity::class.java)
            if (conversation.isGroup) {
                intent.putExtra("groupId", conversation.partnerId)
                intent.putExtra("groupName", conversation.partnerName)
            } else {
                intent.putExtra("receiverId", conversation.partnerId)
                intent.putExtra("receiverName", conversation.partnerName)
            }
            startActivity(intent)
        }, onLongClick = { conversation ->
            if (viewMode == "SECRET") {
                showUnhideDialog(conversation)
            }
        })
        binding.rvConversations.layoutManager = LinearLayoutManager(requireContext())
        binding.rvConversations.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener {
            loadConversations()
            loadGroups()
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun loadConversations() {
        val uid = auth.currentUser?.uid ?: return
        binding.progressBar.visibility = View.VISIBLE

        firestore.collection("messages").whereEqualTo("senderId", uid)
            .addSnapshotListener { snapshot, e -> 
                if (e == null) updateConversations(snapshot?.toObjects(Message::class.java) ?: emptyList(), uid) 
            }
        firestore.collection("messages").whereEqualTo("receiverId", uid)
            .addSnapshotListener { snapshot, e -> 
                if (e == null) updateConversations(snapshot?.toObjects(Message::class.java) ?: emptyList(), uid) 
            }
        
        firestore.collection("groups")
            .whereArrayContains("memberIds", uid)
            .addSnapshotListener { snapshot, _ ->
                snapshot?.forEach { groupDoc ->
                    val groupId = groupDoc.id
                    firestore.collection("groupMessages")
                        .whereEqualTo("conversationId", groupId)
                        .orderBy("timestamp", Query.Direction.DESCENDING)
                        .limit(1)
                        .addSnapshotListener { msgSnapshot, _ ->
                            val msg = msgSnapshot?.toObjects(Message::class.java)?.firstOrNull()
                            if (msg != null) {
                                updateConversations(listOf(msg), uid)
                            }
                        }
                }
            }
    }

    private fun updateConversations(messages: List<Message>, currentUserId: String) {
        messages.forEach { msg ->
            val actuallyGroup = msg.isGroup || !msg.conversationId.contains("_")
            val partnerId = if (actuallyGroup) msg.conversationId else (if (msg.senderId == currentUserId) msg.receiverId else msg.senderId)
            
            if (partnerId.isEmpty()) return@forEach

            val msgTime = getTimestampLong(msg.timestamp)
            val existing = conversationMap[partnerId]
            
            if (existing == null || msgTime > existing.lastMessageTimestamp) {
                val newConv = Conversation(
                    partnerId = partnerId,
                    lastMessage = msg.content,
                    lastMessageTimestamp = msgTime,
                    lastMessageSenderId = msg.senderId,
                    lastMessageType = msg.type,
                    lastMessageViewed = msg.viewed,
                    unreadCount = existing?.unreadCount ?: 0,
                    isGroup = actuallyGroup
                )
                conversationMap[partnerId] = newConv
                if (actuallyGroup) {
                    fetchGroupDetails(partnerId)
                } else {
                    fetchPartnerDetails(partnerId)
                }
                fetchUnreadCount(partnerId, currentUserId, actuallyGroup)
                if (!actuallyGroup) fetchStreak(partnerId, currentUserId)
            }
        }
        refreshAdapter()
    }

    private fun fetchGroupDetails(groupId: String) {
        firestore.collection("groups").document(groupId).get().addOnSuccessListener { doc ->
            if (_binding != null && doc.exists()) {
                val name = doc.getString("name") ?: "Group"
                val photo = doc.getString("groupImageUrl")
                val existing = conversationMap[groupId]
                if (existing != null) {
                    conversationMap[groupId] = existing.copy(partnerName = name, partnerPhotoUrl = photo)
                    refreshAdapter()
                }
            }
        }
    }

    private fun fetchUnreadCount(partnerId: String, currentUserId: String, isGroup: Boolean) {
        val query = if (isGroup) {
            firestore.collection("groupMessages")
                .whereEqualTo("conversationId", partnerId)
                .whereNotEqualTo("senderId", currentUserId)
                .whereEqualTo("viewed", false) 
        } else {
            val conversationId = if (currentUserId < partnerId) "${currentUserId}_${partnerId}" else "${partnerId}_${currentUserId}"
            firestore.collection("messages")
                .whereEqualTo("conversationId", conversationId)
                .whereEqualTo("receiverId", currentUserId)
                .whereEqualTo("viewed", false)
        }

        query.addSnapshotListener { snapshot, _ ->
            val count = snapshot?.size() ?: 0
            val existing = conversationMap[partnerId]
            if (existing != null) {
                conversationMap[partnerId] = existing.copy(unreadCount = count)
                refreshAdapter()
            }
        }
    }

    private fun getTimestampLong(timestamp: Any?): Long {
        return when (timestamp) {
            is com.google.firebase.Timestamp -> timestamp.toDate().time
            is Long -> timestamp
            is Map<*, *> -> (timestamp["seconds"] as? Long ?: 0L) * 1000
            else -> System.currentTimeMillis()
        }
    }

    private fun fetchStreak(partnerId: String, uid: String) {
        firestore.collection("users").document(uid).collection("streaks").document(partnerId)
            .addSnapshotListener { doc, _ ->
                if (doc != null && doc.exists()) {
                    var count = doc.getLong("count")?.toInt() ?: 0
                    val lastSentMe = doc.getLong("lastSentByMe") ?: 0L
                    val lastSentPartner = doc.getLong("lastSentByPartner") ?: 0L
                    val now = System.currentTimeMillis()
                    val twentyFourHours = 24 * 60 * 60 * 1000L
                    
                    if (now - lastSentMe > twentyFourHours || now - lastSentPartner > twentyFourHours) {
                        count = 0
                    }
                    
                    val hoursElapsed = (now - Math.max(lastSentMe, lastSentPartner)) / (1000 * 60 * 60)
                    val isExpiring = count > 0 && hoursElapsed >= 20
                    
                    val existing = conversationMap[partnerId]
                    if (existing != null && (existing.streakCount != count || existing.isExpiringSoon != isExpiring)) {
                        conversationMap[partnerId] = existing.copy(streakCount = count, isExpiringSoon = isExpiring)
                        refreshAdapter()
                    }
                }
            }
    }

    private fun fetchPartnerDetails(partnerId: String) {
        userCache[partnerId]?.let { (name, photo, isAdmin) ->
            val existing = conversationMap[partnerId]
            if (existing != null && (existing.partnerName != name || existing.partnerPhotoUrl != photo || existing.isPartnerAdmin != isAdmin)) {
                conversationMap[partnerId] = existing.copy(partnerName = name, partnerPhotoUrl = photo, isPartnerAdmin = isAdmin)
                refreshAdapter()
            }
            return
        }

        firestore.collection("users").document(partnerId).get()
            .addOnSuccessListener { doc ->
                if (_binding != null && doc.exists()) {
                    val name = doc.getString("name") ?: "Unknown"
                    val photo = doc.getString("profileImageUrl")
                    val isAdmin = doc.getBoolean("isAdmin") ?: false
                    userCache[partnerId] = Triple(name, photo, isAdmin)
                    
                    val existing = conversationMap[partnerId]
                    if (existing != null) {
                        conversationMap[partnerId] = existing.copy(
                            partnerName = name,
                            partnerPhotoUrl = photo,
                            isPartnerAdmin = isAdmin
                        )
                        refreshAdapter()
                    }
                }
            }
    }

    private fun refreshAdapter() {
        if (_binding == null) return
        val list = conversationMap.values.toList()
            .filter { conv ->
                when (viewMode) {
                    "CHATS" -> !secretPartnerIds.contains(conv.partnerId) && !conv.isGroup
                    "GROUPS" -> conv.isGroup
                    "SECRET" -> secretPartnerIds.contains(conv.partnerId)
                    else -> true
                }
            }
            .sortedByDescending { it.lastMessageTimestamp }
            
        adapter.updateData(list)
        binding.progressBar.visibility = View.GONE
        binding.tvNoChats.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.tvNoChats.text = when(viewMode) {
            "SECRET" -> "No Secret Chats.\nEnable secret mode in a chat to hide it."
            "GROUPS" -> "No Groups found."
            else -> "No conversations yet.\nStart chatting with your friends!"
        }
    }

    override fun onSearch(query: String) {
        if (_binding == null) return
        val list = conversationMap.values.toList()
            .filter { conv ->
                val matchesMode = when (viewMode) {
                    "CHATS" -> !secretPartnerIds.contains(conv.partnerId) && !conv.isGroup
                    "GROUPS" -> conv.isGroup
                    "SECRET" -> secretPartnerIds.contains(conv.partnerId)
                    else -> true
                }
                matchesMode && (conv.partnerName.contains(query, true) || conv.lastMessage.contains(query, true))
            }
            .sortedByDescending { it.lastMessageTimestamp }
        adapter.updateData(list)
    }

    private fun showUnhideDialog(conversation: Conversation) {
        val uid = auth.currentUser?.uid ?: return
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Unhide Chat")
            .setMessage("Move \"${conversation.partnerName}\" back to Friends tab?")
            .setPositiveButton("Unhide") { _, _ ->
                firestore.collection("secretConversations")
                    .document("${uid}_${conversation.partnerId}")
                    .delete()
                    .addOnSuccessListener {
                        android.widget.Toast.makeText(context, "Chat unhidden", android.widget.Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
