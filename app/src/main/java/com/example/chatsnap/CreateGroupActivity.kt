package com.example.chatsnap

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatsnap.adapters.SelectFriendsAdapter
import com.example.chatsnap.databinding.ActivityCreateGroupBinding
import com.example.chatsnap.models.Group
import com.example.chatsnap.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class CreateGroupActivity : BaseActivity() {
    private lateinit var binding: ActivityCreateGroupBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private val selectedMemberIds = mutableSetOf<String>()
    private var groupImageBase64: String? = null

    private val pickImage = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            binding.ivGroupImage.setImageURI(it)
            encodeImageToBase64(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        setupFriendsList()

        binding.ivGroupImage.setOnClickListener {
            pickImage.launch("image/*")
        }

        binding.btnCreate.setOnClickListener {
            val groupName = binding.etGroupName.text.toString().trim()
            if (groupName.isEmpty()) {
                Toast.makeText(this, "Please enter group name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selectedMemberIds.size < 1) {
                Toast.makeText(this, "Select at least 1 member", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            createGroup(groupName)
        }
    }

    private fun encodeImageToBase64(uri: android.net.Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes() ?: return
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val out = java.io.ByteArrayOutputStream()
            // Resize for group icon
            val resized = android.graphics.Bitmap.createScaledBitmap(bitmap, 200, 200, true)
            resized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, out)
            val base64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.DEFAULT)
            groupImageBase64 = "data:image/jpeg;base64,$base64"
        } catch (e: Exception) {}
    }

    private fun setupFriendsList() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).get().addOnSuccessListener { doc ->
            @Suppress("UNCHECKED_CAST")
            val friendIds = doc.get("friends") as? List<String> ?: emptyList()
            if (friendIds.isEmpty()) {
                Toast.makeText(this, "No friends to add", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }
            
            db.collection("users").whereIn("uid", friendIds).get().addOnSuccessListener { snapshot ->
                val friends = snapshot.toObjects(User::class.java)
                binding.rvFriends.layoutManager = LinearLayoutManager(this)
                binding.rvFriends.adapter = SelectFriendsAdapter(friends) { friend, isSelected ->
                    if (isSelected) selectedMemberIds.add(friend.uid) else selectedMemberIds.remove(friend.uid)
                }
            }
        }
    }

    private fun createGroup(name: String) {
        val uid = auth.currentUser?.uid ?: return
        val groupId = db.collection("groups").document().id
        val allMemberIds = selectedMemberIds.toMutableList()
        allMemberIds.add(uid)

        val group = Group(
            id = groupId,
            name = name,
            memberIds = allMemberIds,
            adminId = uid,
            lastMessage = "Group created",
            lastMessageTimestamp = System.currentTimeMillis(),
            groupImageUrl = groupImageBase64
        )

        db.collection("groups").document(groupId).set(group)
            .addOnSuccessListener {
                Toast.makeText(this, "Group created!", Toast.LENGTH_SHORT).show()
                finish()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to create group", Toast.LENGTH_SHORT).show()
            }
    }
}
