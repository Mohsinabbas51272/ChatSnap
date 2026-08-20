package com.example.chatsnap

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chatsnap.databinding.ActivityDownloaderBinding
import com.example.chatsnap.databinding.ItemDownloadHistoryBinding
import com.example.chatsnap.databinding.ItemDownloadTaskBinding
import com.example.chatsnap.models.AppDatabase
import com.example.chatsnap.models.DownloadHistoryEntity
import com.example.chatsnap.models.DownloadTask
import com.example.chatsnap.services.DownloadService
import com.google.firebase.auth.FirebaseAuth
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.widget.CheckBox
import android.widget.TextView
import coil.load
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloaderActivity : BaseActivity() {
    private lateinit var binding: ActivityDownloaderBinding
    private val downloadTasks: MutableList<DownloadTask> get() = DownloadService.sharedTasks
    private lateinit var taskAdapter: DownloadTaskAdapter
    private val historyItems = mutableListOf<DownloadHistoryEntity>()
    private lateinit var historyAdapter: DownloadHistoryAdapter
    private lateinit var db: AppDatabase

    private var maxConcurrentTasks: Int
        get() = DownloadService.maxConcurrentTasks
        set(value) { DownloadService.maxConcurrentTasks = value }

    // Quality mapping: Display label to yt-dlp format command string
    private val resolutions = listOf(
        "Best Available (MP4)" to "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best",
        "1080p Full HD (MP4)" to "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=1080]+bestaudio/best[height<=1080]",
        "720p HD (MP4)" to "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=720]+bestaudio/best[height<=720]",
        "360p Compact (MP4)" to "bestvideo[height<=360][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=360]+bestaudio/best[height<=360]",
        "2K / 1440p (MP4)" to "bestvideo[height<=1440][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=1440]+bestaudio/best[height<=1440]",
        "4K / 2160p (MP4)" to "bestvideo[height<=2160][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=2160]+bestaudio/best[height<=2160]",
        "Audio Only (MP3)" to "bestaudio"
    )

    // Concurrent limits mapping
    private val concurrentLimits = listOf(1, 2, 3, 4, 5)

    // Engine readiness flag
    private var engineReady = false

    // Notification permission launcher (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Notification permission denied — progress won't show in tray", Toast.LENGTH_LONG).show()
        }
    }

    // File picker launcher for importing links
    private val selectFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { handleImportedFile(it) }
    }

    // BroadcastReceiver to get updates from DownloadService
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val taskId = intent.getStringExtra(DownloadService.EXTRA_TASK_ID) ?: return

            when (intent.action) {
                DownloadService.BROADCAST_PROGRESS -> {
                    val task = downloadTasks.find { it.id == taskId } ?: return
                    task.progress = intent.getIntExtra(DownloadService.EXTRA_PROGRESS, 0)
                    task.speed = intent.getStringExtra(DownloadService.EXTRA_SPEED) ?: task.speed
                    task.size = intent.getStringExtra(DownloadService.EXTRA_SIZE) ?: task.size
                    task.eta = intent.getStringExtra(DownloadService.EXTRA_ETA) ?: task.eta
                    val title = intent.getStringExtra(DownloadService.EXTRA_TITLE)
                    if (!title.isNullOrEmpty() && !title.startsWith("Preparing")) {
                        task.title = title
                    }
                    // Update thumbnail if service discovered one
                    val thumbUrl = intent.getStringExtra(DownloadService.EXTRA_THUMBNAIL_URL)
                    if (!thumbUrl.isNullOrEmpty()) {
                        task.thumbnailUrl = thumbUrl
                    }
                    val idx = downloadTasks.indexOf(task)
                    if (idx != -1) taskAdapter.notifyItemChanged(idx)
                }
                DownloadService.BROADCAST_COMPLETED -> {
                    val task = downloadTasks.find { it.id == taskId } ?: return
                    task.status = DownloadTask.Status.COMPLETED
                    task.progress = 100
                    task.speed = "Done"
                    task.eta = ""
                    val title = intent.getStringExtra(DownloadService.EXTRA_TITLE)
                    if (!title.isNullOrEmpty()) task.title = title
                    // Update thumbnail if service discovered one
                    val thumbUrl = intent.getStringExtra(DownloadService.EXTRA_THUMBNAIL_URL)
                    if (!thumbUrl.isNullOrEmpty()) {
                        task.thumbnailUrl = thumbUrl
                    }
                    val idx = downloadTasks.indexOf(task)
                    if (idx != -1) taskAdapter.notifyItemChanged(idx)
                    Toast.makeText(this@DownloaderActivity, "✓ ${task.title}", Toast.LENGTH_SHORT).show()
                    // Reload history
                    loadHistory()
                    // Start next queued task
                    checkAndStartQueuedDownloads()
                }
                DownloadService.BROADCAST_FAILED -> {
                    val task = downloadTasks.find { it.id == taskId }
                    val errorMsg = intent.getStringExtra(DownloadService.EXTRA_ERROR_MESSAGE) ?: "Unknown error"

                    if (task != null) {
                        task.status = if (errorMsg == "Cancelled by user") DownloadTask.Status.CANCELLED else DownloadTask.Status.FAILED
                        task.speed = if (errorMsg == "Cancelled by user") "Cancelled" else "Error"
                        task.eta = ""
                        val title = intent.getStringExtra(DownloadService.EXTRA_TITLE)
                        if (!title.isNullOrEmpty()) task.title = title
                        val idx = downloadTasks.indexOf(task)
                        if (idx != -1) taskAdapter.notifyItemChanged(idx)
                    }
                    // Reload history
                    loadHistory()
                    // Start next queued task
                    checkAndStartQueuedDownloads()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getInstance(this)

        // Request notification permission on Android 13+
        requestNotificationPermission()

        // Set up toolbar
        binding.btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Setup Spinners
        val resolutionLabels = resolutions.map { it.first }
        val resolutionAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, resolutionLabels)
        resolutionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spnResolution.adapter = resolutionAdapter

        val limitLabels = concurrentLimits.map { "$it Task${if (it > 1) "s" else ""}" }
        val limitAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, limitLabels)
        limitAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spnConcurrentLimit.adapter = limitAdapter
        binding.spnConcurrentLimit.setSelection(1) // Default 2 concurrent tasks
        maxConcurrentTasks = concurrentLimits[1]

        binding.spnConcurrentLimit.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in concurrentLimits.indices) {
                    maxConcurrentTasks = concurrentLimits[position]
                    checkAndStartQueuedDownloads()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Setup Queue RecyclerView
        taskAdapter = DownloadTaskAdapter(downloadTasks)
        binding.rvQueue.layoutManager = LinearLayoutManager(this)
        binding.rvQueue.adapter = taskAdapter

        // Setup History RecyclerView
        historyAdapter = DownloadHistoryAdapter(historyItems) { entry ->
            // Delete single history item
            lifecycleScope.launch(Dispatchers.IO) {
                db.downloadHistoryDao().deleteById(entry.id)
                withContext(Dispatchers.Main) { loadHistory() }
            }
        }
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = historyAdapter

        // Paste functionality
        binding.btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipboard.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val text = clipData.getItemAt(0).text?.toString() ?: ""
                binding.etUrl.setText(text)
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        // Add to Queue action
        binding.btnDownload.setOnClickListener {
            fetchPlaylistAndHandleQueue()
        }

        // Import links from text file
        binding.btnImportFile.setOnClickListener {
            if (!engineReady) {
                Toast.makeText(this, "Engine is still initializing, please wait...", Toast.LENGTH_SHORT).show()
            } else {
                selectFileLauncher.launch("text/plain")
            }
        }

        // Update engine action
        binding.btnUpdateEngine.setOnClickListener {
            updateEngine()
        }

        // Clear history action
        binding.btnClearHistory.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                db.downloadHistoryDao().clearByUserId(getCurrentUserId())
                withContext(Dispatchers.Main) { loadHistory() }
            }
            Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
        }

        // Register broadcast receiver
        val filter = IntentFilter().apply {
            addAction(DownloadService.BROADCAST_PROGRESS)
            addAction(DownloadService.BROADCAST_COMPLETED)
            addAction(DownloadService.BROADCAST_FAILED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(downloadReceiver, filter)

        // Wait for engine initialization in background, then enable UI
        initializeEngine()

        // Load history
        loadHistory()

        // Prefill URL if passed via intent
        val passedUrl = intent.getStringExtra("download_url")
        if (!passedUrl.isNullOrEmpty()) {
            binding.etUrl.setText(passedUrl)
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh the queue adapter so persisted tasks are shown when navigating back
        taskAdapter.notifyDataSetChanged()
        // Refresh history when returning to the app
        loadHistory()
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(downloadReceiver)
        super.onDestroy()
    }

    private fun handleImportedFile(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val content = contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.bufferedReader().use { it.readText() }
                }
                if (content.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@DownloaderActivity, "The selected file is empty", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val lines = content.split("\n", "\r")
                val foundUrls = mutableListOf<String>()
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                        foundUrls.add(trimmed)
                    }
                }

                if (foundUrls.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@DownloaderActivity, "No valid HTTP/HTTPS links found in file", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    val selectedResIndex = binding.spnResolution.selectedItemPosition
                    val formatLabel = resolutions[selectedResIndex].first.substringBefore(" (").substringBefore(" Full").substringBefore(" HD")
                    val formatOption = resolutions[selectedResIndex].second
                    maxConcurrentTasks = concurrentLimits[binding.spnConcurrentLimit.selectedItemPosition]

                    val newTasksList = mutableListOf<DownloadTask>()
                    var delayMs = 0L
                    for (url in foundUrls) {
                        val taskId = "dl_task_${System.currentTimeMillis() + delayMs}"
                        delayMs += 5

                        val isYt = url.lowercase().contains("youtube.com") || url.lowercase().contains("youtu.be")
                        val ytId = if (isYt) extractYoutubeId(url) else null
                        val customThumbnail = if (!ytId.isNullOrEmpty()) "https://img.youtube.com/vi/$ytId/mqdefault.jpg" else null

                        val newTask = DownloadTask(
                            id = taskId,
                            url = url,
                            formatLabel = formatLabel,
                            formatOption = formatOption,
                            isPlaylist = false,
                            title = "Queued Video...",
                            thumbnailUrl = customThumbnail,
                            userId = getCurrentUserId()
                        )
                        newTasksList.add(newTask)

                        // Fetch og:image thumbnail in background for non-YouTube platforms
                        if (newTask.thumbnailUrl.isNullOrEmpty()) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val thumb = fetchOgImageThumbnail(url)
                                if (!thumb.isNullOrEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        newTask.thumbnailUrl = thumb
                                        val idx = downloadTasks.indexOf(newTask)
                                        if (idx != -1) taskAdapter.notifyItemChanged(idx)
                                    }
                                }
                            }
                        }
                    }

                    downloadTasks.addAll(0, newTasksList)
                    taskAdapter.notifyDataSetChanged()
                    binding.rvQueue.scrollToPosition(0)
                    Toast.makeText(this@DownloaderActivity, "Imported ${foundUrls.size} links to Download Queue", Toast.LENGTH_LONG).show()
                    checkAndStartQueuedDownloads()
                }

            } catch (e: Exception) {
                Log.e("DownloaderActivity", "Failed to import file: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DownloaderActivity, "Error reading file: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun initializeEngine() {
        binding.btnDownload.isEnabled = false
        binding.btnDownload.text = "Initializing Engine..."

        lifecycleScope.launch(Dispatchers.IO) {
            val ready = ChatSnapApplication.awaitInitialization()
            val initError = ChatSnapApplication.getInitError()

            withContext(Dispatchers.Main) {
                if (ready) {
                    engineReady = true
                    binding.btnDownload.isEnabled = true
                    binding.btnDownload.text = "Add to Queue"
                    Log.d("DownloaderActivity", "Download engine is ready")
                } else {
                    binding.btnDownload.isEnabled = false
                    binding.btnDownload.text = "Engine Init Failed"
                    val errorMsg = initError ?: "Unknown initialization error"
                    Toast.makeText(this@DownloaderActivity, "Engine failed:\n$errorMsg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun getCurrentUserId(): String {
        return FirebaseAuth.getInstance().currentUser?.uid ?: ""
    }

    private fun loadHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            val entries = db.downloadHistoryDao().getByUserId(getCurrentUserId())
            withContext(Dispatchers.Main) {
                historyItems.clear()
                historyItems.addAll(entries)
                historyAdapter.notifyDataSetChanged()
                binding.tvHistoryEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                binding.rvHistory.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun isYoutubePlaylist(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.contains("youtube.com") || lower.contains("youtu.be")) &&
                (lower.contains("list=") || lower.contains("/playlist"))
    }

    private fun fetchPlaylistAndHandleQueue() {
        if (!engineReady) {
            Toast.makeText(this, "Engine is still initializing, please wait...", Toast.LENGTH_SHORT).show()
            return
        }

        val url = binding.etUrl.text.toString().trim()
        if (url.isEmpty()) {
            binding.etUrl.error = "Please enter a valid link"
            return
        }

        // Hide keyboard
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etUrl.windowToken, 0)

        binding.btnDownload.isEnabled = false
        binding.btnDownload.text = "Fetching link info..."

        val urlLower = url.lowercase()
        val isYt = urlLower.contains("youtube.com") || urlLower.contains("youtu.be")
        val isYtPlaylist = isYoutubePlaylist(url)

        // For non-playlist URLs (Instagram, TikTok, Twitter/X, and single YouTube videos), skip playlist fetch
        if (!isYt || !isYtPlaylist) {
            binding.btnDownload.isEnabled = true
            binding.btnDownload.text = "Queue Download"

            val taskId = "dl_task_${System.currentTimeMillis()}"
            val selectedResIndex = binding.spnResolution.selectedItemPosition
            val formatLabel = resolutions[selectedResIndex].first.substringBefore(" (").substringBefore(" Full").substringBefore(" HD")
            val formatOption = resolutions[selectedResIndex].second
            maxConcurrentTasks = concurrentLimits[binding.spnConcurrentLimit.selectedItemPosition]

            val ytId = if (isYt) extractYoutubeId(url) else null
            val customThumbnail = if (!ytId.isNullOrEmpty()) "https://img.youtube.com/vi/$ytId/mqdefault.jpg" else null

            val newTask = DownloadTask(
                id = taskId,
                url = url,
                formatLabel = formatLabel,
                formatOption = formatOption,
                isPlaylist = false,
                title = "Queued Video...",
                thumbnailUrl = customThumbnail,
                userId = getCurrentUserId()
            )
            downloadTasks.add(0, newTask)
            taskAdapter.notifyItemInserted(0)
            binding.rvQueue.scrollToPosition(0)
            binding.etUrl.setText("")
            Toast.makeText(this, "Added to Download Queue", Toast.LENGTH_SHORT).show()

            // Fetch og:image thumbnail in background for non-YouTube platforms (if not already set)
            if (newTask.thumbnailUrl.isNullOrEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val thumb = fetchOgImageThumbnail(url)
                    if (!thumb.isNullOrEmpty()) {
                        withContext(Dispatchers.Main) {
                            newTask.thumbnailUrl = thumb
                            val idx = downloadTasks.indexOf(newTask)
                            if (idx != -1) taskAdapter.notifyItemChanged(idx)
                        }
                    }
                }
            }

            checkAndStartQueuedDownloads()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Ensure engine is ready
                ChatSnapApplication.awaitInitialization()

                val request = com.yausername.youtubedl_android.YoutubeDLRequest(url)
                request.addOption("--flat-playlist")
                request.addOption("-j")
                request.addOption("--no-check-certificates")
                request.addOption("--no-cache-dir")
                request.addOption("--socket-timeout", "15")
                request.addOption("--user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")

                Log.d("DownloaderActivity", "Fetching flat-playlist dump for: $url")
                val response = YoutubeDL.getInstance().execute(request)
                val responseOutput = response.out

                val items = mutableListOf<PlaylistItem>()
                if (!responseOutput.isNullOrBlank()) {
                    val lines = responseOutput.split("\n")
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                            try {
                                val json = org.json.JSONObject(trimmed)
                                val id = json.optString("id", "")
                                val title = json.optString("title", "Untitled Video")
                                var videoUrl = json.optString("url", "")
                                var thumbnail = json.optString("thumbnail", "")
                                if (thumbnail.isEmpty() && id.isNotEmpty() && url.contains("youtube")) {
                                    thumbnail = "https://img.youtube.com/vi/$id/mqdefault.jpg"
                                }
                                if (videoUrl.isEmpty() && id.isNotEmpty()) {
                                    videoUrl = "https://www.youtube.com/watch?v=$id"
                                } else if (!videoUrl.startsWith("http") && id.isNotEmpty() && url.contains("youtube")) {
                                    videoUrl = "https://www.youtube.com/watch?v=$id"
                                } else if (videoUrl.isEmpty()) {
                                    videoUrl = url
                                }
                                if (id.isNotEmpty() || title.isNotEmpty()) {
                                    items.add(PlaylistItem(id = id, title = title, url = videoUrl, thumbnailUrl = thumbnail))
                                }
                            } catch (e: Exception) {
                                Log.e("DownloaderActivity", "Failed to parse JSON line: $trimmed", e)
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    binding.btnDownload.isEnabled = true
                    binding.btnDownload.text = "Queue Download"

                    if (items.isEmpty()) {
                        // Extract YT ID if possible for single video
                        val ytId = extractYoutubeId(url)
                        val thumb = if (!ytId.isNullOrEmpty()) "https://img.youtube.com/vi/$ytId/mqdefault.jpg" else null
                        addSingleTaskToQueue(url, customThumbnail = thumb)
                    } else if (items.size == 1) {
                        addSingleTaskToQueue(items[0].url, items[0].title, items[0].thumbnailUrl)
                        binding.etUrl.setText("")
                    } else {
                        showPlaylistSelectionBottomSheet(items)
                    }
                }

            } catch (e: Exception) {
                Log.e("DownloaderActivity", "Failed to fetch playlist info", e)
                withContext(Dispatchers.Main) {
                    binding.btnDownload.isEnabled = true
                    binding.btnDownload.text = "Queue Download"
                    val ytId = extractYoutubeId(url)
                    val thumb = if (!ytId.isNullOrEmpty()) "https://img.youtube.com/vi/$ytId/mqdefault.jpg" else null
                    addSingleTaskToQueue(url, customThumbnail = thumb)
                    binding.etUrl.setText("")
                }
            }
        }
    }

    private fun extractYoutubeId(url: String): String? {
        val pattern = "(?<=watch\\?v=|/videos/|embed/|youtu.be/|/v/|/e/|watch\\?v%3D|watch\\?feature=player_embedded&v=|%2Fvideos%2F|embed#|utm_source|/shorts/)[^#\\&\\?\\n]*"
        val compiledPattern = java.util.regex.Pattern.compile(pattern)
        val matcher = compiledPattern.matcher(url)
        return if (matcher.find()) {
            val id = matcher.group()
            if (id.length == 11) id else null
        } else null
    }

    /**
     * Fetches the og:image meta tag from a URL page to use as thumbnail.
     * Uses Facebook's crawler User-Agent so platforms serve rich metadata.
     * Called from IO dispatcher.
     */
    private fun fetchOgImageThumbnail(url: String): String? {
        try {
            val urlLower = url.lowercase()
            if (urlLower.contains("tiktok.com")) {
                val oembedUrl = "https://www.tiktok.com/oembed?url=" + java.net.URLEncoder.encode(url, "UTF-8")
                val conn = java.net.URL(oembedUrl).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val json = org.json.JSONObject(response)
                    val thumb = json.optString("thumbnail_url", "")
                    if (thumb.isNotEmpty()) return thumb
                }
                conn.disconnect()
            } else if (urlLower.contains("twitter.com") || urlLower.contains("x.com")) {
                val oembedUrl = "https://publish.twitter.com/oembed?url=" + java.net.URLEncoder.encode(url, "UTF-8")
                val conn = java.net.URL(oembedUrl).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val json = org.json.JSONObject(response)
                    val thumb = json.optString("thumbnail_url", "")
                    if (thumb.isNotEmpty()) return thumb
                }
                conn.disconnect()
            }

            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)")
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.instanceFollowRedirects = true

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                conn.disconnect()
                return null
            }

            val html = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            // Try both attribute orders for og:image
            val patterns = listOf(
                """<meta\s+(?:property|name)=["']og:image["']\s+content=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE),
                """<meta\s+content=["']([^"']+)["']\s+(?:property|name)=["']og:image["']""".toRegex(RegexOption.IGNORE_CASE)
            )
            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val imageUrl = match.groupValues[1].replace("&amp;", "&")
                    if (imageUrl.startsWith("http")) {
                        return imageUrl
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DownloaderActivity", "Failed to fetch thumbnail for $url: ${e.message}")
        }
        return null
    }

    private fun addSingleTaskToQueue(url: String, customTitle: String? = null, customThumbnail: String? = null) {
        val selectedResIndex = binding.spnResolution.selectedItemPosition
        val formatLabel = resolutions[selectedResIndex].first.substringBefore(" (").substringBefore(" Full").substringBefore(" HD")
        val formatOption = resolutions[selectedResIndex].second
        maxConcurrentTasks = concurrentLimits[binding.spnConcurrentLimit.selectedItemPosition]

        val taskId = "dl_task_${System.currentTimeMillis()}"
        val newTask = DownloadTask(
            id = taskId,
            url = url,
            formatLabel = formatLabel,
            formatOption = formatOption,
            isPlaylist = false,
            title = customTitle ?: "Queued Video...",
            thumbnailUrl = customThumbnail,
            userId = getCurrentUserId()
        )

        downloadTasks.add(0, newTask)
        taskAdapter.notifyItemInserted(0)
        binding.rvQueue.scrollToPosition(0)
        Toast.makeText(this, "Added to Download Queue", Toast.LENGTH_SHORT).show()
        checkAndStartQueuedDownloads()
    }

    private fun showPlaylistSelectionBottomSheet(items: List<PlaylistItem>) {
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_playlist_selection, null)
        bottomSheet.setContentView(view)

        val tvSubtitle = view.findViewById<TextView>(R.id.tvSubtitle)
        val cbSelectAll = view.findViewById<CheckBox>(R.id.cbSelectAll)
        val rvPlaylistItems = view.findViewById<RecyclerView>(R.id.rvPlaylistItems)
        val btnQueueSelected = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnQueueSelected)

        tvSubtitle.text = "${items.size} videos found in playlist"

        val adapter = PlaylistItemsAdapter(items) {
            val selectedCount = items.count { it.isSelected }
            btnQueueSelected.text = "Queue Selected ($selectedCount)"
            cbSelectAll.setOnCheckedChangeListener(null)
            cbSelectAll.isChecked = selectedCount == items.size
            setupSelectAllListener(cbSelectAll, items, rvPlaylistItems)
        }
        rvPlaylistItems.layoutManager = LinearLayoutManager(this)
        rvPlaylistItems.adapter = adapter

        cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
            for (item in items) {
                item.isSelected = isChecked
            }
            adapter.notifyDataSetChanged()
            val selectedCount = items.count { it.isSelected }
            btnQueueSelected.text = "Queue Selected ($selectedCount)"
        }

        btnQueueSelected.text = "Queue Selected (${items.size})"
        btnQueueSelected.setOnClickListener {
            val selectedItems = items.filter { it.isSelected }
            if (selectedItems.isEmpty()) {
                Toast.makeText(this, "Please select at least one video", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val selectedResIndex = binding.spnResolution.selectedItemPosition
            val formatLabel = resolutions[selectedResIndex].first.substringBefore(" (").substringBefore(" Full").substringBefore(" HD")
            val formatOption = resolutions[selectedResIndex].second
            maxConcurrentTasks = concurrentLimits[binding.spnConcurrentLimit.selectedItemPosition]

            val newTasksList = mutableListOf<DownloadTask>()
            var delayMs = 0L
            for (video in selectedItems) {
                val taskId = "dl_task_${System.currentTimeMillis() + delayMs}"
                delayMs += 5
                
                val newTask = DownloadTask(
                    id = taskId,
                    url = video.url,
                    formatLabel = formatLabel,
                    formatOption = formatOption,
                    isPlaylist = false,
                    title = video.title,
                    thumbnailUrl = video.thumbnailUrl,
                    userId = getCurrentUserId()
                )
                newTasksList.add(newTask)
            }
            
            downloadTasks.addAll(0, newTasksList)
            taskAdapter.notifyDataSetChanged()
            binding.rvQueue.scrollToPosition(0)
            binding.etUrl.setText("")
            Toast.makeText(this, "Added ${selectedItems.size} tasks to Queue", Toast.LENGTH_SHORT).show()
            bottomSheet.dismiss()

            checkAndStartQueuedDownloads()
        }

        bottomSheet.show()
    }

    private fun setupSelectAllListener(cbSelectAll: CheckBox, items: List<PlaylistItem>, rvPlaylistItems: RecyclerView) {
        cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
            for (item in items) {
                item.isSelected = isChecked
            }
            rvPlaylistItems.adapter?.notifyDataSetChanged()
            val btnQueueSelected = cbSelectAll.rootView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnQueueSelected)
            val selectedCount = items.count { it.isSelected }
            btnQueueSelected?.text = "Queue Selected ($selectedCount)"
        }
    }

    private fun checkAndStartQueuedDownloads() {
        val selectedPos = binding.spnConcurrentLimit.selectedItemPosition
        if (selectedPos in concurrentLimits.indices) {
            maxConcurrentTasks = concurrentLimits[selectedPos]
        }
        val activeCount = downloadTasks.count { it.status == DownloadTask.Status.DOWNLOADING }
        val availableSlots = maxConcurrentTasks - activeCount
        if (availableSlots <= 0) return

        val queuedTasks = downloadTasks.filter { it.status == DownloadTask.Status.QUEUED }.take(availableSlots)
        for (task in queuedTasks) {
            startDownloadService(task)
        }
    }

    private fun startDownloadService(task: DownloadTask) {
        task.status = DownloadTask.Status.DOWNLOADING
        val index = downloadTasks.indexOf(task)
        if (index != -1) taskAdapter.notifyItemChanged(index)

        // Start the foreground service
        val intent = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START_DOWNLOAD
            putExtra(DownloadService.EXTRA_TASK_ID, task.id)
            putExtra(DownloadService.EXTRA_URL, task.url)
            putExtra(DownloadService.EXTRA_FORMAT_LABEL, task.formatLabel)
            putExtra(DownloadService.EXTRA_FORMAT_OPTION, task.formatOption)
            putExtra(DownloadService.EXTRA_IS_PLAYLIST, task.isPlaylist)
            putExtra(DownloadService.EXTRA_USER_ID, getCurrentUserId())
            putExtra(DownloadService.EXTRA_THUMBNAIL_URL, task.thumbnailUrl)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        Log.d("DownloaderActivity", "Started DownloadService for: ${task.url}")
    }

    private fun cancelTask(task: DownloadTask) {
        // Send cancel intent to service
        val intent = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_CANCEL_DOWNLOAD
            putExtra(DownloadService.EXTRA_TASK_ID, task.id)
        }
        startService(intent)

        task.status = DownloadTask.Status.CANCELLED
        task.speed = "Cancelled"
        task.eta = ""

        val index = downloadTasks.indexOf(task)
        if (index != -1) taskAdapter.notifyItemChanged(index)

        Toast.makeText(this, "Task Cancelled", Toast.LENGTH_SHORT).show()
        checkAndStartQueuedDownloads()
    }

    private fun updateEngine() {
        if (!engineReady) {
            Toast.makeText(this, "Engine is still initializing, please wait...", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnUpdateEngine.isEnabled = false
        binding.btnUpdateEngine.text = "Updating..."
        Toast.makeText(this, "Checking for engine updates...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val updateStatus = YoutubeDL.getInstance().updateYoutubeDL(
                    this@DownloaderActivity,
                    YoutubeDL.UpdateChannel.STABLE
                )
                withContext(Dispatchers.Main) {
                    binding.btnUpdateEngine.isEnabled = true
                    binding.btnUpdateEngine.text = "Update yt-dlp Engine"

                    when (updateStatus) {
                        YoutubeDL.UpdateStatus.DONE ->
                            Toast.makeText(this@DownloaderActivity, "yt-dlp updated!", Toast.LENGTH_LONG).show()
                        YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE ->
                            Toast.makeText(this@DownloaderActivity, "yt-dlp is already up to date!", Toast.LENGTH_SHORT).show()
                        else ->
                            Toast.makeText(this@DownloaderActivity, "Engine update completed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("DownloaderActivity", "Failed to update yt-dlp", e)
                withContext(Dispatchers.Main) {
                    binding.btnUpdateEngine.isEnabled = true
                    binding.btnUpdateEngine.text = "Update yt-dlp Engine"
                    Toast.makeText(this@DownloaderActivity, "Update Failed!\n${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ─────────── Queue Adapter ───────────

    inner class DownloadTaskAdapter(private val tasks: List<DownloadTask>) :
        RecyclerView.Adapter<DownloadTaskAdapter.TaskViewHolder>() {

        inner class TaskViewHolder(val itemBinding: ItemDownloadTaskBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
            val itemBinding = ItemDownloadTaskBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return TaskViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
            val task = tasks[position]
            val b = holder.itemBinding

            b.tvTaskTitle.text = task.title
            b.tvTaskQuality.text = task.formatLabel

            // Load thumbnail
            if (!task.thumbnailUrl.isNullOrEmpty()) {
                b.ivTaskThumbnail.load(task.thumbnailUrl) {
                    placeholder(android.R.drawable.ic_menu_gallery)
                    error(android.R.drawable.ic_menu_gallery)
                }
            } else {
                b.ivTaskThumbnail.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            // Progress bar
            b.pbTaskProgress.progress = task.progress
            if (task.status == DownloadTask.Status.DOWNLOADING) {
                b.pbTaskProgress.isIndeterminate = task.progress == 0
                b.tvTaskStats.visibility = View.VISIBLE
                val statsList = mutableListOf<String>()
                if (!task.speed.isNullOrBlank()) statsList.add(task.speed)
                if (!task.size.isNullOrBlank()) statsList.add(task.size)
                if (!task.eta.isNullOrBlank()) statsList.add(task.eta)
                b.tvTaskStats.text = statsList.joinToString(" • ")
            } else {
                b.pbTaskProgress.isIndeterminate = false
                b.tvTaskStats.visibility = View.GONE
            }

            // Status badge
            b.tvTaskStatus.text = when (task.status) {
                DownloadTask.Status.QUEUED -> "Queued"
                DownloadTask.Status.DOWNLOADING -> "Downloading (${task.progress}%)"
                DownloadTask.Status.COMPLETED -> "Completed"
                DownloadTask.Status.FAILED -> "Failed"
                DownloadTask.Status.CANCELLED -> "Cancelled"
            }

            b.tvTaskStatus.setTextColor(
                when (task.status) {
                    DownloadTask.Status.QUEUED -> 0xFF8E8E93.toInt()
                    DownloadTask.Status.DOWNLOADING -> 0xFF007AFF.toInt()
                    DownloadTask.Status.COMPLETED -> 0xFF34C759.toInt()
                    DownloadTask.Status.FAILED -> 0xFFFF3B30.toInt()
                    DownloadTask.Status.CANCELLED -> 0xFFFF9500.toInt()
                }
            )

            // Cancel button
            if (task.status == DownloadTask.Status.DOWNLOADING || task.status == DownloadTask.Status.QUEUED) {
                b.btnCancelTask.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                b.btnCancelTask.setOnClickListener { cancelTask(task) }
                b.btnCancelTask.visibility = View.VISIBLE
            } else {
                b.btnCancelTask.visibility = View.GONE
            }
        }

        override fun getItemCount() = tasks.size
    }

    // ─────────── History Adapter ───────────

    inner class DownloadHistoryAdapter(
        private val items: List<DownloadHistoryEntity>,
        private val onDelete: (DownloadHistoryEntity) -> Unit
    ) : RecyclerView.Adapter<DownloadHistoryAdapter.HistoryViewHolder>() {

        private val dateFormatter = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())

        inner class HistoryViewHolder(val binding: ItemDownloadHistoryBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
            val b = ItemDownloadHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return HistoryViewHolder(b)
        }

        override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
            val entry = items[position]
            val b = holder.binding

            b.tvHistoryTitle.text = entry.title
            b.tvHistoryFormat.text = entry.formatLabel
            b.tvHistoryDate.text = dateFormatter.format(Date(entry.timestamp))

            // Load thumbnail
            if (!entry.thumbnailUrl.isNullOrEmpty()) {
                b.ivHistoryThumbnail.load(entry.thumbnailUrl) {
                    placeholder(android.R.drawable.ic_menu_gallery)
                    error(android.R.drawable.ic_menu_gallery)
                }
            } else {
                b.ivHistoryThumbnail.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            when (entry.status) {
                "COMPLETED" -> {
                    b.tvHistoryStatus.text = "Completed"
                    b.tvHistoryStatus.setTextColor(0xFF34C759.toInt())
                    b.ivHistoryIcon.setImageResource(android.R.drawable.stat_sys_download_done)
                    b.ivHistoryIcon.setColorFilter(0xFF34C759.toInt())
                }
                "FAILED" -> {
                    b.tvHistoryStatus.text = "Failed"
                    b.tvHistoryStatus.setTextColor(0xFFFF3B30.toInt())
                    b.ivHistoryIcon.setImageResource(android.R.drawable.stat_notify_error)
                    b.ivHistoryIcon.setColorFilter(0xFFFF3B30.toInt())
                }
                "CANCELLED" -> {
                    b.tvHistoryStatus.text = "Cancelled"
                    b.tvHistoryStatus.setTextColor(0xFFFF9500.toInt())
                    b.ivHistoryIcon.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    b.ivHistoryIcon.setColorFilter(0xFFFF9500.toInt())
                }
            }

            b.btnDeleteHistory.setOnClickListener { onDelete(entry) }

            b.root.setOnClickListener {
                if (entry.status == "FAILED" && !entry.errorMessage.isNullOrEmpty()) {
                    Toast.makeText(this@DownloaderActivity, "Error: ${entry.errorMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }

        override fun getItemCount() = items.size
    }
}

// DownloadTask model is now in com.example.chatsnap.models.DownloadTask

// Playlist item data class
data class PlaylistItem(
    val id: String,
    val title: String,
    val url: String,
    val thumbnailUrl: String,
    var isSelected: Boolean = true
)

// RecyclerView Adapter for playlist video items inside selection dialog
class PlaylistItemsAdapter(
    private val items: List<PlaylistItem>,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<PlaylistItemsAdapter.PlaylistViewHolder>() {

    inner class PlaylistViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val cbVideo: CheckBox = view.findViewById(R.id.cbVideo)
        val tvVideoTitle: TextView = view.findViewById(R.id.tvVideoTitle)
        val ivVideoThumbnail: android.widget.ImageView? = view.findViewById(R.id.ivVideoThumbnail)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist_selection_video, parent, false)
        return PlaylistViewHolder(v)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        val item = items[position]
        holder.tvVideoTitle.text = item.title

        // Load thumbnail
        val ivThumbnail = holder.ivVideoThumbnail
        if (ivThumbnail != null) {
            if (item.thumbnailUrl.isNotEmpty()) {
                ivThumbnail.load(item.thumbnailUrl) {
                    placeholder(android.R.drawable.ic_menu_gallery)
                    error(android.R.drawable.ic_menu_gallery)
                }
            } else {
                ivThumbnail.setImageResource(android.R.drawable.ic_menu_gallery)
            }
        }

        holder.cbVideo.setOnCheckedChangeListener(null)
        holder.cbVideo.isChecked = item.isSelected

        holder.cbVideo.setOnCheckedChangeListener { _, isChecked ->
            item.isSelected = isChecked
            onSelectionChanged()
        }

        holder.itemView.setOnClickListener {
            holder.cbVideo.isChecked = !holder.cbVideo.isChecked
        }
    }

    override fun getItemCount() = items.size
}
