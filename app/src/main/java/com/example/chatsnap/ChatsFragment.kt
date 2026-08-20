package com.example.chatsnap

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
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
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(10)
            .addSnapshotListener { snapshot, _ ->
                if (_binding == null) return@addSnapshotListener
                val latest = snapshot?.documents?.firstOrNull { it.getBoolean("isActive") == true }
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
        val context = context ?: return
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_secret_lock, null)

        val tvTitle = view.findViewById<android.widget.TextView>(R.id.tvSecretDialogTitle)
        val tvSubtitle = view.findViewById<android.widget.TextView>(R.id.tvSecretDialogSubtitle)
        val tilPassword = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilSecretPassword)
        val etPassword = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSecretPassword)
        val btnCancel = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSecretCancel)
        val btnAction = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSecretAction)

        tvTitle.text = "Setup Secret Lock"
        tvSubtitle.text = "Set a password (4+ characters) to protect your hidden chats."
        tilPassword.hint = "New Password (4+ chars)"
        btnAction.text = "Save"

        val dialog = android.app.Dialog(context)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        val dialogWidth = (resources.displayMetrics.widthPixels * 0.88).toInt()
        dialog.window?.setLayout(dialogWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)

        var isSuccess = false

        val savePasswordAction = {
            val pwd = etPassword.text?.toString()?.trim() ?: ""
            if (pwd.length >= 4) {
                val uid = auth.currentUser?.uid
                if (uid != null) {
                    btnAction.isEnabled = false
                    firestore.collection("users").document(uid).update("secretPassword", pwd)
                        .addOnSuccessListener {
                            currentUser = currentUser?.copy(secretPassword = pwd)
                            isSuccess = true
                            viewMode = "SECRET"
                            refreshAdapter()
                            dialog.dismiss()
                            android.widget.Toast.makeText(context, "Secret Lock set successfully!", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        .addOnFailureListener { e ->
                            btnAction.isEnabled = true
                            android.widget.Toast.makeText(context, "Failed to save: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                }
            } else {
                tilPassword.error = "Password must be at least 4 characters"
            }
        }

        btnAction.setOnClickListener {
            savePasswordAction()
        }

        etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                savePasswordAction()
                true
            } else {
                false
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            if (!isSuccess && viewMode != "SECRET") {
                binding.toggleGroup.check(R.id.btnTabChats)
            }
        }

        dialog.show()
        etPassword.requestFocus()
    }

    private fun showSecretLoginDialog() {
        val context = context ?: return
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_secret_lock, null)

        val tvTitle = view.findViewById<android.widget.TextView>(R.id.tvSecretDialogTitle)
        val tvSubtitle = view.findViewById<android.widget.TextView>(R.id.tvSecretDialogSubtitle)
        val tilPassword = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilSecretPassword)
        val etPassword = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSecretPassword)
        val btnCancel = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSecretCancel)
        val btnAction = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSecretAction)

        tvTitle.text = "Unlock Secret Chats"
        tvSubtitle.text = "Enter your secret password to view hidden chats"
        tilPassword.hint = "Password"
        btnAction.text = "Unlock"

        val dialog = android.app.Dialog(context)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        val dialogWidth = (resources.displayMetrics.widthPixels * 0.88).toInt()
        dialog.window?.setLayout(dialogWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)

        var isSuccess = false

        val unlockAction = {
            val pwd = etPassword.text?.toString() ?: ""
            if (pwd == currentUser?.secretPassword) {
                isSuccess = true
                viewMode = "SECRET"
                refreshAdapter()
                dialog.dismiss()
            } else {
                tilPassword.error = "Incorrect password"
                android.widget.Toast.makeText(context, "Incorrect password", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        btnAction.setOnClickListener {
            unlockAction()
        }

        etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                unlockAction()
                true
            } else {
                false
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            if (!isSuccess && viewMode != "SECRET") {
                binding.toggleGroup.check(R.id.btnTabChats)
            }
        }

        dialog.show()
        etPassword.requestFocus()
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

    private var senderMessages = listOf<Message>()
    private var receiverMessages = listOf<Message>()

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
            showConversationOptionsDialog(conversation)
        })
        binding.rvConversations.layoutManager = LinearLayoutManager(requireContext())
        binding.rvConversations.adapter = adapter

        val swipeHandler = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(0, androidx.recyclerview.widget.ItemTouchHelper.LEFT or androidx.recyclerview.widget.ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                target: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val list = (binding.rvConversations.adapter as? ConversationsAdapter)?.getConversationsList() ?: return
                if (position in list.indices) {
                    val conversation = list[position]
                    if (direction == androidx.recyclerview.widget.ItemTouchHelper.RIGHT) {
                        togglePinChat(conversation.partnerId)
                    } else {
                        if (viewMode == "SECRET") {
                            showUnhideDialog(conversation)
                        } else {
                            if (conversation.isGroup) {
                                android.widget.Toast.makeText(context, "Groups cannot be made secret", android.widget.Toast.LENGTH_SHORT).show()
                                refreshAdapter()
                            } else {
                                hideChatDirectly(conversation.partnerId)
                            }
                        }
                    }
                }
            }
        }
        androidx.recyclerview.widget.ItemTouchHelper(swipeHandler).attachToRecyclerView(binding.rvConversations)

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
                if (e == null && snapshot != null) {
                    senderMessages = snapshot.toObjects(Message::class.java)
                    rebuild1On1Conversations(uid)
                }
            }
        firestore.collection("messages").whereEqualTo("receiverId", uid)
            .addSnapshotListener { snapshot, e -> 
                if (e == null && snapshot != null) {
                    receiverMessages = snapshot.toObjects(Message::class.java)
                    rebuild1On1Conversations(uid)
                }
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

    private fun rebuild1On1Conversations(currentUserId: String) {
        val all1On1 = senderMessages + receiverMessages
        val activePartnerIds = all1On1.map { 
            if (it.senderId == currentUserId) it.receiverId else it.senderId 
        }.toSet()

        // Remove partner IDs from conversationMap if their messages were deleted
        val toRemove = conversationMap.filter { (id, conv) -> !conv.isGroup && !activePartnerIds.contains(id) }.keys.toList()
        toRemove.forEach { conversationMap.remove(it) }

        updateConversations(all1On1, currentUserId)
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
        val pinnedIds = getPinnedChats()
        val list = conversationMap.values.toList()
            .map { it.copy(isPinned = pinnedIds.contains(it.partnerId)) }
            .filter { conv ->
                when (viewMode) {
                    "CHATS" -> !secretPartnerIds.contains(conv.partnerId) && !conv.isGroup
                    "GROUPS" -> conv.isGroup
                    "SECRET" -> secretPartnerIds.contains(conv.partnerId)
                    else -> true
                }
            }
            .sortedWith(compareByDescending<Conversation> { it.isPinned }
                .thenByDescending { it.lastMessageTimestamp })
            
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
        val pinnedIds = getPinnedChats()
        val list = conversationMap.values.toList()
            .map { it.copy(isPinned = pinnedIds.contains(it.partnerId)) }
            .filter { conv ->
                val matchesMode = when (viewMode) {
                    "CHATS" -> !secretPartnerIds.contains(conv.partnerId) && !conv.isGroup
                    "GROUPS" -> conv.isGroup
                    "SECRET" -> secretPartnerIds.contains(conv.partnerId)
                    else -> true
                }
                matchesMode && (conv.partnerName.contains(query, true) || conv.lastMessage.contains(query, true))
            }
            .sortedWith(compareByDescending<Conversation> { it.isPinned }
                .thenByDescending { it.lastMessageTimestamp })
        adapter.updateData(list)
    }

    private fun showConversationOptionsDialog(conversation: Conversation) {
        val context = context ?: return
        val isSecret = secretPartnerIds.contains(conversation.partnerId)
        val isPinned = getPinnedChats().contains(conversation.partnerId)

        val options = mutableListOf<String>()
        options.add("👁️ Quick Preview")
        options.add(if (isPinned) "📌 Unpin Chat" else "📌 Pin Chat to Top")
        if (!conversation.isGroup) {
            options.add(if (isSecret) "🔓 Unhide Chat" else "🔒 Hide Chat (Secret Mode)")
        }
        options.add("🗑️ Delete Chat")

        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(conversation.partnerName)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "👁️ Quick Preview" -> showQuickPreviewDialog(conversation)
                    "📌 Unpin Chat", "📌 Pin Chat to Top" -> togglePinChat(conversation.partnerId)
                    "🔓 Unhide Chat" -> showUnhideDialog(conversation)
                    "🔒 Hide Chat (Secret Mode)" -> hideChatDirectly(conversation.partnerId)
                    "🗑️ Delete Chat" -> confirmDeleteChat(conversation)
                }
            }
            .show()
    }

    private fun confirmDeleteChat(conversation: Conversation) {
        val context = context ?: return
        com.example.chatsnap.utils.UIUtils.showCustomDialog(
            context = context,
            title = "Delete Chat?",
            message = "Are you sure you want to delete conversation with \"${conversation.partnerName}\"? All messages will be permanently deleted.",
            positiveText = "Delete",
            negativeText = "Cancel",
            onPositive = {
                deleteChatFromFirestore(conversation)
            }
        )
    }

    private fun deleteChatFromFirestore(conversation: Conversation) {
        val myUid = auth.currentUser?.uid ?: return
        val partnerId = conversation.partnerId
        val context = context ?: return

        val collectionName = if (conversation.isGroup) "groupMessages" else "messages"
        val query = if (conversation.isGroup) {
            firestore.collection(collectionName).whereEqualTo("conversationId", partnerId)
        } else {
            val convId = if (myUid < partnerId) "${myUid}_${partnerId}" else "${partnerId}_${myUid}"
            firestore.collection(collectionName).whereEqualTo("conversationId", convId)
        }

        android.widget.Toast.makeText(context, "Deleting chat...", android.widget.Toast.LENGTH_SHORT).show()

        query.get().addOnSuccessListener { snapshot ->
            val docs = snapshot.documents
            if (docs.isEmpty()) {
                conversationMap.remove(partnerId)
                refreshAdapter()
                android.widget.Toast.makeText(context, "Chat deleted", android.widget.Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }

            val chunks = docs.chunked(500)
            var completed = 0
            for (chunk in chunks) {
                val batch = firestore.batch()
                for (doc in chunk) {
                    batch.delete(doc.reference)
                }
                batch.commit().addOnSuccessListener {
                    completed++
                    if (completed == chunks.size) {
                        firestore.collection("secretConversations").document("${myUid}_${partnerId}").delete()
                        conversationMap.remove(partnerId)
                        refreshAdapter()
                        android.widget.Toast.makeText(context, "Chat deleted", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }.addOnFailureListener { e ->
                    android.widget.Toast.makeText(context, "Failed to delete: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.addOnFailureListener { e ->
            android.widget.Toast.makeText(context, "Failed to query chat: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun showUnhideDialog(conversation: Conversation) {
        val uid = auth.currentUser?.uid ?: return
        val context = context ?: return
        com.example.chatsnap.utils.UIUtils.showCustomDialog(
            context = context,
            title = "Unhide Chat",
            message = "Move \"${conversation.partnerName}\" back to Friends tab?",
            positiveText = "Unhide",
            negativeText = "Cancel",
            onPositive = {
                firestore.collection("secretConversations")
                    .document("${uid}_${conversation.partnerId}")
                    .delete()
                    .addOnSuccessListener {
                        android.widget.Toast.makeText(context, "Chat unhidden", android.widget.Toast.LENGTH_SHORT).show()
                        refreshAdapter()
                    }
            },
            onNegative = {
                refreshAdapter()
            }
        )
    }

    private fun getPinnedChats(): Set<String> {
        val prefs = requireContext().getSharedPreferences("pinned_chats_prefs", android.content.Context.MODE_PRIVATE)
        return prefs.getStringSet("pinned_partner_ids", emptySet()) ?: emptySet()
    }

    private fun togglePinChat(partnerId: String) {
        val prefs = requireContext().getSharedPreferences("pinned_chats_prefs", android.content.Context.MODE_PRIVATE)
        val current = prefs.getStringSet("pinned_partner_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        val isCurrentlyPinned = current.contains(partnerId)
        val pinned = if (isCurrentlyPinned) {
            current.remove(partnerId)
            false
        } else {
            current.add(partnerId)
            true
        }
        prefs.edit().putStringSet("pinned_partner_ids", current).apply()
        val msg = if (pinned) "Chat pinned to top" else "Chat unpinned"
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        refreshAdapter()
    }

    private fun hideChatDirectly(partnerId: String) {
        val uid = auth.currentUser?.uid ?: return
        val secretData = hashMapOf(
            "userId" to uid,
            "partnerId" to partnerId,
            "timestamp" to com.google.firebase.Timestamp.now()
        )
        firestore.collection("secretConversations").document("${uid}_${partnerId}").set(secretData)
            .addOnSuccessListener {
                android.widget.Toast.makeText(context, "Chat hidden in Secret tab", android.widget.Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                android.widget.Toast.makeText(context, "Failed to hide chat", android.widget.Toast.LENGTH_SHORT).show()
                refreshAdapter()
            }
    }

    private fun showQuickPreviewDialog(conversation: Conversation) {
        val context = requireContext()
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_quick_preview, null)
        
        val tvName = view.findViewById<android.widget.TextView>(R.id.tvPreviewName)
        val ivProfile = view.findViewById<android.widget.ImageView>(R.id.ivPreviewProfile)
        val layoutMessages = view.findViewById<android.widget.LinearLayout>(R.id.layoutPreviewMessages)
        val progress = view.findViewById<android.widget.ProgressBar>(R.id.previewProgress)
        
        tvName.text = conversation.partnerName
        if (conversation.isPartnerAdmin) {
            ivProfile.setImageResource(R.drawable.ic_app_logo)
        } else {
            val photo = conversation.partnerPhotoUrl
            if (!photo.isNullOrEmpty()) {
                if (photo.startsWith("data:image") || photo.length > 1000) {
                    try {
                        val cleanBase64 = photo.substringAfter(",")
                        val decodedString: ByteArray = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                        val decodedByte = android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                        ivProfile.setImageBitmap(decodedByte)
                    } catch (e: Exception) {
                        ivProfile.setImageResource(R.drawable.ic_launcher_foreground)
                    }
                } else {
                    ivProfile.load(photo) {
                        placeholder(R.drawable.ic_launcher_foreground)
                    }
                }
            } else {
                ivProfile.setImageResource(R.drawable.ic_launcher_foreground)
            }
        }
        
        // Haptic Feedback
        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        
        val dialog = android.app.Dialog(context)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        
        val myUid = auth.currentUser?.uid ?: return
        val partnerId = conversation.partnerId
        
        if (conversation.isGroup) {
            firestore.collection("groupMessages")
                .whereEqualTo("conversationId", partnerId)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(5)
                .get()
                .addOnSuccessListener { snapshot ->
                    progress.visibility = View.GONE
                    val messages = snapshot.toObjects(Message::class.java).reversed()
                    if (messages.isEmpty()) {
                        val emptyTv = android.widget.TextView(context).apply {
                            text = "No messages yet"
                            setTextColor(context.getColor(android.R.color.darker_gray))
                            setPadding(16, 16, 16, 16)
                            gravity = android.view.Gravity.CENTER
                        }
                        layoutMessages.addView(emptyTv)
                    } else {
                        messages.forEach { msg ->
                            val msgView = LayoutInflater.from(context).inflate(R.layout.item_preview_message, layoutMessages, false)
                            val tvText = msgView.findViewById<android.widget.TextView>(R.id.tvPreviewText)
                            val tvSender = msgView.findViewById<android.widget.TextView>(R.id.tvPreviewSender)
                            
                            tvText.text = msg.content
                            tvSender.visibility = View.VISIBLE
                            tvSender.text = if (msg.senderId == myUid) "You" else conversation.partnerName
                            layoutMessages.addView(msgView)
                        }
                    }
                }
                .addOnFailureListener {
                    progress.visibility = View.GONE
                }
        } else {
            val conversationId = if (myUid < partnerId) "${myUid}_${partnerId}" else "${partnerId}_${myUid}"
            firestore.collection("messages")
                .whereEqualTo("conversationId", conversationId)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(5)
                .get()
                .addOnSuccessListener { snapshot ->
                    progress.visibility = View.GONE
                    val messages = snapshot.toObjects(Message::class.java).reversed()
                    if (messages.isEmpty()) {
                        val emptyTv = android.widget.TextView(context).apply {
                            text = "No messages yet"
                            setTextColor(context.getColor(android.R.color.darker_gray))
                            setPadding(16, 16, 16, 16)
                            gravity = android.view.Gravity.CENTER
                        }
                        layoutMessages.addView(emptyTv)
                    } else {
                        messages.forEach { msg ->
                            val msgView = LayoutInflater.from(context).inflate(R.layout.item_preview_message, layoutMessages, false)
                            val tvText = msgView.findViewById<android.widget.TextView>(R.id.tvPreviewText)
                            tvText.text = msg.content
                            val isMe = msg.senderId == myUid
                            if (isMe) {
                                tvText.setBackgroundResource(R.drawable.bg_bubble_circle)
                                tvText.setTextColor(context.getColor(android.R.color.white))
                                val params = tvText.layoutParams as android.widget.LinearLayout.LayoutParams
                                params.gravity = android.view.Gravity.END
                                tvText.layoutParams = params
                            } else {
                                tvText.setBackgroundResource(R.drawable.bg_bubble_received)
                                val tvReceivedColor = android.util.TypedValue()
                                context.theme.resolveAttribute(android.R.attr.textColorPrimary, tvReceivedColor, true)
                                tvText.setTextColor(tvReceivedColor.data)
                                val params = tvText.layoutParams as android.widget.LinearLayout.LayoutParams
                                params.gravity = android.view.Gravity.START
                                tvText.layoutParams = params
                            }
                            layoutMessages.addView(msgView)
                        }
                    }
                }
                .addOnFailureListener {
                    progress.visibility = View.GONE
                }
        }
        
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
