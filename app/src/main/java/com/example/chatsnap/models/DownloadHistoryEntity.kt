package com.example.chatsnap.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download_history")
data class DownloadHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: String = "",  // Firebase UID - to show history per-user
    val url: String,
    val title: String,
    val formatLabel: String,
    val filePath: String?,
    val fileSize: String?,
    val status: String, // "COMPLETED", "FAILED", "CANCELLED"
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
