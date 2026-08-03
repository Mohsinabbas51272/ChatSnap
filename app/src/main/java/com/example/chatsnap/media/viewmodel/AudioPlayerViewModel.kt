package com.example.chatsnap.media.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatsnap.media.model.LocalMediaItem
import com.example.chatsnap.media.player.AudioPlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class AudioPlayerUiState(
    val currentTrack: LocalMediaItem? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isShuffle: Boolean = false,
    val isRepeat: Boolean = false,
    val isExpanded: Boolean = false
)

class AudioPlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AudioPlayerUiState())
    val uiState: StateFlow<AudioPlayerUiState> = _uiState.asStateFlow()

    private var audioService: AudioPlaybackService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioPlaybackService.LocalBinder
            audioService = binder.getService()
            isBound = true
            observeService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioService = null
            isBound = false
        }
    }

    init {
        bindAudioService()
        startPositionTracker()
    }

    private fun bindAudioService() {
        val context = getApplication<Application>()
        val intent = Intent(context, AudioPlaybackService::class.java)
        context.startService(intent)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun observeService() {
        viewModelScope.launch {
            audioService?.let { service ->
                launch {
                    service.currentTrack.collect { track ->
                        _uiState.update { it.copy(currentTrack = track) }
                    }
                }
                launch {
                    service.isPlaying.collect { playing ->
                        _uiState.update { it.copy(isPlaying = playing) }
                    }
                }
                launch {
                    service.duration.collect { dur ->
                        _uiState.update { it.copy(durationMs = dur) }
                    }
                }
                launch {
                    service.isShuffle.collect { shuffle ->
                        _uiState.update { it.copy(isShuffle = shuffle) }
                    }
                }
                launch {
                    service.isRepeat.collect { repeat ->
                        _uiState.update { it.copy(isRepeat = repeat) }
                    }
                }
            }
        }
    }

    private fun startPositionTracker() {
        viewModelScope.launch {
            while (true) {
                audioService?.let { service ->
                    if (service.isPlaying.value) {
                        _uiState.update { it.copy(currentPositionMs = service.getCurrentPosition()) }
                    }
                }
                delay(500)
            }
        }
    }

    fun playTrackList(tracks: List<LocalMediaItem>, startIndex: Int) {
        audioService?.playTrackList(tracks, startIndex)
    }

    fun togglePlayPause() {
        audioService?.togglePlayPause()
    }

    fun playNext() {
        audioService?.playNext()
    }

    fun playPrevious() {
        audioService?.playPrevious()
    }

    fun seekTo(positionMs: Long) {
        audioService?.seekTo(positionMs)
        _uiState.update { it.copy(currentPositionMs = positionMs) }
    }

    fun toggleShuffle() {
        audioService?.toggleShuffle()
    }

    fun toggleRepeat() {
        audioService?.toggleRepeat()
    }

    fun setExpanded(expanded: Boolean) {
        _uiState.update { it.copy(isExpanded = expanded) }
    }

    fun stopPlayback() {
        audioService?.stopAudioService()
    }

    override fun onCleared() {
        if (isBound) {
            getApplication<Application>().unbindService(serviceConnection)
            isBound = false
        }
        super.onCleared()
    }
}
