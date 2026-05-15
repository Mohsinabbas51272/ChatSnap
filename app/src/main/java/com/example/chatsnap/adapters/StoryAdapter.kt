package com.example.chatsnap.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.chatsnap.databinding.ItemStoryAdminBinding
import com.example.chatsnap.models.Story
import java.text.SimpleDateFormat
import java.util.*

class StoryAdapter(
    private var stories: List<Story>,
    private val onDeleteClick: (Story) -> Unit
) : RecyclerView.Adapter<StoryAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemStoryAdminBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemStoryAdminBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val story = stories[position]
        holder.binding.tvUserName.text = story.displayName
        
        // Load thumbnail
        if (story.mediaUrl.startsWith("data:image")) {
             val cleanBase64 = story.mediaUrl.substringAfter(",")
             val decodedString = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
             val bitmap = android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
             holder.binding.ivStoryThumb.setImageBitmap(bitmap)
        } else {
             holder.binding.ivStoryThumb.load(story.mediaUrl)
        }

        val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
        val date = when (val ts = story.timestamp) {
            is com.google.firebase.Timestamp -> ts.toDate()
            is Long -> Date(ts)
            else -> null
        }
        holder.binding.tvTimestamp.text = date?.let { sdf.format(it) } ?: "Unknown"
        
        holder.binding.tvViews.text = "Views: ${story.viewCount}"

        holder.binding.btnDeleteStory.setOnClickListener {
            onDeleteClick(story)
        }
    }

    override fun getItemCount(): Int = stories.size

    fun updateData(newStories: List<Story>) {
        stories = newStories
        notifyDataSetChanged()
    }
}