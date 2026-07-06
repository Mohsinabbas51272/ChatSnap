package com.example.chatsnap.notes.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.chatsnap.databinding.ItemNoteChecklistBinding
import com.example.chatsnap.notes.data.ChecklistItem

class ChecklistAdapter(
    private val onChecklistChanged: (List<ChecklistItem>) -> Unit
) : RecyclerView.Adapter<ChecklistAdapter.ChecklistViewHolder>() {

    private val items = mutableListOf<ChecklistItem>()

    fun submitList(newItems: List<ChecklistItem>) {
        // Only update if sizes or content differ to prevent cursor jumping
        if (items != newItems) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }
    }

    fun addItem() {
        val newItem = ChecklistItem(text = "", isChecked = false)
        items.add(newItem)
        notifyItemInserted(items.size - 1)
        onChecklistChanged(items)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChecklistViewHolder {
        val binding = ItemNoteChecklistBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ChecklistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChecklistViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ChecklistViewHolder(private val binding: ItemNoteChecklistBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var textWatcher: TextWatcher? = null

        fun bind(item: ChecklistItem) {
            // Unbind previous watcher to avoid callbacks during recycled views binding
            binding.etChecklistText.removeTextChangedListener(textWatcher)

            binding.checkBox.isChecked = item.isChecked
            binding.etChecklistText.setText(item.text)

            // Checkbox changes listener
            binding.checkBox.setOnCheckedChangeListener { _, isChecked ->
                item.isChecked = isChecked
                onChecklistChanged(items)
            }

            // Edit text changes listener
            textWatcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    item.text = s.toString()
                    onChecklistChanged(items)
                }
                override fun afterTextChanged(s: Editable?) {}
            }
            binding.etChecklistText.addTextChangedListener(textWatcher)

            // Delete item button
            binding.btnDeleteChecklist.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    items.removeAt(position)
                    notifyItemRemoved(position)
                    onChecklistChanged(items)
                }
            }
        }
    }
}
