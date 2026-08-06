package com.example.chatsnap.media.ui

import android.content.Context
import android.content.Intent
import android.util.TypedValue
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chatsnap.media.model.LocalMediaItem
import com.example.chatsnap.media.permissions.MediaPermissionManager
import com.example.chatsnap.media.ui.components.*
import com.example.chatsnap.media.viewmodel.AudioPlayerViewModel
import com.example.chatsnap.media.viewmodel.MediaHubViewModel
import kotlinx.coroutines.launch

fun resolveThemeColor(context: Context, attrId: Int, defaultColor: Color): Color {
    val typedValue = TypedValue()
    return if (context.theme.resolveAttribute(attrId, typedValue, true)) {
        val colorInt = if (typedValue.resourceId != 0) {
            ContextCompat.getColor(context, typedValue.resourceId)
        } else {
            typedValue.data
        }
        Color(colorInt)
    } else {
        defaultColor
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaHubScreen(
    onBackClick: () -> Unit = {},
    mediaHubViewModel: MediaHubViewModel = viewModel(),
    audioPlayerViewModel: AudioPlayerViewModel = viewModel()
) {
    val context = LocalContext.current
    val hubState by mediaHubViewModel.uiState.collectAsState()
    val audioState by audioPlayerViewModel.uiState.collectAsState()

    // Dynamic Theme Color Resolution
    val primaryColor = remember(context) {
        resolveThemeColor(context, com.google.android.material.R.attr.colorPrimary, Color(0xFFFFFC00))
    }
    val backgroundColor = remember(context) {
        resolveThemeColor(context, android.R.attr.windowBackground, Color(0xFF121212))
    }
    val surfaceColor = remember(context) {
        resolveThemeColor(context, com.google.android.material.R.attr.colorSurface, Color(0xFF1E1E2E))
    }
    val onSurfaceColor = remember(context) {
        resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurface, Color.White)
    }
    val onPrimaryColor = remember(context) {
        resolveThemeColor(context, com.google.android.material.R.attr.colorOnPrimary, Color.Black)
    }

    var hasPermissions by remember { mutableStateOf(MediaPermissionManager.hasPermissions(context)) }
    var showFilterSortSheet by remember { mutableStateOf(false) }
    var isSearchActive by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = hubState.selectedTab) { 2 }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != hubState.selectedTab) {
            mediaHubViewModel.selectTab(pagerState.currentPage)
        }
    }

    LaunchedEffect(hubState.selectedTab) {
        if (pagerState.currentPage != hubState.selectedTab) {
            pagerState.animateScrollToPage(hubState.selectedTab)
        }
    }

    val handleBackPress: () -> Unit = {
        if (audioState.isExpanded) {
            audioPlayerViewModel.setExpanded(false)
        } else if (isSearchActive) {
            isSearchActive = false
            mediaHubViewModel.updateSearchQuery("")
        } else if (hubState.selectedFolder != null) {
            mediaHubViewModel.selectFolder(null)
        } else {
            onBackClick()
        }
    }

    BackHandler(onBack = handleBackPress)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { map ->
        val granted = map.values.all { it }
        hasPermissions = granted
        if (granted) {
            mediaHubViewModel.loadMediaData()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            permissionLauncher.launch(MediaPermissionManager.getRequiredPermissions())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        if (!hasPermissions) {
            // Permission Grant View
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PermMedia,
                    contentDescription = "Permissions",
                    tint = primaryColor,
                    modifier = Modifier.size(72.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Storage Permission Required",
                    color = onSurfaceColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "ChatSnap Media Hub needs permission to access audio and video files stored on your device.",
                    color = onSurfaceColor.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { permissionLauncher.launch(MediaPermissionManager.getRequiredPermissions()) },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                ) {
                    Text("Grant Permission", color = onPrimaryColor, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar with Search Icon
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = handleBackPress) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = onSurfaceColor
                        )
                    }

                    if (isSearchActive) {
                        OutlinedTextField(
                            value = hubState.searchQuery,
                            onValueChange = { mediaHubViewModel.updateSearchQuery(it) },
                            placeholder = { Text("Search songs, videos, folders...", color = onSurfaceColor.copy(alpha = 0.5f), fontSize = 14.sp) },
                            singleLine = true,
                            trailingIcon = {
                                if (hubState.searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { mediaHubViewModel.updateSearchQuery("") }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = onSurfaceColor)
                                    }
                                }
                            },
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryColor,
                                unfocusedBorderColor = surfaceColor,
                                focusedContainerColor = surfaceColor,
                                unfocusedContainerColor = surfaceColor,
                                focusedTextColor = onSurfaceColor,
                                unfocusedTextColor = onSurfaceColor
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        )
                    } else {
                        Text(
                            text = "Folders",
                            color = onSurfaceColor,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = onSurfaceColor
                            )
                        }
                    }

                    IconButton(onClick = { showFilterSortSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = "Sort & Filter",
                            tint = primaryColor
                        )
                    }
                }

                // Active Selected Folder Chip / Banner
                hubState.selectedFolder?.let { folder ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(surfaceColor)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = "Folder",
                            tint = primaryColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = folder.folderName,
                            color = onSurfaceColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { mediaHubViewModel.selectFolder(null) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Folder",
                                tint = onSurfaceColor.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                // 2 Tabs: Video Directories & Audio Directories
                TabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = Color.Transparent,
                    contentColor = primaryColor,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                            color = primaryColor,
                            height = 3.dp
                        )
                    }
                ) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(0)
                            }
                        },
                        text = {
                            Text(
                                text = "Video Directories",
                                fontWeight = FontWeight.Bold,
                                color = if (pagerState.currentPage == 0) primaryColor else onSurfaceColor.copy(alpha = 0.7f)
                            )
                        }
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(1)
                            }
                        },
                        text = {
                            Text(
                                text = "Audio Directories",
                                fontWeight = FontWeight.Bold,
                                color = if (pagerState.currentPage == 1) primaryColor else onSurfaceColor.copy(alpha = 0.7f)
                            )
                        }
                    )
                }

                // Swipeable Main Content Pager Area
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { page ->
                    if (hubState.isLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = primaryColor)
                        }
                    } else {
                        when (page) {
                            0 -> {
                                if (hubState.selectedFolder == null) {
                                    val filteredFolders = if (hubState.searchQuery.isBlank()) {
                                        hubState.videoFolders
                                    } else {
                                        hubState.videoFolders.filter {
                                            it.folderName.contains(hubState.searchQuery, ignoreCase = true)
                                        }
                                    }
                                    FoldersTabContent(
                                        folders = filteredFolders,
                                        onFolderClick = { folder ->
                                            mediaHubViewModel.selectFolder(folder)
                                        }
                                    )
                                } else {
                                    VideosTabContent(
                                        videos = hubState.displayedMedia,
                                        onVideoClick = { video ->
                                            val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                                                putExtra("EXTRA_MEDIA_ITEM", video)
                                            }
                                            context.startActivity(intent)
                                        },
                                        onToggleFavorite = { video -> mediaHubViewModel.toggleFavorite(video) }
                                    )
                                }
                            }
                            1 -> {
                                if (hubState.selectedFolder == null) {
                                    val filteredFolders = if (hubState.searchQuery.isBlank()) {
                                        hubState.audioFolders
                                    } else {
                                        hubState.audioFolders.filter {
                                            it.folderName.contains(hubState.searchQuery, ignoreCase = true)
                                        }
                                    }
                                    FoldersTabContent(
                                        folders = filteredFolders,
                                        onFolderClick = { folder ->
                                            mediaHubViewModel.selectFolder(folder)
                                        }
                                    )
                                } else {
                                    AudioTabContent(
                                        audioList = hubState.displayedMedia,
                                        onAudioClick = { tracks, startIndex ->
                                            audioPlayerViewModel.playTrackList(tracks, startIndex)
                                        },
                                        onToggleFavorite = { audio -> mediaHubViewModel.toggleFavorite(audio) }
                                    )
                                }
                            }
                        }
                    }
                }

                // Mini Player Reservation Spacer
                if (audioState.currentTrack != null) {
                    Spacer(modifier = Modifier.height(64.dp))
                }
            }

            // Floating Mini Audio Player Dock
            MiniAudioPlayer(
                currentTrack = audioState.currentTrack,
                isPlaying = audioState.isPlaying,
                onPlayPauseClick = { audioPlayerViewModel.togglePlayPause() },
                onNextClick = { audioPlayerViewModel.playNext() },
                onExpandClick = { audioPlayerViewModel.setExpanded(true) },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // Full Audio Player Overlay
        if (audioState.isExpanded && audioState.currentTrack != null) {
            FullAudioPlayerScreen(
                currentTrack = audioState.currentTrack!!,
                isPlaying = audioState.isPlaying,
                currentPositionMs = audioState.currentPositionMs,
                durationMs = audioState.durationMs,
                isShuffle = audioState.isShuffle,
                isRepeat = audioState.isRepeat,
                onPlayPauseClick = { audioPlayerViewModel.togglePlayPause() },
                onNextClick = { audioPlayerViewModel.playNext() },
                onPrevClick = { audioPlayerViewModel.playPrevious() },
                onSeekTo = { pos -> audioPlayerViewModel.seekTo(pos) },
                onToggleShuffle = { audioPlayerViewModel.toggleShuffle() },
                onToggleRepeat = { audioPlayerViewModel.toggleRepeat() },
                onToggleFavorite = { track -> mediaHubViewModel.toggleFavorite(track) },
                onDismiss = { audioPlayerViewModel.setExpanded(false) }
            )
        }

        // Filter / Sort Sheet
        if (showFilterSortSheet) {
            FilterSortBottomSheet(
                currentSort = hubState.sortOption,
                currentFilter = hubState.filterOption,
                onSortSelected = { sort ->
                    mediaHubViewModel.updateSortOption(sort)
                    showFilterSortSheet = false
                },
                onFilterSelected = { filter ->
                    mediaHubViewModel.updateFilterOption(filter)
                    showFilterSortSheet = false
                },
                onDismiss = { showFilterSortSheet = false }
            )
        }
    }
}
