package com.example.chatsnap.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.chatsnap.databinding.ItemSelectFriendBinding
import com.example.chatsnap.models.User

class SelectFriendsAdapter(
    private val friends: List<User>,
    private val onSelected: (User, Boolean) -> Unit
) : RecyclerView.Adapter<SelectFriendsAdapter.ViewHolder>() {

    private val selectedIds = mutableSetOf<String>()

    class ViewHolder(val binding: ItemSelectFriendBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSelectFriendBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val friend = friends[position]
        holder.binding.tvName.text = friend.name
        holder.binding.ivProfile.load(friend.profileImageUrl)
        
        holder.binding.checkBox.setOnCheckedChangeListener(null)
        holder.binding.checkBox.isChecked = selectedIds.contains(friend.uid)
        
        holder.binding.checkBox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selectedIds.add(friend.uid) else selectedIds.remove(friend.uid)
            onSelected(friend, isChecked)
        }
        
        holder.itemView.setOnClickListener {
            holder.binding.checkBox.isChecked = !holder.binding.checkBox.isChecked
        }
    }

    override fun getItemCount() = friends.size
}
