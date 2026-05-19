package com.example.chatsnap.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.chatsnap.R
import com.example.chatsnap.databinding.ItemStoryGroupBinding
import com.example.chatsnap.models.Story
import com.google.firebase.auth.FirebaseAuth

data class GroupedStory(
    val userId: String,
    val displayName: String,
    val userPhoto: String?,
    val stories: List<Story>,
    val hasUnread: Boolean
)

class StoryGroupAdapter(
    private var groups: List<GroupedStory>,
    private val onGroupClick: (GroupedStory) -> Unit
) : RecyclerView.Adapter<StoryGroupAdapter.ViewHolder>() {

    private val currentUid = FirebaseAuth.getInstance().currentUser?.uid

    class ViewHolder(val binding: ItemStoryGroupBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemStoryGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = groups[position]
        val isMe = group.userId == currentUid
        
        holder.binding.tvUserName.text = if (isMe) "My Story" else group.displayName.split(" ")[0]
        
        // Prioritize actual uploaded story media over the user's DP to show correct media thumbnail
        val photoUrl = group.stories.lastOrNull()?.mediaUrl ?: group.userPhoto
        
        if (!photoUrl.isNullOrEmpty()) {
            if (photoUrl.startsWith("data:image") || photoUrl.length > 500) {
                try {
                    val cleanBase64 = if (photoUrl.contains(",")) photoUrl.substringAfter(",") else photoUrl
                    val decodedString = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                    holder.binding.ivUserAvatar.setImageBitmap(bitmap)
                } catch (e: Exception) {
                    holder.binding.ivUserAvatar.setImageResource(R.drawable.ic_launcher_foreground)
                }
            } else {
                holder.binding.ivUserAvatar.load(photoUrl) {
                    crossfade(true)
                    placeholder(R.drawable.ic_launcher_foreground)
                    error(R.drawable.ic_launcher_foreground)
                }
            }
        } else {
            holder.binding.ivUserAvatar.setImageResource(R.drawable.ic_launcher_foreground)
        }

        // Snapchat style: Unread ring is visible only if there are unread stories and it's NOT the user's own story
        // Or if it IS the user's story and they just posted (optional). 
        // Let's stick to showing it for others.
        holder.binding.viewUnreadRing.visibility = if (group.hasUnread && !isMe) View.VISIBLE else View.GONE
        
        // Show '+' icon only on user's own story circle
        holder.binding.ivAddStory.visibility = if (isMe) View.VISIBLE else View.GONE
        
        holder.itemView.setOnClickListener { onGroupClick(group) }
    }

    override fun getItemCount() = groups.size

    fun updateData(newGroups: List<GroupedStory>) {
        groups = newGroups
        notifyDataSetChanged()
    }
}
