package com.example.chatsnap.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.chatsnap.databinding.ItemFriendRequestBinding
import com.example.chatsnap.models.FriendRequest

class FriendRequestAdapter(
    private var requests: List<FriendRequest>,
    private val onAccept: (FriendRequest) -> Unit,
    private val onReject: (FriendRequest) -> Unit
) : RecyclerView.Adapter<FriendRequestAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemFriendRequestBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFriendRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val request = requests[position]
        val sourceText = if (request.source.isNotEmpty()) "\n(From ${request.source})" else ""
        holder.binding.tvRequesterName.text = "${request.fromName}$sourceText"
        holder.binding.btnAccept.setOnClickListener { onAccept(request) }
        holder.binding.btnReject.setOnClickListener { onReject(request) }
    }

    override fun getItemCount() = requests.size

    fun updateData(newRequests: List<FriendRequest>) {
        requests = newRequests
        notifyDataSetChanged()
    }
}
