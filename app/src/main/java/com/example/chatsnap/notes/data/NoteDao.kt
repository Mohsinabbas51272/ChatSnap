package com.example.chatsnap.notes.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: Note): Long

    @Update
    suspend fun updateNote(note: Note)

    @Delete
    suspend fun deleteNote(note: Note)

    @Query("SELECT * FROM notes WHERE id = :id")
    fun getNoteById(id: Long): Flow<Note?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNoteByIdOnce(id: Long): Note?

    // Query sorted by Newest First (pinned notes always at the top)
    @Query("""
        SELECT * FROM notes 
        WHERE (title LIKE :query OR description LIKE :query OR category LIKE :query)
          AND (:category = 'All' OR category = :category)
        ORDER BY isPinned DESC, createdTime DESC
    """)
    fun getNotesByNewest(query: String, category: String): Flow<List<Note>>

    // Query sorted by Oldest First (pinned notes always at the top)
    @Query("""
        SELECT * FROM notes 
        WHERE (title LIKE :query OR description LIKE :query OR category LIKE :query)
          AND (:category = 'All' OR category = :category)
        ORDER BY isPinned DESC, createdTime ASC
    """)
    fun getNotesByOldest(query: String, category: String): Flow<List<Note>>

    // Query sorted by Recently Modified (pinned notes always at the top)
    @Query("""
        SELECT * FROM notes 
        WHERE (title LIKE :query OR description LIKE :query OR category LIKE :query)
          AND (:category = 'All' OR category = :category)
        ORDER BY isPinned DESC, modifiedTime DESC
    """)
    fun getNotesByModified(query: String, category: String): Flow<List<Note>>
}
