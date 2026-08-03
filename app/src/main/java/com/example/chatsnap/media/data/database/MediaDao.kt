package com.example.chatsnap.media.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    // Favorites
    @Query("SELECT * FROM media_favorites ORDER BY addedTimestamp DESC")
    fun getAllFavorites(): Flow<List<FavoriteMediaEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM media_favorites WHERE mediaUriStr = :uriStr)")
    suspend fun isFavorite(uriStr: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteMediaEntity)

    @Query("DELETE FROM media_favorites WHERE mediaUriStr = :uriStr")
    suspend fun deleteFavorite(uriStr: String)

    // Recently Played
    @Query("SELECT * FROM recently_played ORDER BY lastPlayedTimestamp DESC LIMIT 100")
    fun getRecentlyPlayed(): Flow<List<RecentlyPlayedEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecentlyPlayed(entity: RecentlyPlayedEntity)

    // Playback Position
    @Query("SELECT * FROM playback_positions WHERE mediaUriStr = :uriStr")
    suspend fun getPlaybackPosition(uriStr: String): PlaybackPositionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePlaybackPosition(entity: PlaybackPositionEntity)

    // Folder State
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveLastOpenedFolder(entity: FolderStateEntity)

    @Query("SELECT * FROM folder_state ORDER BY lastOpenedTimestamp DESC LIMIT 1")
    suspend fun getLastOpenedFolder(): FolderStateEntity?
}
