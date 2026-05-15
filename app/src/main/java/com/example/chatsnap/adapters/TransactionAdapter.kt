package com.example.chatsnap.adapters

import android.app.AlertDialog
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.chatsnap.R
import com.example.chatsnap.databinding.ItemTransactionBinding
import com.example.chatsnap.models.Transaction
import java.text.SimpleDateFormat
import java.util.*

class TransactionAdapter(private var transactions: List<Transaction>) :
    RecyclerView.Adapter<TransactionAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemTransactionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTransactionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tx = transactions[position]
        val isEarn = tx.type == "earn"
        
        holder.binding.tvTxSource.text = tx.source ?: if (isEarn) "Earned" else "Withdrawal"
        
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        val formattedDate = sdf.format(Date(tx.timestamp))
        holder.binding.tvTxDate.text = formattedDate
        
        holder.binding.tvTxRef.text = if (tx.referenceId.isNotEmpty()) tx.referenceId else "#TXN-${tx.id.takeLast(6).uppercase()}"
        
        if (isEarn) {
            holder.binding.tvTxAmount.text = "+${tx.amount}"
            holder.binding.tvTxAmount.setTextColor(Color.parseColor("#34C759"))
            holder.binding.viewTypeIcon.setBackgroundColor(Color.parseColor("#E8F5E9")) // Light Green
            holder.binding.tvTxStatus.visibility = View.GONE
        } else {
            holder.binding.tvTxAmount.text = "${tx.amount}" // Amount is already negative in model
            holder.binding.tvTxAmount.setTextColor(Color.parseColor("#FF3B30"))
            holder.binding.viewTypeIcon.setBackgroundColor(Color.parseColor("#FFEBEE")) // Light Red
            
            val status = tx.status?.lowercase() ?: "pending"
            holder.binding.tvTxStatus.visibility = View.VISIBLE
            holder.binding.tvTxStatus.text = status.replaceFirstChar { it.uppercase() }
            
            when (status) {
                "pending" -> {
                    holder.binding.tvTxStatus.setTextColor(Color.parseColor("#FF9500")) // Orange
                    holder.binding.tvTxStatus.setBackgroundColor(Color.parseColor("#FFF4E5"))
                }
                "completed", "approved" -> {
                    holder.binding.tvTxStatus.setTextColor(Color.parseColor("#34C759")) // Green
                    holder.binding.tvTxStatus.setBackgroundColor(Color.parseColor("#E8F5E9"))
                }
                "failed", "rejected" -> {
                    holder.binding.tvTxStatus.setTextColor(Color.parseColor("#FF3B30")) // Red
                    holder.binding.tvTxStatus.setBackgroundColor(Color.parseColor("#FFEBEE"))
                }
            }
        }
        
        holder.itemView.setOnClickListener {
            showReceiptDialog(holder.itemView.context, tx, formattedDate)
        }
    }
    
    private fun showReceiptDialog(context: android.content.Context, tx: Transaction, date: String) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_receipt, null)
        val dialog = AlertDialog.Builder(context, R.style.Theme_ChatSnap_Dialog_Transparent)
            .setView(dialogView)
            .create()
            
        dialogView.findViewById<TextView>(R.id.tvReceiptTitle).text = if (tx.type == "earn") "Earning Details" else "Withdrawal Receipt"
        dialogView.findViewById<TextView>(R.id.tvReceiptAmount).text = "${tx.amount} Coins"
        dialogView.findViewById<TextView>(R.id.tvReceiptAmount).setTextColor(Color.parseColor(if (tx.type == "earn") "#34C759" else "#FF3B30"))
        
        val details = """
            Transaction ID: ${if (tx.referenceId.isNotEmpty()) tx.referenceId else "TXN-${tx.id.takeLast(6).uppercase()}"}
            Date: $date
            Source: ${tx.source ?: "N/A"}
            Status: ${tx.status?.replaceFirstChar { it.uppercase() } ?: "Pending"}
            ${if (tx.accountDetails != null) "Account: ${tx.accountDetails}" else ""}
        """.trimIndent()
        
        dialogView.findViewById<TextView>(R.id.tvReceiptDetails).text = details
        
        val timelineLayout = dialogView.findViewById<View>(R.id.layoutTimeline)
        if (tx.type == "withdraw") {
            timelineLayout.visibility = View.VISIBLE
            val tvStep2 = dialogView.findViewById<TextView>(R.id.tvStep2)
            val tvStep3 = dialogView.findViewById<TextView>(R.id.tvStep3)
            
            when (tx.status?.lowercase()) {
                "pending" -> {
                    tvStep2.text = "⏳ Under Review"
                    tvStep3.text = "⬜ Completed"
                }
                "completed", "approved" -> {
                    tvStep2.text = "✅ Approved"
                    tvStep3.text = "✅ Completed"
                }
                "failed", "rejected" -> {
                    tvStep2.text = "❌ Rejected"
                    tvStep3.text = "❌ Cancelled & Refunded"
                }
            }
        } else {
            timelineLayout.visibility = View.GONE
        }
        
        dialogView.findViewById<View>(R.id.btnCloseReceipt).setOnClickListener { dialog.dismiss() }
        
        dialog.show()
    }

    override fun getItemCount() = transactions.size

    fun updateData(newTransactions: List<Transaction>) {
        transactions = newTransactions
        notifyDataSetChanged()
    }
}
