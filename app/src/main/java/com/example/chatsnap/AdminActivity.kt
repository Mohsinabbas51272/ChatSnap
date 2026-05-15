package com.example.chatsnap

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatsnap.adapters.SupportAdapter
import com.example.chatsnap.adapters.UserAdapter
import com.example.chatsnap.adapters.StoryAdapter
import com.example.chatsnap.adapters.WithdrawalAdapter
import com.example.chatsnap.databinding.ActivityAdminBinding
import com.example.chatsnap.models.User
import com.example.chatsnap.models.SupportRequest
import com.example.chatsnap.models.Withdrawal
import com.example.chatsnap.models.Story
import com.example.chatsnap.utils.UIUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.FieldValue

class AdminActivity : BaseActivity() {
    private lateinit var binding: ActivityAdminBinding
    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    
    private lateinit var usersAdapter: UserAdapter
    private lateinit var supportAdapter: SupportAdapter
    private lateinit var storyAdapter: StoryAdapter
    private lateinit var withdrawalAdapter: WithdrawalAdapter
    private var allUsers: List<User> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        firestore = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupTabs()
        setupRecyclers()
        
        loadUsers()
        loadSupportRequests()
        loadWithdrawals()
        loadStories()
        loadDashboardStats()
        loadConfig()

        binding.btnBroadcast.setOnClickListener { showBroadcastDialog() }
        binding.btnSetAnnouncement.setOnClickListener { showAnnouncementDialog() }
        binding.btnSaveConfig.setOnClickListener { saveConfig() }
        
