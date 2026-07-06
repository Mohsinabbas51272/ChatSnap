package com.example.chatsnap.notes.data

import kotlinx.coroutines.flow.Flow

class NoteRepository(private val noteDao: NoteDao) {

    /**
     * Saves a note to the database.
     * Uses INSERT with REPLACE strategy so it works for both new and existing notes.
     * Returns the row ID (for new notes this is the auto-generated ID).
     */
    suspend fun saveNote(note: Note): Long {
        return noteDao.insertNote(note)
    }

    suspend fun deleteNote(note: Note) {
        noteDao.deleteNote(note)
    }

    fun getNoteById(id: Long): Flow<Note?> {
        return noteDao.getNoteById(id)
    }

    suspend fun getNoteByIdOnce(id: Long): Note? {
        return noteDao.getNoteByIdOnce(id)
    }

    fun getNotes(
        query: String,
        category: String,
        sortBy: String
    ): Flow<List<Note>> {
        val formattedQuery = "%$query%"
        return when (sortBy) {
            "oldest" -> noteDao.getNotesByOldest(formattedQuery, category)
            "modified" -> noteDao.getNotesByModified(formattedQuery, category)
            else -> noteDao.getNotesByNewest(formattedQuery, category)
        }
    }
}
