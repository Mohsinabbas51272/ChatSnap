package com.example.chatsnap.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.chatsnap.R
import com.example.chatsnap.databinding.ItemModeStatusBinding
import com.example.chatsnap.models.User
import java.text.SimpleDateFormat
import java.util.*

class ModeStatusAdapter(
    private var users: List<User>,
    private val onClick: (User) -> Unit
) : RecyclerView.Adapter<ModeStatusAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemModeStatusBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemModeStatusBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val user = users[position]
        holder.binding.tvName.text = user.name
        holder.binding.tvStatus.text = user.status ?: "Hey there! I am using ChatSnap"
        
        // Load image
        val photo = user.profileImageUrl
        if (!photo.isNullOrEmpty()) {
            if (photo.startsWith("data:image") || photo.length > 1000) {
                try {
                    val cleanBase64 = photo.substringAfter(",")
                    val decodedString: ByteArray = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                    val decodedByte = android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                    holder.binding.ivProfile.setImageBitmap(decodedByte)
                } catch (e: Exception) {
                    holder.binding.ivProfile.setImageResource(R.drawable.ic_launcher_foreground)
                }
            } else {
                holder.binding.ivProfile.load(photo) {
                    placeholder(R.drawable.ic_launcher_foreground)
                    error(R.drawable.ic_launcher_foreground)
                }
            }
        } else {
            holder.binding.ivProfile.setImageResource(R.drawable.ic_launcher_foreground)
        }

        // Format time
        user.lastStatusUpdate?.let {
            val date = Date(it)
            val format = SimpleDateFormat("h:mm a", Locale.getDefault())
            holder.binding.tvTime.text = format.format(date)
        }

        holder.itemView.setOnClickListener { onClick(user) }
    }

    override fun getItemCount() = users.size

    fun updateData(newUsers: List<User>) {
        users = newUsers
        notifyDataSetChanged()
    }
}
