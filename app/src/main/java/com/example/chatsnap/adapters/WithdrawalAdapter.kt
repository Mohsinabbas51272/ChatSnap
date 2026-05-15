package com.example.chatsnap.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.chatsnap.databinding.ItemSupportRequestBinding // Reuse similar layout or create new
import com.example.chatsnap.models.Withdrawal
import java.text.SimpleDateFormat
import java.util.*

class WithdrawalAdapter(
    private var withdrawals: List<Withdrawal>,
    private val onFulfillClick: (Withdrawal) -> Unit
) : RecyclerView.Adapter<WithdrawalAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemSupportRequestBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSupportRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = withdrawals[position]
        holder.binding.tvSupportTitle.text = "${item.userDisplayName}: ${item.amount} Coins"
        holder.binding.tvSupportMessage.text = "Account: ${item.accountDetails}"
        
        holder.binding.tvSupportStatus.text = item.status
        val colorStr = when(item.status) {
            "PENDING" -> "#F59E0B" // Orange
            "COMPLETED" -> "#10B981" // Green
            "REJECTED" -> "#EF4444" // Red
            else -> "#6B7280" // Gray
        }
        holder.binding.tvSupportStatus.background.setTint(android.graphics.Color.parseColor(colorStr))

        val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        val dateStr = try {
            item.timestamp.toDate()?.let { sdf.format(it) } ?: "Unknown"
        } catch (e: Exception) {
            "Invalid Date"
        }
        holder.binding.tvSupportDate.text = dateStr

        holder.binding.root.setOnClickListener {
            if (item.status == "PENDING") onFulfillClick(item)
        }
    }

    override fun getItemCount(): Int = withdrawals.size

    fun updateData(newList: List<Withdrawal>) {
        withdrawals = newList
        notifyDataSetChanged()
    }
}
