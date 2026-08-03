package com.example.chatsnap.media.data.scanner

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.chatsnap.media.model.FolderItem
import com.example.chatsnap.media.model.LocalMediaItem
import com.example.chatsnap.media.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MediaStoreScanner(private val context: Context) {

    suspend fun queryAllVideos(): List<LocalMediaItem> = withContext(Dispatchers.IO) {
        val videosList = mutableListOf<LocalMediaItem>()
        val contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )

        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(
                contentUri,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: "Video_$id"
                    val path = cursor.getString(dataColumn) ?: ""
                    val mime = cursor.getString(mimeColumn) ?: "video/*"
                    val size = cursor.getLong(sizeColumn)
                    val duration = cursor.getLong(durationColumn)
                    val dateModified = cursor.getLong(dateColumn) * 1000L
                    val width = cursor.getInt(widthColumn)
                    val height = cursor.getInt(heightColumn)

                    val file = File(path)
                    val folderName = if (file.parentFile != null) file.parentFile!!.name else "Videos"
                    val folderPath = file.parent ?: "/sdcard/Movies"
                    val uri = ContentUris.withAppendedId(contentUri, id)

                    val title = name.substringBeforeLast(".")

                    videosList.add(
                        LocalMediaItem(
                            id = id,
                            uri = uri,
                            path = path,
                            title = title,
                            displayName = name,
                            mediaType = MediaType.VIDEO,
                            mimeType = mime,
                            size = size,
                            duration = duration,
                            dateModified = dateModified,
                            folderName = folderName,
                            folderPath = folderPath,
                            width = width,
                            height = height
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        videosList
    }

    suspend fun queryAllAudio(): List<LocalMediaItem> = withContext(Dispatchers.IO) {
        val audioList = mutableListOf<LocalMediaItem>()
        val contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.DURATION} > 5000"
        val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(
                contentUri,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: "Audio_$id"
                    val titleRaw = cursor.getString(titleColumn)
                    val path = cursor.getString(dataColumn) ?: ""
                    val mime = cursor.getString(mimeColumn) ?: "audio/*"
                    val size = cursor.getLong(sizeColumn)
                    val duration = cursor.getLong(durationColumn)
                    val dateModified = cursor.getLong(dateColumn) * 1000L
                    val artist = cursor.getString(artistColumn) ?: "Unknown Artist"
                    val album = cursor.getString(albumColumn) ?: "Unknown Album"
                    val albumId = cursor.getLong(albumIdColumn)

                    val file = File(path)
                    val folderName = if (file.parentFile != null) file.parentFile!!.name else "Music"
                    val folderPath = file.parent ?: "/sdcard/Music"
                    val uri = ContentUris.withAppendedId(contentUri, id)

                    val title = if (!titleRaw.isNullOrBlank()) titleRaw else name.substringBeforeLast(".")

                    audioList.add(
                        LocalMediaItem(
                            id = id,
                            uri = uri,
                            path = path,
                            title = title,
                            displayName = name,
                            mediaType = MediaType.AUDIO,
                            mimeType = mime,
                            size = size,
                            duration = duration,
                            dateModified = dateModified,
                            folderName = folderName,
                            folderPath = folderPath,
                            artist = if (artist == "<unknown>") "Unknown Artist" else artist,
                            album = if (album == "<unknown>") "Unknown Album" else album,
                            albumId = albumId
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        audioList
    }

    suspend fun getFolders(allMedia: List<LocalMediaItem>): List<FolderItem> = withContext(Dispatchers.Default) {
        val groupedMap = allMedia.groupBy { it.folderPath }
        groupedMap.map { (path, items) ->
            val firstItem = items.first()
            val totalSize = items.sumOf { it.size }
            FolderItem(
                folderName = firstItem.folderName,
                folderPath = path,
                itemCount = items.size,
                totalSize = totalSize,
                thumbnailUri = firstItem.uri,
                mediaType = firstItem.mediaType
            )
        }.sortedByDescending { it.itemCount }
    }

    suspend fun getVideoFolders(videos: List<LocalMediaItem>): List<FolderItem> = withContext(Dispatchers.Default) {
        getFolders(videos.filter { it.mediaType == MediaType.VIDEO })
    }

    suspend fun getAudioFolders(audio: List<LocalMediaItem>): List<FolderItem> = withContext(Dispatchers.Default) {
        getFolders(audio.filter { it.mediaType == MediaType.AUDIO })
    }
}
