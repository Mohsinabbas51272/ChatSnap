package com.example.chatsnap.media.domain

import com.example.chatsnap.media.model.*
import com.example.chatsnap.media.repository.MediaRepository

class MediaUseCases(private val repository: MediaRepository) {

    suspend fun getVideos(): List<LocalMediaItem> = repository.getVideos()

    suspend fun getAudio(): List<LocalMediaItem> = repository.getAudio()

    suspend fun getFolders(): List<FolderItem> = repository.getFolders()

    suspend fun getVideoFolders(): List<FolderItem> = repository.getVideoFolders()

    suspend fun getAudioFolders(): List<FolderItem> = repository.getAudioFolders()

    suspend fun toggleFavorite(item: LocalMediaItem): Boolean = repository.toggleFavorite(item)

    suspend fun savePlaybackPosition(uriStr: String, positionMs: Long, durationMs: Long) {
        repository.savePlaybackPosition(uriStr, positionMs, durationMs)
    }

    suspend fun getPlaybackPosition(uriStr: String): Long {
        return repository.getPlaybackPosition(uriStr)
    }

    suspend fun recordRecentlyPlayed(item: LocalMediaItem) {
        repository.addRecentlyPlayed(item)
    }

    suspend fun processMedia(
        items: List<LocalMediaItem>,
        query: String,
        sortOption: SortOption,
        filterOption: FilterOption,
        selectedFolder: String?
    ): List<LocalMediaItem> {
        return repository.filterAndSort(items, query, sortOption, filterOption, selectedFolder)
    }
}
