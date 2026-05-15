package com.example.chatsnap.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.example.chatsnap.R
import com.example.chatsnap.databinding.ItemHighlightBinding
import com.example.chatsnap.models.Highlight

class HighlightAdapter(
    private var highlights: List<Highlight>,
    private val onItemClick: (Highlight) -> Unit
) : RecyclerView.Adapter<HighlightAdapter.HighlightViewHolder>() {

    class HighlightViewHolder(val binding: ItemHighlightBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HighlightViewHolder {
        val binding = ItemHighlightBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HighlightViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HighlightViewHolder, position: Int) {
        val highlight = highlights[position]
        val photoUrl = highlight.mediaUrl
        if (!photoUrl.isNullOrEmpty() && (photoUrl.startsWith("data:image") || photoUrl.length > 1000)) {
            try {
                val cleanBase64 = if (photoUrl.contains(",")) photoUrl.substringAfter(",") else photoUrl
                val decodedString = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                holder.binding.ivHighlight.setImageBitmap(bitmap)
            } catch (e: Exception) {
                holder.binding.ivHighlight.setImageResource(R.drawable.ic_launcher_foreground)
            }
        } else {
            holder.binding.ivHighlight.load(photoUrl) {
                crossfade(true)
                transformations(CircleCropTransformation())
                placeholder(R.drawable.ic_launcher_foreground)
                error(R.drawable.ic_launcher_foreground)
            }
        }
        holder.binding.tvHighlightName.text = highlight.displayName
        holder.binding.root.setOnClickListener { onItemClick(highlight) }
    }

    override fun getItemCount(): Int = highlights.size

    fun updateData(newHighlights: List<Highlight>) {
        highlights = newHighlights
        notifyDataSetChanged()
    }
}
