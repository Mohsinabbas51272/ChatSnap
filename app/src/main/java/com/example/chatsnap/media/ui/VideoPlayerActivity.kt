package com.example.chatsnap.media.ui

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.chatsnap.media.data.database.MediaDatabase
import com.example.chatsnap.media.data.database.PlaybackPositionEntity
import com.example.chatsnap.media.model.LocalMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import android.content.Intent
import com.example.chatsnap.media.player.AudioPlaybackService

class VideoPlayerActivity : ComponentActivity() {

    private var exoPlayer: ExoPlayer? = null
    private var mediaItem: LocalMediaItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mediaItem = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("EXTRA_MEDIA_ITEM", LocalMediaItem::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("EXTRA_MEDIA_ITEM")
        }

        if (mediaItem == null) {
            Toast.makeText(this, "Video not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initPlayer(mediaItem!!)

        setContent {
            SnapchatTheme {
                VideoPlayerScreenContent(
                    mediaItem = mediaItem!!,
                    player = exoPlayer!!,
                    onBackClick = { finish() },
                    onToggleLandscape = { toggleOrientation() },
                    onEnterPip = { enterPipMode() },
                    onPlayInBackground = { playInBackground() }
                )
            }
        }
    }

    private fun playInBackground() {
        val item = mediaItem ?: return
        val player = exoPlayer ?: return
        val currentPos = player.currentPosition

        val intent = Intent(this, AudioPlaybackService::class.java).apply {
            action = AudioPlaybackService.ACTION_PLAY_BACKGROUND
            putExtra("EXTRA_MEDIA_ITEM", item)
            putExtra("EXTRA_POSITION_MS", currentPos)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        Toast.makeText(this, "Playing video audio in background...", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun initPlayer(item: LocalMediaItem) {
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(item.uri))
            prepare()
            playWhenReady = true

            // Restore playback position from Room DB
            val dao = MediaDatabase.getDatabase(this@VideoPlayerActivity).mediaDao()
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                val entity = dao.getPlaybackPosition(item.uri.toString())
                if (entity != null && entity.positionMs > 1000) {
                    withContext(Dispatchers.Main) {
                        seekTo(entity.positionMs)
                    }
                }
            }
        }
    }

    private fun toggleOrientation() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPipMode()
        }
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
        saveCurrentPosition()
    }

    private fun saveCurrentPosition() {
        val player = exoPlayer ?: return
        val item = mediaItem ?: return
        val pos = player.currentPosition
        val dur = player.duration
        if (pos > 0) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                MediaDatabase.getDatabase(this@VideoPlayerActivity).mediaDao()
                    .savePlaybackPosition(
                        PlaybackPositionEntity(
                            mediaUriStr = item.uri.toString(),
                            positionMs = pos,
                            durationMs = if (dur > 0) dur else 0L
                        )
                    )
            }
        }
    }

    override fun onDestroy() {
        saveCurrentPosition()
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreenContent(
    mediaItem: LocalMediaItem,
    player: ExoPlayer,
    onBackClick: () -> Unit,
    onToggleLandscape: () -> Unit,
    onEnterPip: () -> Unit,
    onPlayInBackground: () -> Unit
) {
    var showControls by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var currentPos by remember { mutableLongStateOf(player.currentPosition) }
    var duration by remember { mutableLongStateOf(if (player.duration > 0) player.duration else mediaItem.duration) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var isMuted by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var doubleTapSeekOverlay by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()

    // Sync state
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    duration = player.duration
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Auto hide controls
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(3500)
            showControls = false
        }
    }

    // Update position slider
    LaunchedEffect(isPlaying) {
        while (true) {
            if (isPlaying) {
                currentPos = player.currentPosition
            }
            delay(500)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { showControls = !showControls },
                    onDoubleTap = { offset ->
                        val screenWidth = size.width
                        if (offset.x < screenWidth / 2) {
                            player.seekTo((player.currentPosition - 10000).coerceAtLeast(0))
                            doubleTapSeekOverlay = "-10s"
                        } else {
                            player.seekTo((player.currentPosition + 10000).coerceAtMost(duration))
                            doubleTapSeekOverlay = "+10s"
                        }
                        currentPos = player.currentPosition
                        coroutineScope.launch {
                            delay(800)
                            doubleTapSeekOverlay = null
                        }
                    }
                )
            }
    ) {
        // ExoPlayer View
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Buffering Indicator
        if (isBuffering) {
            CircularProgressIndicator(
                color = Color(0xFFFFFC00),
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Double Tap Overlay
        doubleTapSeekOverlay?.let { text ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Text(
                    text = text,
                    color = Color(0xFFFFFC00),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Snapchat Controls Overlay
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 32.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = mediaItem.title,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (mediaItem.resolutionText.isNotEmpty()) {
                            Text(
                                text = "${mediaItem.resolutionText} • ${mediaItem.formattedSize}",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row {
                        IconButton(onClick = onPlayInBackground) {
                            Icon(
                                imageVector = Icons.Default.Headphones,
                                contentDescription = "Play in Background",
                                tint = Color(0xFFFFFC00)
                            )
                        }
                        IconButton(onClick = onEnterPip) {
                            Icon(
                                imageVector = Icons.Default.PictureInPicture,
                                contentDescription = "PiP",
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = onToggleLandscape) {
                            Icon(
                                imageVector = Icons.Default.ScreenRotation,
                                contentDescription = "Rotate",
                                tint = Color.White
                            )
                        }
                    }
                }

                // Center Play/Pause
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { player.seekTo((player.currentPosition - 10000).coerceAtLeast(0)) },
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay10,
                            contentDescription = "Seek -10s",
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = {
                            if (player.isPlaying) player.pause() else player.play()
                        },
                        modifier = Modifier
                            .size(72.dp)
                            .background(Color(0xFFFFFC00), CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.Black,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    IconButton(
                        onClick = { player.seekTo((player.currentPosition + 10000).coerceAtMost(duration)) },
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forward10,
                            contentDescription = "Seek +10s",
                            tint = Color.White
                        )
                    }
                }

                // Bottom Controls
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    // Time Labels
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatTime(currentPos),
                            color = Color.White,
                            fontSize = 12.sp
                        )
                        Text(
                            text = formatTime(duration),
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                    }

                    // Seekbar
                    Slider(
                        value = if (duration > 0) currentPos.toFloat() / duration.toFloat() else 0f,
                        onValueChange = { fraction ->
                            val newPos = (fraction * duration).toLong()
                            currentPos = newPos
                            player.seekTo(newPos)
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFFFC00),
                            activeTrackColor = Color(0xFFFFFC00),
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        )
                    )

                    // Options Row (Mute, Speed)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                isMuted = !isMuted
                                player.volume = if (isMuted) 0f else 1f
                            }
                        ) {
                            Icon(
                                imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                contentDescription = "Mute",
                                tint = Color.White
                            )
                        }

                        Box {
                            TextButton(onClick = { showSpeedMenu = true }) {
                                Text(
                                    text = "${playbackSpeed}x",
                                    color = Color(0xFFFFFC00),
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            DropdownMenu(
                                expanded = showSpeedMenu,
                                onDismissRequest = { showSpeedMenu = false }
                            ) {
                                listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                                    DropdownMenuItem(
                                        text = { Text("${speed}x") },
                                        onClick = {
                                            playbackSpeed = speed
                                            player.setPlaybackSpeed(speed)
                                            showSpeedMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val sec = totalSec % 60
    val min = (totalSec / 60) % 60
    val hr = totalSec / 3600
    return if (hr > 0) {
        String.format("%d:%02d:%02d", hr, min, sec)
    } else {
        String.format("%02d:%02d", min, sec)
    }
}

@Composable
fun SnapchatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFFFFC00),
            secondary = Color(0xFF00D2FF),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E)
        ),
        content = content
    )
}