        setupSearch()
        setupAdminLock()
    }

    private fun setupAdminLock() {
        binding.btnUnlockAdmin.setOnClickListener {
            val entered = binding.etLockPassword.text.toString()
            if (entered.isEmpty()) return@setOnClickListener

            firestore.collection("config").document("admin").get()
                .addOnSuccessListener { doc ->
                    val correctPassword = doc.getString("password") ?: "admin123"
                    if (entered == correctPassword) {
                        binding.layoutAdminLock.visibility = android.view.View.GONE
                        Toast.makeText(this, "Welcome, Admin", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Wrong Password!", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Error checking password", Toast.LENGTH_SHORT).show()
                }
        }

        binding.btnLockCancel.setOnClickListener { finish() }
    }

    private fun setupTabs() {
        val tabLayouts = listOf(
            binding.layoutDashboardTab,
            binding.layoutUsersTab,
            binding.layoutFinanceTab,
            binding.layoutSupportTab,
            binding.layoutStoriesTab,
            binding.layoutReportsTab,
            binding.layoutConfigTab
        )
        binding.adminTabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                val pos = tab?.position ?: 0
                tabLayouts.forEachIndexed { i, v -> v.visibility = if (i == pos) android.view.View.VISIBLE else android.view.View.GONE }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
    }

    private fun setupRecyclers() {
        usersAdapter = UserAdapter(emptyList(), emptyMap(), onAddClick = {}, onChatClick = { showUserOptions(it) })
        binding.rvAdminUsers.layoutManager = LinearLayoutManager(this)
        binding.rvAdminUsers.adapter = usersAdapter

        supportAdapter = SupportAdapter(emptyList(), isAdmin = true, onItemClick = { showSupportReplyDialog(it) })
        binding.rvAdminSupport.layoutManager = LinearLayoutManager(this)
        binding.rvAdminSupport.adapter = supportAdapter

        withdrawalAdapter = WithdrawalAdapter(emptyList()) { showFulfillDialog(it) }
        binding.rvAdminWithdrawals.layoutManager = LinearLayoutManager(this)
        binding.rvAdminWithdrawals.adapter = withdrawalAdapter

        storyAdapter = StoryAdapter(emptyList()) { confirmDeleteStory(it) }
        binding.rvAdminStories.layoutManager = LinearLayoutManager(this)
        binding.rvAdminStories.adapter = storyAdapter

        binding.rvAdminReports.layoutManager = LinearLayoutManager(this)
        loadReports()
    }

    // ═══════════════════════════════════════════
    //  DASHBOARD STATS
    // ═══════════════════════════════════════════
    private fun loadDashboardStats() {
        firestore.collection("users").addSnapshotListener { snap, _ ->
            if (snap != null) {
                binding.tvStatTotalUsers.text = snap.size().toString()
                val onlineCount = snap.documents.count { it.getBoolean("online") == true }
                binding.tvStatActiveToday.text = onlineCount.toString()
                // Total coins
                var totalCoins = 0L
                snap.documents.forEach { /* we'll calculate from wallets separately */ }
            }
        }
        firestore.collection("messages").addSnapshotListener { snap, _ ->
            binding.tvStatTotalMessages.text = (snap?.size() ?: 0).toString()
        }
        firestore.collection("withdrawals").whereEqualTo("status", "PENDING").addSnapshotListener { snap, _ ->
            binding.tvStatPendingWithdrawals.text = (snap?.size() ?: 0).toString()
        }
        firestore.collection("stories").addSnapshotListener { snap, _ ->
            binding.tvStatTotalStories.text = (snap?.size() ?: 0).toString()
        }
    }

    // ═══════════════════════════════════════════
    //  CONFIG - Load & Save
    // ═══════════════════════════════════════════
    private fun loadConfig() {
        firestore.collection("config").document("admin").get().addOnSuccessListener { doc ->
            if (doc.exists()) {
                binding.etCoinRate.setText((doc.getLong("coinRate") ?: 30L).toString())
                binding.etMinWithdrawal.setText((doc.getLong("minWithdrawal") ?: 300L).toString())
                binding.etRewardLogin.setText((doc.getLong("rewardLogin") ?: 10L).toString())
                binding.etRewardStory.setText((doc.getLong("rewardStory") ?: 5L).toString())
                binding.etRewardMessage.setText((doc.getLong("rewardMessage") ?: 5L).toString())
                binding.etRewardCall.setText((doc.getLong("rewardCall") ?: 10L).toString())
                binding.switchMaintenance.isChecked = doc.getBoolean("maintenanceMode") ?: false
                binding.switchFreezeWithdrawals.isChecked = doc.getBoolean("freezeWithdrawals") ?: false
            }
        }
    }

    private fun saveConfig() {
        val config = hashMapOf<String, Any>(
            "coinRate" to (binding.etCoinRate.text.toString().toLongOrNull() ?: 30L),
            "minWithdrawal" to (binding.etMinWithdrawal.text.toString().toLongOrNull() ?: 300L),
            "rewardLogin" to (binding.etRewardLogin.text.toString().toLongOrNull() ?: 10L),
            "rewardStory" to (binding.etRewardStory.text.toString().toLongOrNull() ?: 5L),
            "rewardMessage" to (binding.etRewardMessage.text.toString().toLongOrNull() ?: 5L),
            "rewardCall" to (binding.etRewardCall.text.toString().toLongOrNull() ?: 10L),
            "maintenanceMode" to binding.switchMaintenance.isChecked,
            "freezeWithdrawals" to binding.switchFreezeWithdrawals.isChecked
        )
        val newPassword = binding.etAdminPassword.text.toString().trim()
        if (newPassword.isNotEmpty()) config["password"] = newPassword
        
        // Add current admin's UID to allow bypass during maintenance
        auth.currentUser?.uid?.let { config["adminUid"] = it }

        firestore.collection("config").document("admin").set(config, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener { Toast.makeText(this, "✅ Config saved!", Toast.LENGTH_SHORT).show() }
            .addOnFailureListener { Toast.makeText(this, "Error: ${it.message}", Toast.LENGTH_SHORT).show() }
    }

    // ═══════════════════════════════════════════
    //  ANNOUNCEMENT BANNER
    // ═══════════════════════════════════════════
    private fun showAnnouncementDialog() {
        val input = EditText(this).apply { hint = "Banner text (leave empty to clear)"; setPadding(32,32,32,32) }
        UIUtils.showCustomDialog(this, "Set Announcement", "This will show a banner on all users' home screen.", "Set", "Cancel",
            customView = input, onPositive = {
                val text = input.text.toString().trim()
                val data = hashMapOf<String, Any>("text" to text, "active" to text.isNotEmpty(), "timestamp" to System.currentTimeMillis())
                firestore.collection("config").document("announcement").set(data)
                    .addOnSuccessListener { Toast.makeText(this, if (text.isEmpty()) "Banner cleared" else "Banner set!", Toast.LENGTH_SHORT).show() }
            })
    }

    // ═══════════════════════════════════════════
    //  USERS
    // ═══════════════════════════════════════════
    private fun loadUsers() {
        firestore.collection("users").addSnapshotListener { snapshot, _ ->
            if (snapshot != null) {
                allUsers = snapshot.toObjects(User::class.java)
                usersAdapter.updateData(allUsers)
            }
        }
    }

    private fun setupSearch() {
        binding.etUserSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().lowercase()
                val filtered = if (query.isEmpty()) allUsers else allUsers.filter { 
                    it.name.lowercase().contains(query) || 
                    it.email.lowercase().contains(query) ||
                    (query == "admin" && it.isAdmin) ||
                    (query == "role:admin" && it.isAdmin)
                }
                usersAdapter.updateData(filtered)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun showUserOptions(user: User) {
        val isAdmin = user.isAdmin
        val options = arrayOf(
            "🎁 Gift Coins", "💸 Deduct Coins",
            "✅ Toggle Verified Badge", "🔇 Mute User",
            if (user.isBlocked == true) "🔓 Unblock User" else "🚫 Block User",
            if (isAdmin) "⬇️ Remove Admin Access" else "🛡️ Make Admin",
            "👤 View Profile", "🗑️ Delete Account"
        )
        android.app.AlertDialog.Builder(this).setTitle("Manage ${user.name}").setItems(options) { _, which ->
            when (which) {
                0 -> showGiftCoinsDialog(user)
                1 -> showDeductCoinsDialog(user)
                2 -> toggleVerified(user)
                3 -> showMuteDialog(user)
                4 -> toggleBlockUser(user)
                5 -> toggleAdmin(user)
                6 -> showUserProfile(user)
                7 -> confirmDeleteUser(user)
            }
        }.show()
    }

    private fun toggleAdmin(user: User) {
        // Fetch fresh status before toggling to avoid state mismatch
        firestore.collection("users").document(user.uid).get().addOnSuccessListener { doc ->
            val currentStatus = doc.getBoolean("isAdmin") ?: false
            val isNowAdmin = !currentStatus
            
            val title = if (isNowAdmin) "🛡️ Promote to Admin" else "⬇️ Remove Admin"
            val msg = if (isNowAdmin) "Give ${user.name} full administrative access?" 
                      else "Remove administrative access from ${user.name}?"

            UIUtils.showCustomDialog(this, title, msg, "Yes, Proceed", "Cancel", onPositive = {
                firestore.collection("users").document(user.uid).update("isAdmin", isNowAdmin)
                    .addOnSuccessListener { 
                        val status = if (isNowAdmin) "is now an Admin" else "is no longer an Admin"
                        Toast.makeText(this, "🛡️ ${user.name} $status", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
            })
        }
    }

    private fun showGiftCoinsDialog(user: User) {
        val input = EditText(this).apply { hint = "Amount"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; setPadding(32,32,32,32) }
        UIUtils.showCustomDialog(this, "Gift Coins", "Gift coins to ${user.name}.", "Gift", "Cancel", customView = input, onPositive = {
            val amount = input.text.toString().toLongOrNull() ?: 0L
            if (amount > 0) {
                firestore.runTransaction { tx ->
                    val ref = firestore.collection("users").document(user.uid).collection("wallet").document("data")
                    tx.update(ref, "balance", FieldValue.increment(amount))
                    val txRef = firestore.collection("users").document(user.uid).collection("transactions").document()
                    tx.set(txRef, hashMapOf("amount" to amount, "type" to "earn", "source" to "Admin Gift", "timestamp" to System.currentTimeMillis(), "status" to "completed", "referenceId" to com.example.chatsnap.models.Transaction.generateRefId()))
                }.addOnSuccessListener { Toast.makeText(this, "Gifted $amount coins!", Toast.LENGTH_SHORT).show() }
            }
        })
    }

    private fun showDeductCoinsDialog(user: User) {
        val input = EditText(this).apply { hint = "Amount to deduct"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; setPadding(32,32,32,32) }
        UIUtils.showCustomDialog(this, "Deduct Coins", "Remove coins from ${user.name} as a penalty.", "Deduct", "Cancel", customView = input, onPositive = {
            val amount = input.text.toString().toLongOrNull() ?: 0L
            if (amount > 0) {
                firestore.runTransaction { tx ->
                    val ref = firestore.collection("users").document(user.uid).collection("wallet").document("data")
                    tx.update(ref, "balance", FieldValue.increment(-amount))
                    val txRef = firestore.collection("users").document(user.uid).collection("transactions").document()
                    tx.set(txRef, hashMapOf("amount" to -amount, "type" to "withdraw", "source" to "Admin Penalty", "timestamp" to System.currentTimeMillis(), "status" to "completed", "referenceId" to com.example.chatsnap.models.Transaction.generateRefId()))
                }.addOnSuccessListener { Toast.makeText(this, "Deducted $amount coins!", Toast.LENGTH_SHORT).show() }
            }
        })
    }

    private fun toggleVerified(user: User) {
        firestore.collection("users").document(user.uid).get().addOnSuccessListener { doc ->
            val isVerified = doc.getBoolean("isVerified") ?: false
            firestore.collection("users").document(user.uid).update("isVerified", !isVerified)
                .addOnSuccessListener { Toast.makeText(this, if (!isVerified) "✅ ${user.name} is now Verified!" else "Badge removed", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun showMuteDialog(user: User) {
        val options = arrayOf("Mute 24 Hours", "Mute 7 Days", "Mute Permanently", "Unmute")
        android.app.AlertDialog.Builder(this).setTitle("Mute ${user.name}").setItems(options) { _, which ->
            val now = System.currentTimeMillis()
            val muteExpiry = when (which) {
                0 -> now + 24 * 60 * 60 * 1000L
                1 -> now + 7 * 24 * 60 * 60 * 1000L
                2 -> Long.MAX_VALUE
                else -> 0L
            }
            val updates = hashMapOf<String, Any>("isMuted" to (muteExpiry > 0), "muteExpiry" to muteExpiry)
            firestore.collection("users").document(user.uid).update(updates)
                .addOnSuccessListener { Toast.makeText(this, if (muteExpiry > 0) "User muted!" else "User unmuted!", Toast.LENGTH_SHORT).show() }
        }.show()
    }

    private fun showUserProfile(user: User) {
        firestore.collection("users").document(user.uid).collection("wallet").document("data").get().addOnSuccessListener { walletDoc ->
            val balance = walletDoc.getLong("balance") ?: 0L
            firestore.collection("users").document(user.uid).collection("friends").get().addOnSuccessListener { friendsSnap ->
                val friendsCount = friendsSnap.size()
                val isVerified = user.isBlocked != true // placeholder
                firestore.collection("users").document(user.uid).get().addOnSuccessListener { fullDoc ->
                    val verified = fullDoc.getBoolean("isVerified") ?: false
                    val muted = fullDoc.getBoolean("isMuted") ?: false
                    val msg = """
                        👤 Name: ${user.name}
                        📧 Email: ${user.email}
                        📱 Phone: ${user.phone}
                        💰 Balance: $balance coins
                        👥 Friends: $friendsCount
                        ✅ Verified: ${if (verified) "Yes" else "No"}
                        🔇 Muted: ${if (muted) "Yes" else "No"}
                        🚫 Blocked: ${if (user.isBlocked == true) "Yes" else "No"}
                        🟢 Online: ${if (user.online) "Yes" else "No"}
                    """.trimIndent()
                    android.app.AlertDialog.Builder(this).setTitle("User Profile").setMessage(msg).setPositiveButton("Close", null).show()
                }
            }
        }
    }

    private fun toggleBlockUser(user: User) {
        val isBlocked = user.isBlocked == true
        val action = if (isBlocked) "unblock" else "block"
        UIUtils.showCustomDialog(this, "${action.replaceFirstChar { it.uppercase() }} User", "Are you sure you want to $action ${user.name}?", action.replaceFirstChar { it.uppercase() }, "Cancel",
            onPositive = { firestore.collection("users").document(user.uid).update("isBlocked", !isBlocked) })
    }

    private fun confirmDeleteUser(user: User) {
        UIUtils.showCustomDialog(this, "Delete User", "Permanent! Delete ${user.name}'s account?", "Delete", "Cancel",
            onPositive = { firestore.collection("users").document(user.uid).delete() })
    }

    // ═══════════════════════════════════════════
    //  SUPPORT
    // ═══════════════════════════════════════════
    private fun loadSupportRequests() {
        firestore.collection("supportRequests").orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener
                if (snapshot != null) {
                    val requests = try { snapshot.toObjects(SupportRequest::class.java) } catch (err: Exception) { emptyList() }
                    supportAdapter.updateData(requests)
                }
            }
    }

    private fun showSupportReplyDialog(request: SupportRequest) {
        val input = EditText(this).apply { hint = "Enter response..."; setText(request.response ?: ""); setPadding(32,32,32,32) }
        UIUtils.showCustomDialog(this, "Reply to ${request.userName}", "Issue: ${request.message}", "Resolve", "Cancel", customView = input, onPositive = {
            val response = input.text.toString().trim()
            if (response.isNotEmpty()) firestore.collection("supportRequests").document(request.id).update("response", response, "status", "resolved")
        })
    }

    // ═══════════════════════════════════════════
    //  FINANCE
    // ═══════════════════════════════════════════
    private fun loadWithdrawals() {
        firestore.collection("withdrawals").addSnapshotListener { snapshot, e ->
            if (e != null) return@addSnapshotListener
            if (snapshot != null) {
                val list = mutableListOf<Withdrawal>()
                for (doc in snapshot.documents) { try { doc.toObject(Withdrawal::class.java)?.copy(id = doc.id)?.let { list.add(it) } } catch (_: Exception) {} }
                withdrawalAdapter.updateData(list.sortedByDescending { it.timestamp })
            }
        }
    }

    private fun showFulfillDialog(withdrawal: Withdrawal) {
        android.app.AlertDialog.Builder(this).setTitle("Withdrawal Request")
            .setItems(arrayOf("Mark as Completed", "Reject (Refund Coins)", "Cancel")) { _, which ->
                when (which) { 0 -> fulfillWithdrawal(withdrawal, "COMPLETED"); 1 -> rejectWithdrawal(withdrawal) }
            }.show()
    }

    private fun fulfillWithdrawal(withdrawal: Withdrawal, status: String) {
        firestore.runTransaction { tx ->
            tx.update(firestore.collection("withdrawals").document(withdrawal.id), "status", status)
            tx.update(firestore.collection("users").document(withdrawal.uid).collection("transactions").document(withdrawal.transactionId), "status", status.lowercase())
        }.addOnSuccessListener { Toast.makeText(this, "Withdrawal $status", Toast.LENGTH_SHORT).show() }
    }

    private fun rejectWithdrawal(withdrawal: Withdrawal) {
        firestore.runTransaction { tx ->
            tx.update(firestore.collection("withdrawals").document(withdrawal.id), "status", "REJECTED")
            tx.update(firestore.collection("users").document(withdrawal.uid).collection("transactions").document(withdrawal.transactionId), "status", "rejected")
            tx.update(firestore.collection("users").document(withdrawal.uid).collection("wallet").document("data"), "balance", FieldValue.increment(withdrawal.amount))
            val refundRef = firestore.collection("users").document(withdrawal.uid).collection("transactions").document()
            tx.set(refundRef, hashMapOf("amount" to withdrawal.amount, "type" to "earn", "source" to "Withdrawal Refund", "timestamp" to System.currentTimeMillis(), "status" to "completed", "referenceId" to com.example.chatsnap.models.Transaction.generateRefId()))
        }.addOnSuccessListener { Toast.makeText(this, "Rejected & refunded", Toast.LENGTH_SHORT).show() }
    }

    // ═══════════════════════════════════════════
    //  STORIES
    // ═══════════════════════════════════════════
    private fun loadStories() {
        firestore.collection("stories").orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener
                if (snapshot != null) {
                    val list = mutableListOf<Story>()
                    for (doc in snapshot.documents) { try { doc.toObject(Story::class.java)?.copy(id = doc.id)?.let { list.add(it) } } catch (_: Exception) {} }
                    storyAdapter.updateData(list)
                }
            }
    }

    private fun confirmDeleteStory(story: Story) {
        UIUtils.showCustomDialog(this, "Delete Story", "Remove story by ${story.displayName}?", "Delete", "Cancel",
            onPositive = { firestore.collection("stories").document(story.id).delete().addOnSuccessListener { Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show() } })
    }

    // ═══════════════════════════════════════════
    //  REPORTS
    // ═══════════════════════════════════════════
    private fun loadReports() {
        firestore.collection("reports").orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                val items = mutableListOf<Map<String, Any>>()
                snapshot.documents.forEach { doc -> doc.data?.let { items.add(it.plus("docId" to doc.id)) } }

                val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
                    inner class VH(val tv: android.widget.TextView) : androidx.recyclerview.widget.RecyclerView.ViewHolder(tv)
                    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                        val tv = android.widget.TextView(parent.context).apply { setPadding(32,24,32,24); textSize = 13f; setBackgroundResource(R.drawable.bg_setting_item)
                            val lp = android.view.ViewGroup.MarginLayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                            lp.bottomMargin = 8; layoutParams = lp }
                        return VH(tv)
                    }
                    override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                        val item = items[position]
                        val reporterName = item["reporterName"] as? String ?: "Unknown"
                        val reason = item["reason"] as? String ?: "No reason"
                        val content = item["messageContent"] as? String ?: ""
                        val status = item["status"] as? String ?: "pending"
                        (holder as VH).tv.text = "⚠️ $reporterName reported:\n\"$content\"\nReason: $reason\nStatus: $status"
                        holder.tv.setOnClickListener { showReportActions(item) }
                    }
                    override fun getItemCount() = items.size
                }
                binding.rvAdminReports.adapter = adapter
            }
    }

    private fun showReportActions(report: Map<String, Any>) {
        val docId = report["docId"] as? String ?: return
        val reportedUserId = report["reportedUserId"] as? String
        android.app.AlertDialog.Builder(this).setTitle("Report Action")
            .setItems(arrayOf("Dismiss Report", "Delete Reported Message", "Mute Reporter's Target", "Block Reported User")) { _, which ->
                when (which) {
                    0 -> firestore.collection("reports").document(docId).update("status", "dismissed")
                    1 -> { val msgId = report["messageId"] as? String; if (msgId != null) firestore.collection("messages").document(msgId).update("isDeleted", true)
                        firestore.collection("reports").document(docId).update("status", "actioned") }
                    2 -> if (reportedUserId != null) { firestore.collection("users").document(reportedUserId).update("isMuted", true, "muteExpiry", System.currentTimeMillis() + 24*60*60*1000L)
                        firestore.collection("reports").document(docId).update("status", "actioned") }
                    3 -> if (reportedUserId != null) { firestore.collection("users").document(reportedUserId).update("isBlocked", true)
                        firestore.collection("reports").document(docId).update("status", "actioned") }
                }
                Toast.makeText(this, "Action taken!", Toast.LENGTH_SHORT).show()
            }.show()
    }

    // ═══════════════════════════════════════════
    //  BROADCAST
    // ═══════════════════════════════════════════
    private fun showBroadcastDialog() {
        val input = EditText(this).apply { hint = "Broadcast message..."; setPadding(32,32,32,32) }
        UIUtils.showCustomDialog(this, "Broadcast", "Send to all users.", "Send", "Cancel", customView = input,
            onPositive = { sendBroadcast(input.text.toString()) })
    }

    private fun sendBroadcast(message: String) {
        if (message.isEmpty()) return
        Toast.makeText(this, "Sending broadcast...", Toast.LENGTH_SHORT).show()
        val currentUid = auth.currentUser?.uid ?: "ADMIN"
        val broadcastId = firestore.collection("broadcasts").document().id
        val broadcastData = hashMapOf(
            "broadcastId" to broadcastId,
            "senderId" to currentUid,
            "content" to message,
            "timestamp" to System.currentTimeMillis(),
            "isActive" to true
        )
        firestore.collection("broadcasts").document(broadcastId).set(broadcastData)
            .addOnSuccessListener { Toast.makeText(this, "📢 Broadcast sent to all users!", Toast.LENGTH_SHORT).show() }
            .addOnFailureListener { Toast.makeText(this, "Failed: ${it.message}", Toast.LENGTH_SHORT).show() }
    }
}
