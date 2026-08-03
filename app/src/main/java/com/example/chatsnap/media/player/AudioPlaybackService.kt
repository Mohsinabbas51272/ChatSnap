package com.example.chatsnap.media.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.chatsnap.R
import com.example.chatsnap.media.model.LocalMediaItem
import kotlinx.coroutines.flow.MutableStateFlow

class AudioPlaybackService : Service() {

    private val binder = LocalBinder()
    private var exoPlayer: ExoPlayer? = null

    val currentTrack = MutableStateFlow<LocalMediaItem?>(null)
    val isPlaying = MutableStateFlow(false)
    val playbackPosition = MutableStateFlow(0L)
    val duration = MutableStateFlow(0L)
    val isShuffle = MutableStateFlow(false)
    val isRepeat = MutableStateFlow(false)

    private var playlist: List<LocalMediaItem> = emptyList()
    private var currentIndex = -1

    inner class LocalBinder : Binder() {
        fun getService(): AudioPlaybackService = this@AudioPlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        this@AudioPlaybackService.isPlaying.value = playing
                        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        if (playing) {
                            startForeground(NOTIFICATION_ID, buildNotification())
                        } else {
                            if (currentTrack.value != null) {
                                manager.notify(NOTIFICATION_ID, buildNotification())
                            }
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            if (this@AudioPlaybackService.isRepeat.value) {
                                exoPlayer?.seekTo(0)
                                exoPlayer?.play()
                            } else {
                                playNext()
                            }
                        }
                    }
                })
            }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_PLAY_PAUSE -> togglePlayPause()
                ACTION_NEXT -> playNext()
                ACTION_PREV -> playPrevious()
                ACTION_STOP -> stopAudioService()
                ACTION_PLAY_BACKGROUND -> {
                    val item = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra("EXTRA_MEDIA_ITEM", LocalMediaItem::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra("EXTRA_MEDIA_ITEM")
                    }
                    val posMs = intent.getLongExtra("EXTRA_POSITION_MS", 0L)
                    if (item != null) {
                        playTrackList(listOf(item), 0)
                        if (posMs > 0) {
                            seekTo(posMs)
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    fun stopAudioService() {
        exoPlayer?.apply {
            stop()
            clearMediaItems()
        }
        isPlaying.value = false
        currentTrack.value = null
        duration.value = 0L

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)

        stopSelf()
    }

    fun playTrackList(tracks: List<LocalMediaItem>, startIndex: Int) {
        playlist = tracks
        currentIndex = startIndex
        if (currentIndex in playlist.indices) {
            playCurrentIndex()
        }
    }

    fun playCurrentIndex() {
        val track = playlist.getOrNull(currentIndex) ?: return
        currentTrack.value = track
        duration.value = track.duration

        exoPlayer?.apply {
            stop()
            setMediaItem(MediaItem.fromUri(track.uri))
            prepare()
            play()
        }
    }

    fun togglePlayPause() {
        exoPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    fun playNext() {
        if (playlist.isEmpty()) return
        if (isShuffle.value) {
            currentIndex = (playlist.indices).random()
        } else {
            currentIndex = (currentIndex + 1) % playlist.size
        }
        playCurrentIndex()
    }

    fun playPrevious() {
        if (playlist.isEmpty()) return
        if (isShuffle.value) {
            currentIndex = (playlist.indices).random()
        } else {
            currentIndex = if (currentIndex - 1 < 0) playlist.size - 1 else currentIndex - 1
        }
        playCurrentIndex()
    }

    fun toggleShuffle() {
        isShuffle.value = !isShuffle.value
    }

    fun toggleRepeat() {
        isRepeat.value = !isRepeat.value
    }

    fun getCurrentPosition(): Long = exoPlayer?.currentPosition ?: 0L

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ChatSnap Media Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Audio playback controls notification"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val track = currentTrack.value
        val title = track?.title ?: "ChatSnap Media"
        val artist = track?.artist ?: "Playing Audio"

        val playPauseIcon = if (isPlaying.value) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play

        val playPausePendingIntent = PendingIntent.getService(
            this, 1, Intent(this, AudioPlaybackService::class.java).apply { action = ACTION_PLAY_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextPendingIntent = PendingIntent.getService(
            this, 2, Intent(this, AudioPlaybackService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevPendingIntent = PendingIntent.getService(
            this, 3, Intent(this, AudioPlaybackService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopPendingIntent = PendingIntent.getService(
            this, 4, Intent(this, AudioPlaybackService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(artist)
            .setOngoing(isPlaying.value)
            .setDeleteIntent(stopPendingIntent)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevPendingIntent)
            .addAction(playPauseIcon, "Play/Pause", playPausePendingIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", stopPendingIntent)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1, 2, 3))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "chatsnap_audio_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY_PAUSE = "com.example.chatsnap.media.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.chatsnap.media.ACTION_NEXT"
        const val ACTION_PREV = "com.example.chatsnap.media.ACTION_PREV"
        const val ACTION_STOP = "com.example.chatsnap.media.ACTION_STOP"
        const val ACTION_PLAY_BACKGROUND = "com.example.chatsnap.media.ACTION_PLAY_BACKGROUND"
    }
}
