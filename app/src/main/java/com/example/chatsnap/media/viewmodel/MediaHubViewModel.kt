package com.example.chatsnap.media.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatsnap.media.domain.MediaUseCases
import com.example.chatsnap.media.model.*
import com.example.chatsnap.media.repository.MediaRepositoryImpl
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class MediaHubUiState(
    val isLoading: Boolean = true,
    val selectedTab: Int = 0, // 0 = Video Directories, 1 = Audio Directories
    val videos: List<LocalMediaItem> = emptyList(),
    val audio: List<LocalMediaItem> = emptyList(),
    val videoFolders: List<FolderItem> = emptyList(),
    val audioFolders: List<FolderItem> = emptyList(),
    val displayedMedia: List<LocalMediaItem> = emptyList(),
    val searchQuery: String = "",
    val sortOption: SortOption = SortOption.NEWEST,
    val filterOption: FilterOption = FilterOption.ALL,
    val selectedFolder: FolderItem? = null,
    val errorMessage: String? = null
)

class MediaHubViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepositoryImpl(application)
    private val useCases = MediaUseCases(repository)

    private val _uiState = MutableStateFlow(MediaHubUiState())
    val uiState: StateFlow<MediaHubUiState> = _uiState.asStateFlow()

    private var allVideosCache = listOf<LocalMediaItem>()
    private var allAudioCache = listOf<LocalMediaItem>()

    init {
        loadMediaData()
    }

    fun loadMediaData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                allVideosCache = useCases.getVideos()
                allAudioCache = useCases.getAudio()
                val vFolders = useCases.getVideoFolders()
                val aFolders = useCases.getAudioFolders()

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        videos = allVideosCache,
                        audio = allAudioCache,
                        videoFolders = vFolders,
                        audioFolders = aFolders
                    )
                }
                updateFilteredMedia()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to scan media: ${e.message}"
                    )
                }
            }
        }
    }

    fun selectTab(tabIndex: Int) {
        _uiState.update { it.copy(selectedTab = tabIndex, selectedFolder = null) }
        updateFilteredMedia()
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        updateFilteredMedia()
    }

    fun updateSortOption(sortOption: SortOption) {
        _uiState.update { it.copy(sortOption = sortOption) }
        updateFilteredMedia()
    }

    fun updateFilterOption(filterOption: FilterOption) {
        _uiState.update { it.copy(filterOption = filterOption) }
        updateFilteredMedia()
    }

    fun selectFolder(folder: FolderItem?) {
        _uiState.update { it.copy(selectedFolder = folder) }
        updateFilteredMedia()
    }

    fun toggleFavorite(item: LocalMediaItem) {
        viewModelScope.launch {
            val isNowFav = useCases.toggleFavorite(item)
            item.isFavorite = isNowFav

            // Refresh lists
            allVideosCache.find { it.id == item.id }?.isFavorite = isNowFav
            allAudioCache.find { it.id == item.id }?.isFavorite = isNowFav

            _uiState.update {
                it.copy(
                    videos = allVideosCache.toList(),
                    audio = allAudioCache.toList()
                )
            }
            updateFilteredMedia()
        }
    }

    private fun updateFilteredMedia() {
        viewModelScope.launch {
            val state = _uiState.value
            val baseList = if (state.selectedTab == 0) allVideosCache else allAudioCache

            val processed = useCases.processMedia(
                items = baseList,
                query = state.searchQuery,
                sortOption = state.sortOption,
                filterOption = state.filterOption,
                selectedFolder = state.selectedFolder?.folderPath
            )

            _uiState.update { it.copy(displayedMedia = processed) }
        }
    }
}
