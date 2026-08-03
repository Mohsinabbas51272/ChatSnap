package com.example.chatsnap.media.model

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class MediaType {
    VIDEO,
    AUDIO
}

enum class SortOption(val displayName: String) {
    NEWEST("Newest First"),
    OLDEST("Oldest First"),
    LARGEST("Largest Size"),
    SMALLEST("Smallest Size"),
    DURATION("Duration"),
    ALPHABETICAL("Alphabetical (A-Z)")
}

enum class FilterOption(val displayName: String) {
    ALL("All Media"),
    VIDEOS_ONLY("Videos Only"),
    AUDIO_ONLY("Audio Only"),
    FAVORITES("Favorites"),
    RECENTLY_PLAYED("Recently Played")
}

@Parcelize
data class LocalMediaItem(
    val id: Long,
    val uri: Uri,
    val path: String,
    val title: String,
    val displayName: String,
    val mediaType: MediaType,
    val mimeType: String,
    val size: Long,
    val duration: Long,
    val dateModified: Long,
    val folderName: String,
    val folderPath: String,
    // Video specific
    val width: Int = 0,
    val height: Int = 0,
    // Audio specific
    val artist: String = "Unknown Artist",
    val album: String = "Unknown Album",
    val albumId: Long = -1L,
    // Meta state
    var isFavorite: Boolean = false,
    var lastPositionMs: Long = 0L
) : Parcelable {
    val formattedDuration: String
        get() {
            if (duration <= 0) return "00:00"
            val totalSeconds = duration / 1000
            val seconds = totalSeconds % 60
            val minutes = (totalSeconds / 60) % 60
            val hours = totalSeconds / 3600
            return if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }
        }

    val formattedSize: String
        get() {
            if (size <= 0) return "0 B"
            val kb = size / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format("%.2f GB", gb)
                mb >= 1.0 -> String.format("%.1f MB", mb)
                kb >= 1.0 -> String.format("%.0f KB", kb)
                else -> "$size B"
            }
        }

    val resolutionText: String
        get() = if (width > 0 && height > 0) "${width}x${height}" else ""
}

data class FolderItem(
    val folderName: String,
    val folderPath: String,
    val itemCount: Int,
    val totalSize: Long,
    val thumbnailUri: Uri?,
    val mediaType: MediaType
) {
    val formattedSize: String
        get() {
            val mb = totalSize / (1024.0 * 1024.0)
            val gb = mb / 1024.0
            return if (gb >= 1.0) String.format("%.2f GB", gb) else String.format("%.1f MB", mb)
        }
}
