package com.example.chatsnap.notes.ui

import android.content.Context
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chatsnap.R
import com.example.chatsnap.databinding.DialogPickDocumentBinding
import com.example.chatsnap.databinding.ItemPickDocumentBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object DocumentPickDialog {

    fun show(
        context: Context,
        onDocSelected: (File) -> Unit,
        onLaunchSystemPicker: () -> Unit
    ) {
        val binding = DialogPickDocumentBinding.inflate(LayoutInflater.from(context))
        
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .setCancelable(true)
            .create()

        // Read scanned documents from standard Documents directory
        val docFolder = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        val docFiles = docFolder?.listFiles { file ->
            val name = file.name.lowercase()
            name.endsWith(".pdf") || name.endsWith(".jpg") || name.endsWith(".jpeg")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        if (docFiles.isEmpty()) {
            binding.rvScannedDocs.visibility = View.GONE
            binding.tvNoScannedDocs.visibility = View.VISIBLE
        } else {
            binding.rvScannedDocs.visibility = View.VISIBLE
            binding.tvNoScannedDocs.visibility = View.GONE

            binding.rvScannedDocs.layoutManager = LinearLayoutManager(context)
            binding.rvScannedDocs.adapter = ScannedDocAdapter(docFiles) { file ->
                onDocSelected(file)
                dialog.dismiss()
            }
        }

        binding.btnSystemFilePicker.setOnClickListener {
            onLaunchSystemPicker()
            dialog.dismiss()
        }

        dialog.show()
    }

    private class ScannedDocAdapter(
        private val files: List<File>,
        private val onFileClicked: (File) -> Unit
    ) : RecyclerView.Adapter<ScannedDocAdapter.DocViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocViewHolder {
            val binding = ItemPickDocumentBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return DocViewHolder(binding)
        }

        override fun onBindViewHolder(holder: DocViewHolder, position: Int) {
            holder.bind(files[position])
        }

        override fun getItemCount(): Int = files.size

        inner class DocViewHolder(private val binding: ItemPickDocumentBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(file: File) {
                val name = file.name
                binding.tvDocName.text = name

                val isPdf = name.lowercase().endsWith(".pdf")
                binding.ivDocIcon.setImageResource(
                    if (isPdf) android.R.drawable.ic_menu_save else android.R.drawable.ic_menu_gallery
                )

                val dateStr = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(file.lastModified()))
                val sizeStr = getFileSizeString(file)
                binding.tvDocDetails.text = "$dateStr • $sizeStr"

                binding.root.setOnClickListener {
                    onFileClicked(file)
                }
            }

            private fun getFileSizeString(file: File): String {
                val bytes = file.length()
                if (bytes < 1024) return "$bytes B"
                val kb = bytes / 1024
                if (kb < 1024) return "$kb KB"
                val mb = kb / 1024
                return "$mb MB"
            }
        }
    }
}
