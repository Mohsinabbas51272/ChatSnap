package com.example.chatsnap.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.chatsnap.databinding.ItemUserBinding
import com.example.chatsnap.models.User

class UserAdapter(
    private var users: List<User>,
    private var relationshipStatus: Map<String, String> = emptyMap(), // uid -> status
    private var currentFriends: List<String> = emptyList(),
    private val horizontal: Boolean = false,
    private val onAddClick: (User) -> Unit,
    private val onChatClick: (User) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class ViewHolder(val binding: androidx.viewbinding.ViewBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (horizontal) {
            val binding = com.example.chatsnap.databinding.ItemUserHorizontalBinding.inflate(inflater, parent, false)
            ViewHolder(binding)
        } else {
            val binding = com.example.chatsnap.databinding.ItemUserBinding.inflate(inflater, parent, false)
            ViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val user = users[position]
        val vh = holder as ViewHolder
        
        if (horizontal) {
            val binding = vh.binding as com.example.chatsnap.databinding.ItemUserHorizontalBinding
            binding.tvUserName.text = user.name
            loadUserImage(user.profileImageUrl, binding.ivUserProfile)
            binding.root.setOnClickListener { onChatClick(user) }
        } else {
            val binding = vh.binding as com.example.chatsnap.databinding.ItemUserBinding
            binding.tvUserName.text = user.name
            
            val mutualCount = user.friends.intersect(currentFriends.toSet()).size
            val subText = when {
                mutualCount > 0 -> "$mutualCount mutual friends"
                !user.username.isNullOrEmpty() -> "@${user.username}"
                !user.bio.isNullOrEmpty() -> user.bio
                else -> "ChatSnap Member"
            }
            binding.tvUserEmail.text = subText
            
            // Show Admin Badge
            if (user.isAdmin) {
                binding.tvAdminBadge.visibility = android.view.View.VISIBLE
            } else {
                binding.tvAdminBadge.visibility = android.view.View.GONE
            }
            
            loadUserImage(user.profileImageUrl, binding.ivUserProfile)
            
            // For Admin/Management: Allow clicking the whole row
            binding.root.setOnClickListener { onChatClick(user) }
            
            val status = relationshipStatus[user.uid] ?: "NONE"
            when (status) {
                "FRIEND" -> {
                    binding.btnAddFriend.visibility = android.view.View.GONE
                    binding.btnChat.visibility = android.view.View.VISIBLE
                    binding.btnChat.setOnClickListener { onChatClick(user) }
                }
                "SENT" -> {
                    binding.btnAddFriend.visibility = android.view.View.VISIBLE
                    binding.btnChat.visibility = android.view.View.GONE
                    binding.btnAddFriend.text = "Requested"
                    binding.btnAddFriend.isEnabled = false
                }
                else -> {
                    binding.btnAddFriend.visibility = android.view.View.VISIBLE
                    binding.btnChat.visibility = android.view.View.GONE
                    binding.btnAddFriend.text = "Add"
                    binding.btnAddFriend.isEnabled = true
                    binding.btnAddFriend.setOnClickListener { onAddClick(user) }
                }
            }
        }
    }

    private fun loadUserImage(photo: String, imageView: android.widget.ImageView) {
        if (photo.isEmpty()) {
            imageView.setImageResource(com.example.chatsnap.R.drawable.ic_launcher_background)
            return
        }
        if (photo.startsWith("data:image") || photo.length > 1000) {
            try {
                val cleanBase64 = photo.substringAfter(",")
                val decodedString: ByteArray = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                val decodedByte = android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                imageView.setImageBitmap(decodedByte)
            } catch (e: Exception) {
                imageView.load(photo)
            }
        } else {
            imageView.load(photo)
        }
    }

    override fun getItemCount(): Int = users.size

    fun updateData(newUsers: List<User>, newStatus: Map<String, String>? = null, newFriends: List<String>? = null) {
        users = newUsers
        if (newStatus != null) relationshipStatus = newStatus
        if (newFriends != null) currentFriends = newFriends
        notifyDataSetChanged()
    }

    fun updateStatus(newStatus: Map<String, String>) {
        relationshipStatus = newStatus
        notifyDataSetChanged()
    }
}
