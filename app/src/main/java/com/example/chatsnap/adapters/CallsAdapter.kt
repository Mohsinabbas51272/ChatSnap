package com.example.chatsnap.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.chatsnap.databinding.ItemCallBinding
import com.example.chatsnap.models.Call
import com.google.firebase.auth.FirebaseAuth
import java.text.SimpleDateFormat
import java.util.*

class CallsAdapter(
    private var calls: List<Call>,
    private val currentUid: String,
    private val onCallClick: (Call) -> Unit
) : RecyclerView.Adapter<CallsAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemCallBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCallBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val call = calls[position]
        val isOutgoing = call.callerId == currentUid
        
        holder.binding.tvPartnerName.text = if (isOutgoing) call.receiverName else call.callerName
        
        val typeStr = if (call.type.equals("video", ignoreCase = true)) "Video Call" else "Audio Call"
        val timeStr = formatTime(call.timestamp)
        holder.binding.tvCallInfo.text = "$typeStr • $timeStr"
        
        // Icon logic
        if (call.status == "missed" || call.status == "rejected") {
            holder.binding.ivCallIcon.setImageResource(android.R.drawable.sym_call_missed)
        } else if (isOutgoing) {
            holder.binding.ivCallIcon.setImageResource(android.R.drawable.sym_call_outgoing)
        } else {
            holder.binding.ivCallIcon.setImageResource(android.R.drawable.sym_call_incoming)
        }

        holder.binding.root.setOnClickListener { onCallClick(call) }
        holder.binding.btnCallAgain.setOnClickListener { onCallClick(call) }
    }

    override fun getItemCount(): Int = calls.size

    fun updateData(newCalls: List<Call>) {
        calls = newCalls
        notifyDataSetChanged()
    }

    private fun formatTime(timestamp: Long): String {
        val now = Calendar.getInstance()
        val time = Calendar.getInstance().apply { timeInMillis = timestamp }
        return if (now.get(Calendar.DATE) == time.get(Calendar.DATE)) {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        } else {
            SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(timestamp))
        }
    }
}
