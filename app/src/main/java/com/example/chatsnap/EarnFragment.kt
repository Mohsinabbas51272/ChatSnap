package com.example.chatsnap

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.chatsnap.databinding.FragmentEarnBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.text.SimpleDateFormat
import java.util.*

class EarnFragment : Fragment() {
    private var _binding: FragmentEarnBinding? = null
    private val binding get() = _binding!!
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private var rewardLogin = 10L
    private var rewardStory = 15L // Send snaps
    private var rewardMessage = 20L // Referral
    private var rewardCall = 30L // See ads
    private var rewardInvite = 25L // Invite Friends

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEarnBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        loadConfig()
        loadWalletData()
        checkDailyClaimStatus()

        binding.btnWithdraw.setOnClickListener {
            val safeContext = context ?: return@setOnClickListener
            startActivity(Intent(safeContext, WalletActivity::class.java))
        }

        binding.btnInvite.setOnClickListener {
            shareReferralLink()
        }
    }

    private fun loadConfig() {
        firestore.collection("config").document("admin").get().addOnSuccessListener { doc ->
            if (_binding != null && doc != null && doc.exists()) {
                rewardLogin = doc.getLong("rewardLogin") ?: 10L
                rewardStory = doc.getLong("rewardStory") ?: 15L
                rewardMessage = doc.getLong("rewardMessage") ?: 20L
                rewardCall = doc.getLong("rewardCall") ?: 30L
                rewardInvite = doc.getLong("rewardInvite") ?: 25L
            }
            if (_binding != null) {
                setupQuests()
            }
        }.addOnFailureListener {
            if (_binding != null) {
                setupQuests()
            }
        }
    }

    private fun setupQuests() {
        if (_binding == null) return
        binding.cardDailyLogin.tvQuestTitle.text = "Daily check-in"
        binding.cardDailyLogin.tvQuestReward.text = "+$rewardLogin Coins"

        binding.cardTask2.tvQuestTitle.text = "Send snaps to friend"
        binding.cardTask2.tvQuestReward.text = "+$rewardStory Coins"

        binding.cardTask3.tvQuestTitle.text = "Submit referral form"
        binding.cardTask3.tvQuestReward.text = "+$rewardMessage Coins"

        binding.cardTask4.tvQuestTitle.text = "See ads 3 per day"
        binding.cardTask4.tvQuestReward.text = "+$rewardCall Coins"

        binding.cardTask5.tvQuestTitle.text = "Share link to invite and big earn"
        binding.cardTask5.tvQuestReward.text = "+$rewardInvite Coins"
    }

    private fun loadWalletData() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid)
            .collection("wallet").document("data")
            .addSnapshotListener { snapshot, e ->
                if (_binding != null && e == null) {
                    if (snapshot != null && snapshot.exists()) {
                        val balance = snapshot.getLong("balance") ?: 0L
                        binding.tvBalance.text = balance.toString()
                    } else {
                        binding.tvBalance.text = "0"
                        firestore.collection("users").document(uid)
                            .collection("wallet").document("data")
                            .set(mapOf("balance" to 0L), SetOptions.merge())
                    }
                }
            }
    }

    private fun checkDailyClaimStatus() {
        val uid = auth.currentUser?.uid ?: return
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        
        firestore.collection("users").document(uid)
            .collection("wallet").document("data")
            .addSnapshotListener { doc, _ ->
                if (_binding != null && doc != null && doc.exists()) {
                    val loginClaimed = doc.getString("lastLoginDate") == today
                    val task2Claimed = doc.getString("lastTask2Date") == today
                    val task3Claimed = doc.getString("lastTask3Date") == today
                    val task4Claimed = doc.getString("lastTask4Date") == today
                    val task5Claimed = doc.getString("lastTask5Date") == today

                    val task2Done = doc.getString("pendingTask2Date") == today || task2Claimed
                    val task3Done = doc.getString("pendingTask3Date") == today || task3Claimed
                    val task4Done = doc.getString("pendingTask4Date") == today || task4Claimed
                    val task5Done = doc.getString("pendingTask5Date") == today || task5Claimed

                    // Check-in is always ready to claim daily
                    updateButtonStatus(binding.cardDailyLogin.btnClaim, loginClaimed, true, "Check-in",
                        onActionClick = { 
                            claimTask("Daily Check-in", "lastLoginDate", rewardLogin, binding.cardDailyLogin.btnClaim)
                        },
                        onClaimClick = {
                            claimTask("Daily Check-in", "lastLoginDate", rewardLogin, binding.cardDailyLogin.btnClaim)
                        }
                    )

                    updateButtonStatus(binding.cardTask2.btnClaim, task2Claimed, task2Done, "Send Snap",
                        onActionClick = { showSendSnapSimulationDialog() },
                        onClaimClick = { claimTask("Send Snaps to Friend", "lastTask2Date", rewardStory, binding.cardTask2.btnClaim) }
                    )

                    updateButtonStatus(binding.cardTask3.btnClaim, task3Claimed, task3Done, "Submit",
                        onActionClick = { showReferralFormDialog() },
                        onClaimClick = { claimTask("Submit Referral Form", "lastTask3Date", rewardMessage, binding.cardTask3.btnClaim) }
                    )

                    updateButtonStatus(binding.cardTask4.btnClaim, task4Claimed, task4Done, "Watch Ad",
                        onActionClick = { showAdViewerSimulatorDialog() },
                        onClaimClick = { claimTask("See Ads 3 per Day", "lastTask4Date", rewardCall, binding.cardTask4.btnClaim) }
                    )

                    updateButtonStatus(binding.cardTask5.btnClaim, task5Claimed, task5Done, "Share Link",
                        onActionClick = { shareReferralLink() },
                        onClaimClick = { claimTask("Share Link & Earn", "lastTask5Date", rewardInvite, binding.cardTask5.btnClaim) }
                    )
                    
                    // Calculate today's earnings
                    var todayEarnings = 0L
                    if (loginClaimed) todayEarnings += rewardLogin
                    if (task2Claimed) todayEarnings += rewardStory
                    if (task3Claimed) todayEarnings += rewardMessage
                    if (task4Claimed) todayEarnings += rewardCall
                    if (task5Claimed) todayEarnings += rewardInvite
                    binding.tvTodayEarned.text = "+$todayEarnings"
                }
            }
    }

    private fun updateButtonStatus(
        button: com.google.android.material.button.MaterialButton,
        claimed: Boolean,
        taskDone: Boolean,
        actionLabel: String,
        onActionClick: () -> Unit,
        onClaimClick: () -> Unit
    ) {
        val safeContext = context ?: return
        if (claimed) {
            button.text = "Claimed"
            button.isEnabled = false
            button.alpha = 0.5f
            button.setOnClickListener(null)
            button.setBackgroundColor(ContextCompat.getColor(safeContext, android.R.color.darker_gray))
        } else if (taskDone) {
            button.text = "Claim Now"
            button.isEnabled = true
            button.alpha = 1.0f
            button.setOnClickListener { onClaimClick() }
            button.setBackgroundColor(ContextCompat.getColor(safeContext, R.color.primary))
        } else {
            button.text = actionLabel
            button.isEnabled = true
            button.alpha = 1.0f
            button.setOnClickListener { onActionClick() }
            button.setBackgroundColor(ContextCompat.getColor(safeContext, R.color.secondary))
        }
    }

    private fun showSendSnapSimulationDialog() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid).get().addOnSuccessListener { doc ->
            if (_binding == null || !isAdded) return@addOnSuccessListener
            val friendsList = doc.get("friends") as? List<String> ?: emptyList()
            
            val friendNames = mutableListOf<String>()
            friendNames.add("ChatSnap Assistant AI 🤖")
            friendNames.add("Team ChatSnap Support 🛡️")
            
            if (friendsList.isEmpty()) {
                showFriendSelectionDialog(friendNames)
            } else {
                firestore.collection("users").whereIn("uid", friendsList).get().addOnSuccessListener { snapshot ->
                    if (_binding == null || !isAdded) return@addOnSuccessListener
                    snapshot.documents.forEach { fDoc ->
                        fDoc.getString("name")?.let { friendNames.add(it) }
                    }
                    showFriendSelectionDialog(friendNames)
                }.addOnFailureListener {
                    if (_binding != null && isAdded) {
                        showFriendSelectionDialog(friendNames)
                    }
                }
            }
        }
    }

    private fun showFriendSelectionDialog(friends: List<String>) {
        val safeContext = context ?: return
        val items = friends.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(safeContext)
            .setTitle("Send a Snap")
            .setItems(items) { _, which ->
                val selected = items[which]
                val ctx = context ?: return@setItems
                Toast.makeText(ctx, "📸 Sending snap to $selected...", Toast.LENGTH_SHORT).show()
                
                val currentUid = auth.currentUser?.uid ?: return@setItems
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val walletRef = firestore.collection("users").document(currentUid).collection("wallet").document("data")
                walletRef.update("pendingTask2Date", today)
                    .addOnSuccessListener {
                        val c = context ?: return@addOnSuccessListener
                        Toast.makeText(c, "Snap sent! Task completed! 🎉", Toast.LENGTH_SHORT).show()
                    }
            }
            .show()
    }

    private fun showReferralFormDialog() {
        val safeContext = context ?: return
        val rootLayout = android.widget.LinearLayout(safeContext).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val input = android.widget.EditText(safeContext).apply {
            hint = "Enter Referral Code or Email"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        rootLayout.addView(input)

        android.app.AlertDialog.Builder(safeContext)
            .setTitle("Submit Referral Form")
            .setView(rootLayout)
            .setPositiveButton("Submit") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isNotEmpty()) {
                    val currentUid = auth.currentUser?.uid ?: return@setPositiveButton
                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    val walletRef = firestore.collection("users").document(currentUid).collection("wallet").document("data")
                    walletRef.update("pendingTask3Date", today)
                        .addOnSuccessListener {
                            val c = context ?: return@addOnSuccessListener
                            Toast.makeText(c, "Referral submitted! Task completed! 🎉", Toast.LENGTH_SHORT).show()
                        }
                } else {
                    val c = context ?: return@setPositiveButton
                    Toast.makeText(c, "Referral code cannot be empty.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAdViewerSimulatorDialog() {
        val safeContext = context ?: return
        val progressDialog = android.app.ProgressDialog(safeContext).apply {
            setTitle("Loading Daily Sponsors")
            setMessage("Watching Ad 1 of 3...")
            setProgressStyle(android.app.ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            show()
        }

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (_binding == null || !isAdded) {
                progressDialog.dismiss()
                return@postDelayed
            }
            progressDialog.setMessage("Watching Ad 2 of 3...")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (_binding == null || !isAdded) {
                    progressDialog.dismiss()
                    return@postDelayed
                }
                progressDialog.setMessage("Watching Ad 3 of 3...")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    progressDialog.dismiss()
                    val uid = auth.currentUser?.uid ?: return@postDelayed
                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    val walletRef = firestore.collection("users").document(uid).collection("wallet").document("data")
                    walletRef.update("pendingTask4Date", today)
                        .addOnSuccessListener {
                            val c = context ?: return@addOnSuccessListener
                            Toast.makeText(c, "Watched all ads! Task completed! 📺🎉", Toast.LENGTH_SHORT).show()
                        }
                }, 2000)
            }, 2000)
        }, 2000)
    }

    private fun shareReferralLink() {
        val safeContext = context ?: return
        val currentUid = auth.currentUser?.uid ?: return
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Join ChatSnap and earn rewards! My referral link: https://chatsnap.app/invite/$currentUid")
        }
        startActivity(Intent.createChooser(shareIntent, "Invite via"))
        
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val walletRef = firestore.collection("users").document(currentUid).collection("wallet").document("data")
        walletRef.update("pendingTask5Date", today)
            .addOnSuccessListener {
                val c = context ?: return@addOnSuccessListener
                Toast.makeText(c, "Link shared! Task completed! 🎉", Toast.LENGTH_SHORT).show()
            }
    }

    private fun claimTask(taskName: String, dateField: String, amount: Long, button: com.google.android.material.button.MaterialButton) {
        val uid = auth.currentUser?.uid ?: return
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        
        button.isEnabled = false
        
        firestore.runTransaction { transaction ->
            val walletRef = firestore.collection("users").document(uid).collection("wallet").document("data")
            val walletDoc = transaction.get(walletRef)
            
            val currentBalance = if (walletDoc.exists()) walletDoc.getLong("balance") ?: 0L else 0L
            val lastDate = if (walletDoc.exists()) walletDoc.getString(dateField) else null
            
            if (lastDate == today) {
                throw Exception("Already claimed today")
            }

            val updates = hashMapOf<String, Any>(
                "balance" to (currentBalance + amount),
                dateField to today
            )
            transaction.set(walletRef, updates, SetOptions.merge())
            
            val txRef = firestore.collection("users").document(uid).collection("transactions").document()
            transaction.set(txRef, hashMapOf(
                "amount" to amount,
                "type" to "earn",
                "source" to taskName,
                "timestamp" to System.currentTimeMillis(),
                "status" to "completed",
                "referenceId" to com.example.chatsnap.models.Transaction.generateRefId()
            ))
        }.addOnSuccessListener {
            if (_binding != null && isAdded) {
                val safeContext = context ?: return@addOnSuccessListener
                Toast.makeText(safeContext, "Claimed $amount coins! 🎉", Toast.LENGTH_SHORT).show()
                updateButtonStatus(button, true, true, "", {}, {})
                showCoinBurst(button)
            }
        }.addOnFailureListener { e ->
            if (_binding != null && isAdded) {
                button.isEnabled = true
                val safeContext = context ?: return@addOnFailureListener
                val msg = if (e.message?.contains("Already claimed") == true) "Already claimed today!" else "Error: ${e.message}"
                Toast.makeText(safeContext, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCoinBurst(anchorView: android.view.View) {
        val safeContext = context ?: return
        val container = (activity?.findViewById<android.view.ViewGroup>(android.R.id.content))
            ?: (binding.root.getChildAt(0) as? android.view.ViewGroup)
            ?: return

        val location = IntArray(2)
        anchorView.getLocationInWindow(location)
        val containerLocation = IntArray(2)
        container.getLocationInWindow(containerLocation)
        val startX = (location[0] - containerLocation[0] + anchorView.width / 2).toFloat()
        val startY = (location[1] - containerLocation[1] + anchorView.height / 2).toFloat()

        val emojis = listOf("🪙", "💰", "✨", "⭐", "🪙", "💫", "🪙")
        val random = java.util.Random()
        emojis.forEachIndexed { index, emoji ->
            val tv = android.widget.TextView(safeContext).apply {
                text = emoji
                textSize = 22f
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
                x = startX
                y = startY
                alpha = 1f
            }
            container.addView(tv)

            val dx = (random.nextFloat() - 0.5f) * 400f
            val dy = -(random.nextFloat() * 300f + 100f)
            val delay = index * 60L

            android.animation.AnimatorSet().apply {
                val moveX = android.animation.ObjectAnimator.ofFloat(tv, "translationX", 0f, dx)
                val moveY = android.animation.ObjectAnimator.ofFloat(tv, "translationY", 0f, dy)
                val fadeOut = android.animation.ObjectAnimator.ofFloat(tv, "alpha", 1f, 0f)
                val scaleX = android.animation.ObjectAnimator.ofFloat(tv, "scaleX", 0.5f, 1.2f, 0f)
                val scaleY = android.animation.ObjectAnimator.ofFloat(tv, "scaleY", 0.5f, 1.2f, 0f)
                playTogether(moveX, moveY, fadeOut, scaleX, scaleY)
                duration = 900
                startDelay = delay
                interpolator = android.view.animation.DecelerateInterpolator()
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        container.removeView(tv)
                    }
                })
                start()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
