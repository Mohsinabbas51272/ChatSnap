package com.example.chatsnap.notes.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.chatsnap.R
import com.example.chatsnap.databinding.ItemAttachmentDocBinding
import java.io.File

class DocAttachmentAdapter(
    private val onDocClicked: (String) -> Unit,
    private val onRemoveClicked: (String) -> Unit
) : RecyclerView.Adapter<DocAttachmentAdapter.DocViewHolder>() {

    private val docs = mutableListOf<String>()

    fun submitList(newDocs: List<String>) {
        if (docs != newDocs) {
            docs.clear()
            docs.addAll(newDocs)
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocViewHolder {
        val binding = ItemAttachmentDocBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return DocViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DocViewHolder, position: Int) {
        holder.bind(docs[position])
    }

    override fun getItemCount(): Int = docs.size

    inner class DocViewHolder(private val binding: ItemAttachmentDocBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(pathString: String) {
            val file = File(pathString)
            val name = file.name
            binding.tvDocName.text = name

            val isPdf = name.endsWith(".pdf", ignoreCase = true)
            binding.ivDocIcon.setImageResource(
                if (isPdf) android.R.drawable.ic_menu_save else android.R.drawable.ic_menu_gallery
            )

            // Details string: Size
            val sizeStr = getFileSizeString(file)
            binding.tvDocDetails.text = if (isPdf) "PDF Document • $sizeStr" else "Image Scan • $sizeStr"

            binding.root.setOnClickListener {
                onDocClicked(pathString)
            }
            binding.btnRemoveDoc.setOnClickListener {
                onRemoveClicked(pathString)
            }
        }

        private fun getFileSizeString(file: File): String {
            if (!file.exists()) return "0 KB"
            val bytes = file.length()
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024
            if (kb < 1024) return "$kb KB"
            val mb = kb / 1024
            return "$mb MB"
        }
    }
}
