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
import com.example.chatsnap.services.DownloadService
import com.google.firebase.auth.FirebaseAuth
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloaderActivity : BaseActivity() {
    private lateinit var binding: ActivityDownloaderBinding
    private val downloadTasks = mutableListOf<DownloadTask>()
    private lateinit var taskAdapter: DownloadTaskAdapter
    private val historyItems = mutableListOf<DownloadHistoryEntity>()
    private lateinit var historyAdapter: DownloadHistoryAdapter
    private lateinit var db: AppDatabase

    private var maxConcurrentTasks = 2

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
            addToQueue()
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
    }

    override fun onResume() {
        super.onResume()
        // Refresh history when returning to the app
        loadHistory()
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(downloadReceiver)
        super.onDestroy()
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

    private fun addToQueue() {
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

        // Gather selection configs
        val selectedResIndex = binding.spnResolution.selectedItemPosition
        val formatLabel = resolutions[selectedResIndex].first.substringBefore(" (").substringBefore(" Full").substringBefore(" HD")
        val formatOption = resolutions[selectedResIndex].second
        val isPlaylist = binding.swPlaylist.isChecked

        // Max concurrent limits config
        maxConcurrentTasks = concurrentLimits[binding.spnConcurrentLimit.selectedItemPosition]

        // Create new queued task
        val taskId = "dl_task_${System.currentTimeMillis()}"
        val newTask = DownloadTask(
            id = taskId,
            url = url,
            formatLabel = formatLabel,
            formatOption = formatOption,
            isPlaylist = isPlaylist
        )

        downloadTasks.add(newTask)
        taskAdapter.notifyItemInserted(downloadTasks.size - 1)

        // Clear URL input field
        binding.etUrl.setText("")
        Toast.makeText(this, "Added to Download Queue", Toast.LENGTH_SHORT).show()

        // Trigger queue processor
        checkAndStartQueuedDownloads()
    }

    private fun checkAndStartQueuedDownloads() {
        // Count active tasks (downloading via service)
        val activeCount = downloadTasks.count { it.status == DownloadTask.Status.DOWNLOADING }
        if (activeCount >= maxConcurrentTasks) return

        // Take next queued task
        val nextTask = downloadTasks.firstOrNull { it.status == DownloadTask.Status.QUEUED }
        if (nextTask != null) {
            startDownloadService(nextTask)
            // Recursively start another if limit allows
            checkAndStartQueuedDownloads()
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

            // Progress bar
            b.pbTaskProgress.progress = task.progress
            if (task.status == DownloadTask.Status.DOWNLOADING) {
                b.pbTaskProgress.isIndeterminate = task.progress == 0
                b.tvTaskStats.visibility = View.VISIBLE
                b.tvTaskStats.text = "${task.speed} • ${task.size} • ${task.eta}"
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

// Task model for active/queued downloads
data class DownloadTask(
    val id: String,
    val url: String,
    val formatLabel: String,
    val formatOption: String,
    val isPlaylist: Boolean,
    var status: Status = Status.QUEUED,
    var title: String = "Queued Video...",
    var progress: Int = 0,
    var speed: String = "-- MB/s",
    var eta: String = "--:--",
    var size: String = "-- MB",
    var tempFilePath: String? = null,
    var job: Job? = null
) {
    enum class Status { QUEUED, DOWNLOADING, COMPLETED, FAILED, CANCELLED }
}
