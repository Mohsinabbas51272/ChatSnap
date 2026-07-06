package com.example.chatsnap.notes.ui

import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.chatsnap.databinding.ItemNoteBinding
import com.example.chatsnap.notes.data.Note
import java.text.SimpleDateFormat
import java.util.*

class NotesAdapter(
    private val onNoteClicked: (Note) -> Unit
) : ListAdapter<Note, NotesAdapter.NoteViewHolder>(NoteDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val binding = ItemNoteBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return NoteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class NoteViewHolder(private val binding: ItemNoteBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(note: Note) {
            binding.tvNoteTitle.text = note.title.ifEmpty { "Untitled Note" }
            binding.tvNotePreview.text = note.description.ifEmpty { 
                val checklistSize = note.getChecklist().size
                if (checklistSize > 0) "Checklist ($checklistSize items)" else "No additional text"
            }
            binding.tvCategoryLabel.text = note.category
            
            // Format time
            val date = Date(note.modifiedTime)
            val formatter = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
            binding.tvModifiedTime.text = formatter.format(date)

            // Pin / Favorite Icons visibility
            binding.ivPin.visibility = if (note.isPinned) View.VISIBLE else View.GONE
            binding.ivFavorite.visibility = if (note.isFavorite) View.VISIBLE else View.GONE

            // Apply Background color according to theme
            val context = binding.root.context
            val isDarkTheme = (context.resources.configuration.uiMode and 
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            
            val noteColorObj = NoteColor.getColorByName(note.colorName)
            binding.cardNote.setCardBackgroundColor(noteColorObj.getBackgroundColor(isDarkTheme))

            binding.root.setOnClickListener {
                onNoteClicked(note)
            }
        }
    }

    class NoteDiffCallback : DiffUtil.ItemCallback<Note>() {
        override fun areItemsTheSame(oldItem: Note, newItem: Note): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Note, newItem: Note): Boolean {
            return oldItem == newItem
        }
    }
}
