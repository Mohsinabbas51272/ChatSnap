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
    private var rewardStory = 5L
    private var rewardMessage = 5L
    private var rewardCall = 10L

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
            startActivity(Intent(requireContext(), WalletActivity::class.java))
        }

        binding.cardDailyLogin.btnClaim.setOnClickListener {
            claimTask("Daily Check-in", "lastLoginDate", rewardLogin, binding.cardDailyLogin.btnClaim)
        }
        binding.cardTask2.btnClaim.setOnClickListener {
            claimTask("View Stories", "lastTask2Date", rewardStory, binding.cardTask2.btnClaim)
        }
        binding.cardTask3.btnClaim.setOnClickListener {
            claimTask("Send Messages", "lastTask3Date", rewardMessage, binding.cardTask3.btnClaim)
        }
        binding.cardTask4.btnClaim.setOnClickListener {
            claimTask("Make a Call", "lastTask4Date", rewardCall, binding.cardTask4.btnClaim)
        }

        binding.btnInvite.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND)
            shareIntent.type = "text/plain"
            shareIntent.putExtra(Intent.EXTRA_TEXT, "Join ChatSnap and earn rewards! My referral link: https://chatsnap.app/invite/${auth.uid}")
            startActivity(Intent.createChooser(shareIntent, "Invite via"))
        }
    }

    private fun loadConfig() {
        firestore.collection("config").document("admin").get().addOnSuccessListener { doc ->
            if (doc.exists()) {
                rewardLogin = doc.getLong("rewardLogin") ?: 10L
                rewardStory = doc.getLong("rewardStory") ?: 5L
                rewardMessage = doc.getLong("rewardMessage") ?: 5L
                rewardCall = doc.getLong("rewardCall") ?: 10L
            }
            setupQuests()
        }.addOnFailureListener { setupQuests() }
    }

    private fun setupQuests() {
        binding.cardDailyLogin.tvQuestTitle.text = "Daily Check-in"
        binding.cardDailyLogin.tvQuestReward.text = "+$rewardLogin Coins"

        binding.cardTask2.tvQuestTitle.text = "View Stories"
        binding.cardTask2.tvQuestReward.text = "+$rewardStory Coins"

        binding.cardTask3.tvQuestTitle.text = "Send Messages"
        binding.cardTask3.tvQuestReward.text = "+$rewardMessage Coins"

        binding.cardTask4.tvQuestTitle.text = "Make a Call"
        binding.cardTask4.tvQuestReward.text = "+$rewardCall Coins"
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
                        // Initialize wallet if not exists
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
                    updateButtonStatus(binding.cardDailyLogin.btnClaim, doc.getString("lastLoginDate") == today, true)
                    updateButtonStatus(binding.cardTask2.btnClaim, doc.getString("lastTask2Date") == today, doc.getString("pendingTask2Date") == today)
                    updateButtonStatus(binding.cardTask3.btnClaim, doc.getString("lastTask3Date") == today, doc.getString("pendingTask3Date") == today)
                    updateButtonStatus(binding.cardTask4.btnClaim, doc.getString("lastTask4Date") == today, doc.getString("pendingTask4Date") == today)
                    
                    // Calculate today's earnings
                    var todayEarnings = 0L
                    if (doc.getString("lastLoginDate") == today) todayEarnings += rewardLogin
                    if (doc.getString("lastTask2Date") == today) todayEarnings += rewardStory
                    if (doc.getString("lastTask3Date") == today) todayEarnings += rewardMessage
                    if (doc.getString("lastTask4Date") == today) todayEarnings += rewardCall
                    binding.tvTodayEarned.text = "+$todayEarnings"
                }
            }
    }

    private fun updateButtonStatus(button: com.google.android.material.button.MaterialButton, claimed: Boolean, taskDone: Boolean) {
        if (claimed) {
            button.text = "Claimed"
            button.isEnabled = false
            button.alpha = 0.5f
        } else if (taskDone) {
            button.text = "Claim Now"
            button.isEnabled = true
            button.alpha = 1.0f
            button.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.primary))
        } else {
            button.text = "Not Done"
            button.isEnabled = false
            button.alpha = 0.6f
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

            if (!walletDoc.exists()) {
                transaction.set(walletRef, mapOf("balance" to amount, dateField to today))
            } else {
                transaction.update(walletRef, "balance", currentBalance + amount)
                transaction.update(walletRef, dateField, today)
            }
            
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
            if (_binding != null) {
                Toast.makeText(context, "Claimed $amount coins!", Toast.LENGTH_SHORT).show()
                updateButtonStatus(button, true, true)
            }
        }.addOnFailureListener { e ->
            if (_binding != null) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                button.isEnabled = true
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
