package com.example.chatsnap.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.chatsnap.databinding.ItemWithdrawalBinding
import com.example.chatsnap.models.Withdrawal
import java.text.SimpleDateFormat
import java.util.*

class WithdrawalAdapter(
    private var withdrawals: List<Withdrawal>,
    private val onFulfillClick: (Withdrawal) -> Unit
) : RecyclerView.Adapter<WithdrawalAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemWithdrawalBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemWithdrawalBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = withdrawals[position]
        holder.binding.tvWithdrawalUser.text = item.userDisplayName
        holder.binding.tvWithdrawalCoins.text = "${item.amount} Coins"
        
        val details = item.accountDetails
        var method = "Unknown"
        var name = "Unknown"
        var number = "Unknown"
        
        try {
            if (details.startsWith("[")) {
                method = details.substringAfter("[").substringBefore("]")
                val rest = details.substringAfter("]")
                if (rest.contains("-")) {
                    name = rest.substringBefore("-").trim()
                    number = rest.substringAfter("-").trim()
                } else {
                    name = rest.trim()
                }
            } else {
                name = details
            }
        } catch (e: Exception) {
            name = details
        }

        holder.binding.tvWithdrawalMethod.text = method
        val methodColor = if (method.lowercase().contains("easypaisa")) "#22C55E" else "#F59E0B"
        holder.binding.tvWithdrawalMethod.background?.setTint(android.graphics.Color.parseColor(methodColor))

        holder.binding.tvWithdrawalAccountName.text = name
        holder.binding.tvWithdrawalAccountNumber.text = number

        holder.binding.tvWithdrawalStatus.text = item.status
        val statusColor = when(item.status) {
            "PENDING" -> "#F59E0B"
            "COMPLETED" -> "#10B981"
            "REJECTED" -> "#EF4444"
            else -> "#6B7280"
        }
        holder.binding.tvWithdrawalStatus.background?.setTint(android.graphics.Color.parseColor(statusColor))

        val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        val dateStr = try {
            item.timestamp.toDate()?.let { sdf.format(it) } ?: "Unknown"
        } catch (e: Exception) {
            "Invalid Date"
        }
        holder.binding.tvWithdrawalDate.text = dateStr

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
