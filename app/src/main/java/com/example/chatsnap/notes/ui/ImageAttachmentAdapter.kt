package com.example.chatsnap.notes.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.chatsnap.databinding.ItemAttachmentImageBinding

class ImageAttachmentAdapter(
    private val onImageClicked: (String) -> Unit,
    private val onRemoveClicked: (String) -> Unit
) : RecyclerView.Adapter<ImageAttachmentAdapter.ImageViewHolder>() {

    private val images = mutableListOf<String>()

    fun submitList(newImages: List<String>) {
        if (images != newImages) {
            images.clear()
            images.addAll(newImages)
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val binding = ItemAttachmentImageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ImageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        holder.bind(images[position])
    }

    override fun getItemCount(): Int = images.size

    inner class ImageViewHolder(private val binding: ItemAttachmentImageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(uriString: String) {
            binding.ivThumbnail.load(uriString) {
                crossfade(true)
            }
            binding.root.setOnClickListener {
                onImageClicked(uriString)
            }
            binding.btnRemoveImage.setOnClickListener {
                onRemoveClicked(uriString)
            }
        }
    }
}
