package com.example.chatsnap.notes.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.example.chatsnap.R
import com.example.chatsnap.databinding.ActivityNotesBinding
import com.example.chatsnap.notes.data.Note
import com.google.android.material.chip.Chip
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NotesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotesBinding
    private val viewModel: NotesViewModel by viewModels()
    private lateinit var notesAdapter: NotesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        notesAdapter = NotesAdapter { note ->
            val intent = Intent(this, NoteEditorActivity::class.java).apply {
                putExtra("note_id", note.id)
            }
            startActivity(intent)
        }
        binding.rvNotes.adapter = notesAdapter
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.fabAddNote.setOnClickListener {
            startActivity(Intent(this, NoteEditorActivity::class.java))
        }

        // Layout view toggle switch (Grid vs List)
        binding.btnLayoutToggle.setOnClickListener {
            viewModel.toggleLayoutMode()
        }

        // Sorting popup menu
        binding.btnSort.setOnClickListener { view ->
            showSortPopupMenu(view)
        }

        // Search text watcher
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s.toString()
                viewModel.setSearchQuery(text)
                binding.btnClearSearch.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearSearch.setOnClickListener {
            binding.etSearch.text?.clear()
        }

        // Category filter chips selection listener
        binding.chipGroupCategories.setOnCheckedStateChangeListener { group, checkedIds ->
            val checkedId = checkedIds.firstOrNull()
            if (checkedId != null) {
                val chip = group.findViewById<Chip>(checkedId)
                val category = when (chip.id) {
                    binding.chipCategoryPersonal.id -> "Personal"
                    binding.chipCategoryWork.id -> "Work"
                    binding.chipCategoryStudy.id -> "Study"
                    binding.chipCategoryIdeas.id -> "Ideas"
                    binding.chipCategoryShopping.id -> "Shopping"
                    binding.chipCategoryOthers.id -> "Others"
                    else -> "All"
                }
                viewModel.setCategoryFilter(category)
            } else {
                // If selection cleared, default back to All
                binding.chipCategoryAll.isChecked = true
                viewModel.setCategoryFilter("All")
            }
        }
    }

    private fun showSortPopupMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Newest First")
        popup.menu.add(0, 2, 0, "Oldest First")
        popup.menu.add(0, 3, 0, "Recently Modified")

        popup.setOnMenuItemClickListener { item ->
            val sort = when (item.itemId) {
                2 -> "oldest"
                3 -> "modified"
                else -> "newest"
            }
            viewModel.setSortBy(sort)
            true
        }
        popup.show()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Collect and render layout configuration
                launch {
                    viewModel.isGridView.collectLatest { isGrid ->
                        binding.rvNotes.layoutManager = if (isGrid) {
                            binding.btnLayoutToggle.setImageResource(R.drawable.ic_view_list) // Click to show list
                            StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
                        } else {
                            binding.btnLayoutToggle.setImageResource(R.drawable.ic_view_grid) // Click to show grid
                            LinearLayoutManager(this@NotesActivity)
                        }
                    }
                }

                // Collect and render notes list
                launch {
                    viewModel.notesList.collectLatest { notes ->
                        notesAdapter.submitList(notes)
                        if (notes.isEmpty()) {
                            binding.layoutEmpty.visibility = View.VISIBLE
                            binding.rvNotes.visibility = View.GONE
                        } else {
                            binding.layoutEmpty.visibility = View.GONE
                            binding.rvNotes.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
    }
}
