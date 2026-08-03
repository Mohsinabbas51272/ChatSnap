package com.example.chatsnap.media.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_favorites")
data class FavoriteMediaEntity(
    @PrimaryKey val mediaUriStr: String,
    val title: String,
    val mediaType: String,
    val addedTimestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "recently_played")
data class RecentlyPlayedEntity(
    @PrimaryKey val mediaUriStr: String,
    val title: String,
    val mediaType: String,
    val lastPlayedTimestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "playback_positions")
data class PlaybackPositionEntity(
    @PrimaryKey val mediaUriStr: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedTimestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "folder_state")
data class FolderStateEntity(
    @PrimaryKey val folderPath: String,
    val folderName: String,
    val lastOpenedTimestamp: Long = System.currentTimeMillis()
)
