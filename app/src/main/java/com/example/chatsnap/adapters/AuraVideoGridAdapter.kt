package com.example.chatsnap.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.chatsnap.R
import com.example.chatsnap.databinding.ItemAuraVideoGridBinding
import com.example.chatsnap.models.AuraVideo

class AuraVideoGridAdapter(
    private var videos: List<AuraVideo>,
    private val onVideoClick: (AuraVideo, Int) -> Unit
) : RecyclerView.Adapter<AuraVideoGridAdapter.GridViewHolder>() {

    class GridViewHolder(val binding: ItemAuraVideoGridBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GridViewHolder {
        val binding = ItemAuraVideoGridBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return GridViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GridViewHolder, position: Int) {
        val video = videos[position]
        holder.binding.tvGridViewCount.text = formatCount(video.viewCount)

        val url = video.videoUrl
        if (url.startsWith("data:image") || url.startsWith("http")) {
            holder.binding.ivGridThumbnail.load(url) {
                placeholder(R.drawable.bg_top_gradient)
                error(R.drawable.bg_top_gradient)
            }
        } else {
            holder.binding.ivGridThumbnail.setImageResource(R.drawable.bg_top_gradient)
        }

        holder.itemView.setOnClickListener {
            onVideoClick(video, position)
        }
    }

    override fun getItemCount(): Int = videos.size

    fun updateData(newVideos: List<AuraVideo>) {
        videos = newVideos
        notifyDataSetChanged()
    }

    private fun formatCount(count: Long): String {
        return when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }
}
