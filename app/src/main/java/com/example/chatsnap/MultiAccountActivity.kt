package com.example.chatsnap

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.chatsnap.databinding.ActivityMultiAccountBinding
import com.example.chatsnap.databinding.ItemSavedAccountBinding
import com.example.chatsnap.utils.AccountManager
import com.google.firebase.auth.FirebaseAuth

class MultiAccountActivity : BaseActivity() {

    private lateinit var binding: ActivityMultiAccountBinding
    private lateinit var auth: FirebaseAuth
    private var savedAccounts = mutableListOf<AccountManager.SavedAccount>()
    private lateinit var adapter: AccountsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMultiAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecyclerView()

        binding.btnAddAccount.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java).apply {
                putExtra("add_account", true)
            }
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        loadSavedAccounts()
    }

    private fun loadSavedAccounts() {
        savedAccounts.clear()
        savedAccounts.addAll(AccountManager.getAccounts(this))
        adapter.notifyDataSetChanged()
        
        if (savedAccounts.isEmpty()) {
            binding.tvSelectHint.text = "No saved accounts yet. Please add one."
        } else {
            binding.tvSelectHint.text = "Choose an account to login"
        }
    }

    private fun setupRecyclerView() {
        adapter = AccountsAdapter()
        binding.rvAccounts.layoutManager = LinearLayoutManager(this)
        binding.rvAccounts.adapter = adapter
    }

    private fun switchAccount(account: AccountManager.SavedAccount) {
        binding.progressBar.visibility = View.VISIBLE
        try {
            val password = AccountManager.decryptPassword(
                account.encryptedPasswordBase64,
                account.ivBase64
            )
            auth.signOut()
            AppLockActivity.isUnlocked = false
            auth.signInWithEmailAndPassword(account.email, password)
                .addOnSuccessListener {
                    val uid = auth.currentUser?.uid ?: account.uid
                    com.example.chatsnap.utils.SessionManager.startNewSession(this, uid) {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(this, "Logged in as ${account.name}", Toast.LENGTH_SHORT).show()
                        val intent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        startActivity(intent)
                        finish()
                    }
                }
                .addOnFailureListener { e ->
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this, "Login failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
        } catch (e: Exception) {
            binding.progressBar.visibility = View.GONE
            Toast.makeText(this, "Decryption/Login failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmRemoveAccount(account: AccountManager.SavedAccount) {
        AlertDialog.Builder(this)
            .setTitle("Remove Account")
            .setMessage("Are you sure you want to remove ${account.name} from this device?")
            .setPositiveButton("Remove") { _, _ ->
                AccountManager.removeAccount(this, account.uid)
                loadSavedAccounts()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class AccountsAdapter : RecyclerView.Adapter<AccountsAdapter.ViewHolder>() {

        inner class ViewHolder(val itemBinding: ItemSavedAccountBinding) : RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemSavedAccountBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val account = savedAccounts[position]
            holder.itemBinding.tvName.text = account.name
            holder.itemBinding.tvEmail.text = account.email

            if (account.profileImageUrl.isNotEmpty()) {
                if (account.profileImageUrl.startsWith("data:image")) {
                    try {
                        val cleanBase64 = account.profileImageUrl.substringAfter(",")
                        val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                        holder.itemBinding.ivProfile.setImageBitmap(bitmap)
                    } catch (e: Exception) {
                        holder.itemBinding.ivProfile.setImageResource(R.drawable.ic_launcher_foreground)
                    }
                } else {
                    holder.itemBinding.ivProfile.load(account.profileImageUrl)
                }
            } else {
                holder.itemBinding.ivProfile.setImageResource(R.drawable.ic_launcher_foreground)
            }

            holder.itemView.setOnClickListener {
                switchAccount(account)
            }

            holder.itemBinding.btnRemove.setOnClickListener {
                confirmRemoveAccount(account)
            }
        }

        override fun getItemCount() = savedAccounts.size
    }
}
