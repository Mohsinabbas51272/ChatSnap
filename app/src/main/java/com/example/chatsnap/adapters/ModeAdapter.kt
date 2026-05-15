package com.example.chatsnap.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.chatsnap.databinding.ItemUserModeBinding
import com.example.chatsnap.models.UserMode

import coil.load

class ModeAdapter(private val modes: List<UserMode>) : RecyclerView.Adapter<ModeAdapter.ModeViewHolder>() {

    class ModeViewHolder(val binding: ItemUserModeBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModeViewHolder {
        val binding = ItemUserModeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ModeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ModeViewHolder, position: Int) {
        val mode = modes[position]
        holder.binding.tvModeEmoji.text = mode.emoji
        holder.binding.tvModeName.text = mode.modeText
        
        val photoUrl = mode.photoUrl
        if (!photoUrl.isNullOrEmpty()) {
            if (photoUrl.startsWith("data:image") || photoUrl.length > 1000) {
                try {
                    val cleanBase64 = photoUrl.substringAfter(",")
                    val decodedString = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                    val decodedByte = android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                    holder.binding.ivModeUser.setImageBitmap(decodedByte)
                } catch (e: Exception) {
                    holder.binding.ivModeUser.load(photoUrl)
                }
            } else {
                holder.binding.ivModeUser.load(photoUrl)
            }
        } else {
            holder.binding.ivModeUser.setImageResource(com.example.chatsnap.R.drawable.ic_launcher_foreground)
        }
    }

    override fun getItemCount() = modes.size
}