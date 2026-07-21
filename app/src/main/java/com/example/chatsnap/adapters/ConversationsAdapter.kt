package com.example.chatsnap.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.chatsnap.R
import com.example.chatsnap.databinding.ItemConversationBinding
import com.example.chatsnap.models.Conversation
import java.text.SimpleDateFormat
import java.util.*

class ConversationsAdapter(
    private var conversations: List<Conversation>,
    private val onClick: (Conversation) -> Unit,
    private val onLongClick: ((Conversation) -> Unit)? = null
) : RecyclerView.Adapter<ConversationsAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemConversationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConversationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val conversation = conversations[position]
        val context = holder.itemView.context
        
        holder.binding.tvPartnerName.text = if (conversation.isPartnerAdmin) "ChatSnap" else conversation.partnerName
        
        // Handle last message display logic
        val displayMessage = when (conversation.lastMessageType) {
            "IMAGE", "VIDEO" -> if (conversation.lastMessageViewed) "Opened" else "New Photo"
            "SNAP" -> if (conversation.lastMessageViewed) "Opened" else "New Snap"
            "POLL" -> "Poll: ${conversation.lastMessage}"
            "AUDIO" -> "Voice Message"
            "LOCATION" -> "Location"
            else -> conversation.lastMessage
        }
        holder.binding.tvLastMessage.text = displayMessage
        holder.binding.tvTimestamp.text = formatTime(conversation.lastMessageTimestamp)
        
        // Profile Image loading
        if (conversation.isPartnerAdmin) {
            holder.binding.ivPartnerProfile.setImageResource(R.drawable.ic_app_logo)
        } else {
            val photo = conversation.partnerPhotoUrl
            if (!photo.isNullOrEmpty()) {
                if (photo.startsWith("data:image") || photo.length > 1000) {
                    try {
                        val cleanBase64 = photo.substringAfter(",")
                        val decodedString: ByteArray = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                        val decodedByte = android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                        holder.binding.ivPartnerProfile.setImageBitmap(decodedByte)
                    } catch (e: Exception) {
                        holder.binding.ivPartnerProfile.setImageResource(R.drawable.ic_launcher_foreground)
                    }
                } else {
                    holder.binding.ivPartnerProfile.load(photo) {
                        placeholder(R.drawable.ic_launcher_foreground)
                        crossfade(true)
                    }
                }
            } else {
                holder.binding.ivPartnerProfile.setImageResource(R.drawable.ic_launcher_foreground)
            }
        }
        
        holder.binding.viewOnlineStatus.visibility = if (conversation.isOnline) View.VISIBLE else View.GONE
        holder.binding.ivPinned.visibility = if (conversation.isPinned) View.VISIBLE else View.GONE
        
        // Streak Display
        if (conversation.streakCount > 0) {
            holder.binding.tvStreak.visibility = View.VISIBLE
            val streakText = if (conversation.isExpiringSoon) "⌛ 🔥 ${conversation.streakCount}" else "🔥 ${conversation.streakCount}"
            holder.binding.tvStreak.text = streakText
        } else {
            holder.binding.tvStreak.visibility = View.GONE
        }
        
        // Unread and Status indicators
        if (conversation.unreadCount > 0) {
            holder.binding.viewUnreadIndicator.visibility = View.VISIBLE
            holder.binding.tvUnreadCount.visibility = View.VISIBLE
            holder.binding.tvUnreadCount.text = conversation.unreadCount.toString()
            holder.binding.tvPartnerName.setTypeface(null, android.graphics.Typeface.BOLD)
            holder.binding.tvLastMessage.setTextColor(context.getColor(R.color.primary))
            holder.binding.ivMessageStatus.visibility = View.GONE
        } else {
            holder.binding.viewUnreadIndicator.visibility = View.GONE
            holder.binding.tvUnreadCount.visibility = View.GONE
            holder.binding.tvPartnerName.setTypeface(null, android.graphics.Typeface.NORMAL)
            holder.binding.tvLastMessage.setTextColor(context.getColor(android.R.color.darker_gray))
            
            // Show status icon (Delivered/Viewed) for the last message you sent
            // This is a simplified logic: if viewed is true, show blue double tick, else grey
            holder.binding.ivMessageStatus.visibility = View.VISIBLE
            if (conversation.lastMessageViewed) {
                holder.binding.ivMessageStatus.setImageResource(R.drawable.ic_tick_double)
                holder.binding.ivMessageStatus.imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.primary))
            } else {
                holder.binding.ivMessageStatus.setImageResource(R.drawable.ic_tick_single)
                holder.binding.ivMessageStatus.imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(android.R.color.darker_gray))
            }
        }
        
        holder.itemView.setOnClickListener { onClick(conversation) }
        holder.itemView.setOnLongClickListener { onLongClick?.invoke(conversation); true }
    }

    override fun getItemCount(): Int = conversations.size

    fun getConversationsList(): List<Conversation> = conversations

    fun updateData(newConversations: List<Conversation>) {
        conversations = newConversations
        notifyDataSetChanged()
    }

    private fun formatTime(timestamp: Long): String {
        if (timestamp == 0L) return ""
        val now = Calendar.getInstance()
        val time = Calendar.getInstance().apply { timeInMillis = timestamp }
        
        return if (now.get(Calendar.DATE) == time.get(Calendar.DATE)) {
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
        } else {
            SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
        }
    }
}
