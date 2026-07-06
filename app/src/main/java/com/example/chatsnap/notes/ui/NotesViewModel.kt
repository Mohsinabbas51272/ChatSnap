package com.example.chatsnap.notes.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatsnap.notes.data.Note
import com.example.chatsnap.notes.data.NotesDatabase
import com.example.chatsnap.notes.data.NoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "NotesViewModel"
    }

    private val repository: NoteRepository
    
    // Query parameters for Note list
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedCategory = MutableStateFlow("All")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _sortBy = MutableStateFlow("newest")
    val sortBy: StateFlow<String> = _sortBy.asStateFlow()

    private val _isGridView = MutableStateFlow(true)
    val isGridView: StateFlow<Boolean> = _isGridView.asStateFlow()

    val notesList: StateFlow<List<Note>>

    private val _activeNote = MutableStateFlow<Note?>(null)
    val activeNote: StateFlow<Note?> = _activeNote.asStateFlow()

    private var saveJob: Job? = null

    init {
        val database = NotesDatabase.getInstance(application)
        repository = NoteRepository(database.noteDao())

        notesList = combine(
            _searchQuery,
            _selectedCategory,
            _sortBy
        ) { query, category, sort ->
            Triple(query, category, sort)
        }.flatMapLatest { (query, category, sort) ->
            repository.getNotes(query, category, sort)
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    }

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setCategoryFilter(category: String) { _selectedCategory.value = category }
    fun setSortBy(sort: String) { _sortBy.value = sort }
    fun toggleLayoutMode() { _isGridView.value = !_isGridView.value }

    /**
     * Creates a new note in-memory. The note gets an id=0 initially.
     * It will be saved to DB on first edit or on pause.
     */
    fun createNewNote() {
        val note = Note(
            title = "",
            description = "",
            category = "Others",
            colorName = "Yellow",
            createdTime = System.currentTimeMillis(),
            modifiedTime = System.currentTimeMillis()
        )
        _activeNote.value = note
        Log.d(TAG, "createNewNote: created in-memory note")
    }

    fun loadNoteById(id: Long) {
        viewModelScope.launch {
            try {
                val note = withContext(Dispatchers.IO) {
                    repository.getNoteByIdOnce(id)
                }
                _activeNote.value = note
                Log.d(TAG, "loadNoteById: loaded note id=$id, found=${note != null}")
            } catch (e: Exception) {
                Log.e(TAG, "loadNoteById: error loading note id=$id", e)
                _activeNote.value = null
            }
        }
    }

    /**
     * Updates the active note and triggers a debounced auto-save.
     */
    fun updateActiveNote(updateBlock: (Note) -> Unit) {
        val current = _activeNote.value ?: return
        try {
            updateBlock(current)
            current.modifiedTime = System.currentTimeMillis()
            _activeNote.value = current.copy()
        } catch (e: Exception) {
            Log.e(TAG, "updateActiveNote: error in updateBlock", e)
            return
        }

        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500)
            try {
                val noteToSave = _activeNote.value ?: return@launch
                withContext(Dispatchers.IO) {
                    val savedId = repository.saveNote(noteToSave)
                    // If this was a new note (id==0), update with real DB id
                    if (noteToSave.id == 0L && savedId > 0L) {
                        val updated = noteToSave.copy(id = savedId)
                        withContext(Dispatchers.Main) {
                            _activeNote.value = updated
                        }
                        Log.d(TAG, "updateActiveNote: new note got id=$savedId")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "updateActiveNote: error saving note", e)
            }
        }
    }

    /**
     * Immediately saves the active note. Called from onPause.
     * Uses NonCancellable to ensure save completes even if ViewModel is clearing.
     */
    fun forceSaveActiveNote() {
        val current = _activeNote.value ?: return
        if (current.id == 0L && current.title.isBlank() && current.description.isBlank()) {
            return
        }
        saveJob?.cancel()
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            try {
                val savedId = repository.saveNote(current)
                if (current.id == 0L && savedId > 0L) {
                    withContext(Dispatchers.Main) {
                        _activeNote.value = current.copy(id = savedId)
                    }
                }
                Log.d(TAG, "forceSaveActiveNote: saved, id=${current.id}, newId=$savedId")
            } catch (e: Exception) {
                Log.e(TAG, "forceSaveActiveNote: error", e)
            }
        }
    }

    fun deleteActiveNote(onComplete: () -> Unit) {
        val current = _activeNote.value ?: return
        saveJob?.cancel()
        viewModelScope.launch {
            try {
                if (current.id != 0L) {
                    withContext(Dispatchers.IO) {
                        repository.deleteNote(current)
                    }
                }
                _activeNote.value = null
                onComplete()
            } catch (e: Exception) {
                Log.e(TAG, "deleteActiveNote: error", e)
                _activeNote.value = null
                onComplete()
            }
        }
    }
}
