package com.example.chatsnap.adapters

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.chatsnap.databinding.ItemAiMsgBotBinding
import com.example.chatsnap.databinding.ItemAiMsgUserBinding
import com.example.chatsnap.models.AiMessage

class AiChatAdapter(
    private val messages: List<AiMessage>,
    private val onBotMessageLongClick: ((String) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_BOT = 2
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) VIEW_TYPE_USER else VIEW_TYPE_BOT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER) {
            val binding = ItemAiMsgUserBinding.inflate(inflater, parent, false)
            UserViewHolder(binding)
        } else {
            val binding = ItemAiMsgBotBinding.inflate(inflater, parent, false)
            BotViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        if (holder is UserViewHolder) {
            holder.binding.tvContent.text = msg.content
        } else if (holder is BotViewHolder) {
            holder.binding.tvContent.text = msg.content

            holder.itemView.setOnLongClickListener {
                onBotMessageLongClick?.invoke(msg.content) ?: run {
                    val clipboard = holder.itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("AI Message", msg.content)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(holder.itemView.context, "Copied response to clipboard", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
    }

    override fun getItemCount() = messages.size

    class UserViewHolder(val binding: ItemAiMsgUserBinding) : RecyclerView.ViewHolder(binding.root)
    class BotViewHolder(val binding: ItemAiMsgBotBinding) : RecyclerView.ViewHolder(binding.root)
}
