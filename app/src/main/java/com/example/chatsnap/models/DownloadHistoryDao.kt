package com.example.chatsnap.models

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DownloadHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: DownloadHistoryEntity)

    @Query("SELECT * FROM download_history ORDER BY timestamp DESC")
    suspend fun getAll(): List<DownloadHistoryEntity>

    @Query("SELECT * FROM download_history WHERE userId = :userId ORDER BY timestamp DESC")
    suspend fun getByUserId(userId: String): List<DownloadHistoryEntity>

    @Query("DELETE FROM download_history WHERE id = :entryId")
    suspend fun deleteById(entryId: Long)

    @Query("DELETE FROM download_history")
    suspend fun clearAll()

    @Query("DELETE FROM download_history WHERE userId = :userId")
    suspend fun clearByUserId(userId: String)

    @Query("SELECT COUNT(*) FROM download_history")
    suspend fun getCount(): Int
}
