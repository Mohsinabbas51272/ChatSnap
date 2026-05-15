package com.example.chatsnap.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.example.chatsnap.R
import com.example.chatsnap.databinding.ItemStoryViewerBinding
import com.example.chatsnap.models.StoryViewerInfo
import java.util.concurrent.TimeUnit

class StoryViewersAdapter(private val viewers: List<StoryViewerInfo>) :
    RecyclerView.Adapter<StoryViewersAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemStoryViewerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemStoryViewerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val viewer = viewers[position]
        holder.binding.tvRank.text = (position + 1).toString()
        holder.binding.tvViewerName.text = viewer.displayName
        holder.binding.tvUserViewCount.text = viewer.viewCount.toString()
        holder.binding.tvViewsLabel.text = if (viewer.viewCount > 1) "views" else "view"
        
        holder.binding.tvTime.text = formatTime(viewer.lastViewed)

        // Fetching profile image usually requires a secondary call or having it in StoryViewerInfo
        // For now, let's use a placeholder or assume we might add it later
        // holder.binding.ivViewerImage.load(R.drawable.ic_launcher_foreground)
    }

    private fun formatTime(timestampStr: String): String {
        return try {
            val timestamp = timestampStr.toLong()
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            
            when {
                diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
                diff < TimeUnit.HOURS.toMillis(1) -> "${diff / 60000}m ago"
                diff < TimeUnit.DAYS.toMillis(1) -> "${diff / 3600000}h ago"
                else -> "Long ago"
            }
        } catch (e: Exception) {
            "..."
        }
    }

    override fun getItemCount(): Int = viewers.size
}
