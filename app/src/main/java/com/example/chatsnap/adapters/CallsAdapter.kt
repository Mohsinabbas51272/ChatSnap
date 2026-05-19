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
    private var followedFriends: List<String> = emptyList(),
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
        val partnerId = if (isOutgoing) call.receiverId else call.callerId
        
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

        // Color followed friend missed call label in red
        val isFollowedFriend = followedFriends.contains(partnerId)
        val isMissed = call.status == "missed"
        
        if (isMissed && isFollowedFriend) {
            holder.binding.tvPartnerName.setTextColor(android.graphics.Color.RED)
            holder.binding.tvCallInfo.setTextColor(android.graphics.Color.RED)
            holder.binding.ivCallIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.RED)
        } else {
            val typedValue = android.util.TypedValue()
            holder.binding.root.context.theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
            holder.binding.tvPartnerName.setTextColor(typedValue.data)
            
            val typedValueSec = android.util.TypedValue()
            holder.binding.root.context.theme.resolveAttribute(android.R.attr.textColorSecondary, typedValueSec, true)
            holder.binding.tvCallInfo.setTextColor(typedValueSec.data)
            
            holder.binding.ivCallIcon.imageTintList = null
        }

        holder.binding.root.setOnClickListener { onCallClick(call) }
        holder.binding.btnCallAgain.setOnClickListener { onCallClick(call) }
    }

    override fun getItemCount(): Int = calls.size

    fun updateData(newCalls: List<Call>, newFollowed: List<String> = followedFriends) {
        calls = newCalls
        followedFriends = newFollowed
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
