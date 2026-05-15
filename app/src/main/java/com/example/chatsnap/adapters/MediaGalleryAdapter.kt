package com.example.chatsnap.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.chatsnap.databinding.ItemMediaGalleryBinding
import com.example.chatsnap.models.Message

class MediaGalleryAdapter(
    private var mediaList: List<Message>,
    private val onItemClick: (Message) -> Unit
) : RecyclerView.Adapter<MediaGalleryAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemMediaGalleryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMediaGalleryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = mediaList[position]
        
        val url = item.content // For IMAGE/VIDEO types, content is usually the URL or base64
        
        if (url.startsWith("data:image")) {
            val cleanBase64 = url.substringAfter(",")
            val decodedString = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
            holder.binding.ivMedia.setImageBitmap(bitmap)
        } else {
            holder.binding.ivMedia.load(url) {
                crossfade(true)
                placeholder(android.R.drawable.progress_indeterminate_horizontal)
            }
        }

        holder.binding.ivVideoIcon.visibility = if (item.type == "VIDEO") View.VISIBLE else View.GONE
        holder.binding.ivSnapIcon.visibility = if (item.type == "SNAP") View.VISIBLE else View.GONE

        holder.binding.root.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount(): Int = mediaList.size

    fun updateData(newList: List<Message>) {
        mediaList = newList
        notifyDataSetChanged()
    }
}
