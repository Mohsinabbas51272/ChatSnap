package com.example.chatsnap

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatsnap.adapters.TransactionAdapter
import com.example.chatsnap.databinding.ActivityWalletBinding
import com.example.chatsnap.models.Transaction
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

class WalletActivity : BaseActivity() {
    private lateinit var binding: ActivityWalletBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var adapter: TransactionAdapter
    private val transactions = mutableListOf<Transaction>()

    private var MIN_WITHDRAWAL = 300
    private var COIN_RATE = 30.0
    private var withdrawalsFrozen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWalletBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        setupToolbar()
        setupRecyclerView()
        loadConfig()
        loadWalletData()
        loadTransactions()

        binding.btnSubmitRequest.setOnClickListener {
            handleWithdrawal()
        }
    }

    private fun loadConfig() {
        firestore.collection("config").document("admin").get().addOnSuccessListener { doc ->
            if (doc.exists()) {
                MIN_WITHDRAWAL = (doc.getLong("minWithdrawal") ?: 300L).toInt()
                COIN_RATE = (doc.getLong("coinRate") ?: 30L).toDouble()
                withdrawalsFrozen = doc.getBoolean("freezeWithdrawals") ?: false
            }
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        adapter = TransactionAdapter(transactions)
        binding.rvTransactions.layoutManager = LinearLayoutManager(this)
        binding.rvTransactions.adapter = adapter
    }

    private fun loadWalletData() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid)
            .collection("wallet").document("data")
            .addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener
                if (snapshot != null && snapshot.exists()) {
                    val balance = snapshot.getLong("balance") ?: 0L
                    binding.tvBalance.text = balance.toString()
                    binding.tvEstPkr.text = String.format("Est. PKR: %.2f", balance.toDouble() / COIN_RATE)
                }
            }
    }

    private fun loadTransactions() {
        val uid = auth.currentUser?.uid ?: return
        binding.progressBar.visibility = View.VISIBLE
        
        firestore.collection("users").document(uid)
            .collection("transactions")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snapshot, e ->
                binding.progressBar.visibility = View.GONE
                if (e != null) return@addSnapshotListener
                
                if (snapshot != null) {
                    transactions.clear()
                    for (doc in snapshot.documents) {
                        val tx = Transaction(
                            id = doc.id,
                            amount = doc.getLong("amount")?.toInt() ?: 0,
                            type = doc.getString("type") ?: "earn",
                            source = doc.getString("source"),
                            status = doc.getString("status"),
                            timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis(),
                            accountDetails = doc.getString("accountDetails"),
                            referenceId = doc.getString("referenceId") ?: ""
                        )
                        transactions.add(tx)
                    }
                    adapter.updateData(transactions)
                    binding.tvNoTransactions.visibility = if (transactions.isEmpty()) View.VISIBLE else View.GONE
                }
            }
    }

    private fun handleWithdrawal() {
        val amountStr = binding.etWithdrawAmount.text.toString()
        val uid = auth.currentUser?.uid ?: return

        if (amountStr.isEmpty()) {
            Toast.makeText(this, "Please enter amount", Toast.LENGTH_SHORT).show()
            return
        }

        val amount = amountStr.toInt()
        if (withdrawalsFrozen) {
            Toast.makeText(this, "Withdrawals are temporarily disabled. Try again later.", Toast.LENGTH_LONG).show()
            return
        }
        if (amount < MIN_WITHDRAWAL) {
            Toast.makeText(this, "Minimum withdrawal is $MIN_WITHDRAWAL coins", Toast.LENGTH_SHORT).show()
            return
        }

        val method = if (binding.rbEasypaisa.isChecked) "Easypaisa" else "JazzCash"
        val number = binding.etAccountNumber.text.toString().trim()
        val name = binding.etAccountName.text.toString().trim()

        if (number.length < 10) {
            Toast.makeText(this, "Please enter a valid account number", Toast.LENGTH_SHORT).show()
            return
        }

        if (name.isEmpty()) {
            Toast.makeText(this, "Please enter account holder name", Toast.LENGTH_SHORT).show()
            return
        }

        val accountDetails = "[$method] $name - $number"

        com.example.chatsnap.utils.UIUtils.showCustomDialog(
            this,
            "Confirm Withdrawal",
            "Are you sure you want to withdraw $amount coins to the provided account? This will deduct $amount coins from your balance.",
            "Withdraw",
            "Cancel",
            onPositive = {
                performWithdrawal(amount, accountDetails)
            }
        )
    }

    private fun performWithdrawal(amount: Int, accountDetails: String) {
        val uid = auth.currentUser?.uid ?: return
        binding.btnSubmitRequest.isEnabled = false
        
        firestore.runTransaction { transaction ->
            val walletRef = firestore.collection("users").document(uid)
                .collection("wallet").document("data")
            val walletDoc = transaction.get(walletRef)
            
            val currentBalance = walletDoc.getLong("balance") ?: 0L
            if (currentBalance < amount) {
                throw Exception("Insufficient balance")
            }

            transaction.update(walletRef, "balance", currentBalance - amount)

            val txRef = firestore.collection("users").document(uid)
                .collection("transactions").document()
            val refId = Transaction.generateRefId()
            val txData = hashMapOf(
                "amount" to -amount,
                "type" to "withdraw",
                "source" to "Manual Withdrawal",
                "accountDetails" to accountDetails,
                "timestamp" to System.currentTimeMillis(),
                "status" to "pending",
                "referenceId" to refId
            )
            transaction.set(txRef, txData)

            val userDisplayName = auth.currentUser?.displayName ?: "User"
            val globalWithdrawRef = firestore.collection("withdrawals").document(txRef.id)
            val globalData = hashMapOf(
                "id" to txRef.id,
                "uid" to uid,
                "userDisplayName" to userDisplayName,
                "transactionId" to txRef.id,
                "amount" to amount.toLong(),
                "accountDetails" to accountDetails,
                "status" to "PENDING",
                "timestamp" to com.google.firebase.Timestamp.now()
            )
            transaction.set(globalWithdrawRef, globalData)
        }.addOnSuccessListener {
            com.example.chatsnap.utils.UIUtils.showCustomDialog(
                this,
                "Request Submitted",
                "Your withdrawal request for $amount coins has been submitted and is currently pending approval.",
                "Done",
                "",
                onPositive = {}
            )
            binding.etWithdrawAmount.text.clear()
            binding.etAccountNumber.text.clear()
            binding.etAccountName.text.clear()
            binding.btnSubmitRequest.isEnabled = true
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            binding.btnSubmitRequest.isEnabled = true
        }
    }
}
