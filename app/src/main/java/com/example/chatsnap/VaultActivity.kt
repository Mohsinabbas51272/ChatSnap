package com.example.chatsnap

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.example.chatsnap.databinding.ActivityVaultBinding
import com.example.chatsnap.models.Message
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class VaultActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVaultBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val savedMedia = mutableListOf<Message>()
    private lateinit var adapter: VaultAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVaultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupVault()
    }

    private fun setupVault() {
        val prefs = getSharedPreferences("vault_prefs", MODE_PRIVATE)
        val savedPin = prefs.getString("vault_pin", null)

        if (savedPin == null) {
            binding.tvPinStatus.text = "Set a 4-digit PIN for your Vault"
            binding.btnUnlock.text = "Set PIN"
        }

        binding.btnUnlock.setOnClickListener {
            val enteredPin = binding.etPin.text.toString()
            if (enteredPin.length != 4) {
                Toast.makeText(this, "PIN must be 4 digits", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (savedPin == null) {
                prefs.edit().putString("vault_pin", enteredPin).apply()
                unlockVault()
            } else if (enteredPin == savedPin) {
                unlockVault()
            } else {
                Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun unlockVault() {
        binding.pinLayout.visibility = View.GONE
        binding.rvVault.visibility = View.VISIBLE
        loadVaultMedia()
    }

    private fun loadVaultMedia() {
        val uid = auth.uid ?: return
        db.collection("users").document(uid).collection("vault")
            .get()
            .addOnSuccessListener { snapshot ->
                savedMedia.clear()
                savedMedia.addAll(snapshot.toObjects(Message::class.java))
                setupRecyclerView()
            }
    }

    private fun setupRecyclerView() {
        adapter = VaultAdapter(savedMedia) { message ->
            // View full screen media
            val intent = android.content.Intent(this, MediaViewerActivity::class.java)
            intent.putExtra("mediaUrl", message.mediaUrl)
            intent.putExtra("mediaType", message.type)
            startActivity(intent)
        }
        binding.rvVault.layoutManager = GridLayoutManager(this, 3)
        binding.rvVault.adapter = adapter
    }
}

class VaultAdapter(
    private val items: List<Message>,
    private val onClick: (Message) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<VaultAdapter.ViewHolder>() {

    class ViewHolder(val view: android.widget.ImageView) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val iv = android.widget.ImageView(parent.context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                300
            )
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setPadding(2, 2, 2, 2)
        }
        return ViewHolder(iv)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        // Use coil or similar to load
        // holder.view.load(item.mediaUrl)
        if (item.mediaUrl?.startsWith("data:image") == true) {
            val clean = item.mediaUrl.substringAfter(",")
            val bytes = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            holder.view.setImageBitmap(bitmap)
        } else {
            // Simplified
            holder.view.setImageResource(android.R.drawable.ic_menu_gallery)
        }
        holder.view.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}
