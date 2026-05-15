package com.example.chatsnap.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.chatsnap.databinding.ItemSupportRequestBinding
import com.example.chatsnap.models.SupportRequest
import java.text.SimpleDateFormat
import java.util.*

class SupportAdapter(
    private var requests: List<SupportRequest>,
    private val isAdmin: Boolean = false,
    private val onItemClick: (SupportRequest) -> Unit = {}
) : RecyclerView.Adapter<SupportAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemSupportRequestBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSupportRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val request = requests[position]
        holder.binding.tvSupportTitle.text = if (isAdmin) "${request.userName}: ${request.title}" else request.title
        holder.binding.tvSupportMessage.text = request.message
        
        val status = request.status.uppercase()
        holder.binding.tvSupportStatus.text = status
        
        // Dynamic background for status
        val statusBg = when (status) {
            "OPEN" -> android.graphics.Color.parseColor("#2563EB")
            "IN-PROGRESS" -> android.graphics.Color.parseColor("#F59E0B")
            "RESOLVED" -> android.graphics.Color.parseColor("#10B981")
            else -> android.graphics.Color.parseColor("#6B7280")
        }
        holder.binding.tvSupportStatus.background.setTint(statusBg)

        if (!request.response.isNullOrEmpty()) {
            holder.binding.layoutReply.visibility = View.VISIBLE
            holder.binding.tvSupportResponse.text = request.response
        } else {
            holder.binding.layoutReply.visibility = View.GONE
        }

        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        holder.binding.tvSupportDate.text = sdf.format(request.timestamp.toDate())

        holder.itemView.setOnClickListener { onItemClick(request) }
    }

    override fun getItemCount(): Int = requests.size

    fun updateData(newRequests: List<SupportRequest>) {
        requests = newRequests
        notifyDataSetChanged()
    }
}
