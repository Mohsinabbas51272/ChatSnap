package com.example.chatsnap.media.repository

import android.content.Context
import com.example.chatsnap.media.data.database.FavoriteMediaEntity
import com.example.chatsnap.media.data.database.MediaDao
import com.example.chatsnap.media.data.database.MediaDatabase
import com.example.chatsnap.media.data.database.PlaybackPositionEntity
import com.example.chatsnap.media.data.database.RecentlyPlayedEntity
import com.example.chatsnap.media.data.scanner.MediaStoreScanner
import com.example.chatsnap.media.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

interface MediaRepository {
    suspend fun getVideos(): List<LocalMediaItem>
    suspend fun getAudio(): List<LocalMediaItem>
    suspend fun getAllMedia(): List<LocalMediaItem>
    suspend fun getFolders(): List<FolderItem>
    suspend fun getVideoFolders(): List<FolderItem>
    suspend fun getAudioFolders(): List<FolderItem>
    
    fun getFavorites(): Flow<List<FavoriteMediaEntity>>
    suspend fun toggleFavorite(mediaItem: LocalMediaItem): Boolean
    suspend fun isFavorite(uriStr: String): Boolean

    fun getRecentlyPlayed(): Flow<List<RecentlyPlayedEntity>>
    suspend fun addRecentlyPlayed(mediaItem: LocalMediaItem)

    suspend fun getPlaybackPosition(uriStr: String): Long
    suspend fun savePlaybackPosition(uriStr: String, positionMs: Long, durationMs: Long)

    suspend fun filterAndSort(
        items: List<LocalMediaItem>,
        query: String,
        sortOption: SortOption,
        filterOption: FilterOption,
        selectedFolder: String?
    ): List<LocalMediaItem>
}

class MediaRepositoryImpl(private val context: Context) : MediaRepository {

    private val scanner = MediaStoreScanner(context)
    private val dao: MediaDao by lazy { MediaDatabase.getDatabase(context).mediaDao() }

    override suspend fun getVideos(): List<LocalMediaItem> = withContext(Dispatchers.IO) {
        val videos = scanner.queryAllVideos()
        val favorites = dao.getAllFavorites().first().map { it.mediaUriStr }.toSet()
        videos.onEach { it.isFavorite = favorites.contains(it.uri.toString()) }
    }

    override suspend fun getAudio(): List<LocalMediaItem> = withContext(Dispatchers.IO) {
        val audio = scanner.queryAllAudio()
        val favorites = dao.getAllFavorites().first().map { it.mediaUriStr }.toSet()
        audio.onEach { it.isFavorite = favorites.contains(it.uri.toString()) }
    }

    override suspend fun getAllMedia(): List<LocalMediaItem> = withContext(Dispatchers.IO) {
        getVideos() + getAudio()
    }

    override suspend fun getFolders(): List<FolderItem> = withContext(Dispatchers.IO) {
        val all = getAllMedia()
        scanner.getFolders(all)
    }

    override suspend fun getVideoFolders(): List<FolderItem> = withContext(Dispatchers.IO) {
        val videos = getVideos()
        scanner.getVideoFolders(videos)
    }

    override suspend fun getAudioFolders(): List<FolderItem> = withContext(Dispatchers.IO) {
        val audio = getAudio()
        scanner.getAudioFolders(audio)
    }

    override fun getFavorites(): Flow<List<FavoriteMediaEntity>> = dao.getAllFavorites()

    override suspend fun toggleFavorite(mediaItem: LocalMediaItem): Boolean = withContext(Dispatchers.IO) {
        val uriStr = mediaItem.uri.toString()
        val currentlyFav = dao.isFavorite(uriStr)
        if (currentlyFav) {
            dao.deleteFavorite(uriStr)
            false
        } else {
            dao.insertFavorite(
                FavoriteMediaEntity(
                    mediaUriStr = uriStr,
                    title = mediaItem.title,
                    mediaType = mediaItem.mediaType.name
                )
            )
            true
        }
    }

    override suspend fun isFavorite(uriStr: String): Boolean = dao.isFavorite(uriStr)

    override fun getRecentlyPlayed(): Flow<List<RecentlyPlayedEntity>> = dao.getRecentlyPlayed()

    override suspend fun addRecentlyPlayed(mediaItem: LocalMediaItem) = withContext(Dispatchers.IO) {
        dao.insertRecentlyPlayed(
            RecentlyPlayedEntity(
                mediaUriStr = mediaItem.uri.toString(),
                title = mediaItem.title,
                mediaType = mediaItem.mediaType.name
            )
        )
    }

    override suspend fun getPlaybackPosition(uriStr: String): Long = withContext(Dispatchers.IO) {
        dao.getPlaybackPosition(uriStr)?.positionMs ?: 0L
    }

    override suspend fun savePlaybackPosition(uriStr: String, positionMs: Long, durationMs: Long) = withContext(Dispatchers.IO) {
        dao.savePlaybackPosition(
            PlaybackPositionEntity(
                mediaUriStr = uriStr,
                positionMs = positionMs,
                durationMs = durationMs
            )
        )
    }

    override suspend fun filterAndSort(
        items: List<LocalMediaItem>,
        query: String,
        sortOption: SortOption,
        filterOption: FilterOption,
        selectedFolder: String?
    ): List<LocalMediaItem> = withContext(Dispatchers.Default) {
        var filtered = items

        // 1. Folder filter
        if (!selectedFolder.isNullOrEmpty()) {
            filtered = filtered.filter { it.folderPath == selectedFolder || it.folderName == selectedFolder }
        }

        // 2. Type/Meta Filter
        filtered = when (filterOption) {
            FilterOption.ALL -> filtered
            FilterOption.VIDEOS_ONLY -> filtered.filter { it.mediaType == MediaType.VIDEO }
            FilterOption.AUDIO_ONLY -> filtered.filter { it.mediaType == MediaType.AUDIO }
            FilterOption.FAVORITES -> filtered.filter { it.isFavorite }
            FilterOption.RECENTLY_PLAYED -> filtered // filtered upstream if needed
        }

        // 3. Search query filter
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            filtered = filtered.filter {
                it.title.lowercase().contains(q) ||
                it.displayName.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q) ||
                it.folderName.lowercase().contains(q)
            }
        }

        // 4. Sorting
        when (sortOption) {
            SortOption.NEWEST -> filtered.sortedByDescending { it.dateModified }
            SortOption.OLDEST -> filtered.sortedBy { it.dateModified }
            SortOption.LARGEST -> filtered.sortedByDescending { it.size }
            SortOption.SMALLEST -> filtered.sortedBy { it.size }
            SortOption.DURATION -> filtered.sortedByDescending { it.duration }
            SortOption.ALPHABETICAL -> filtered.sortedBy { it.title.lowercase() }
        }
    }
}
