package com.example.chatsnap.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.chatsnap.ChatSnapApplication
import com.example.chatsnap.DownloaderActivity
import com.example.chatsnap.R
import com.example.chatsnap.models.AppDatabase
import com.example.chatsnap.models.DownloadHistoryEntity
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class DownloadService : Service() {

    companion object {
        const val TAG = "DownloadService"
        const val CHANNEL_ID = "chatsnap_downloads"
        const val CHANNEL_NAME = "ChatSnap Downloads"

        // Intent actions
        const val ACTION_START_DOWNLOAD = "com.example.chatsnap.START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.example.chatsnap.CANCEL_DOWNLOAD"

        // Intent extras
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_URL = "url"
        const val EXTRA_FORMAT_LABEL = "format_label"
        const val EXTRA_FORMAT_OPTION = "format_option"
        const val EXTRA_IS_PLAYLIST = "is_playlist"
        const val EXTRA_USER_ID = "user_id"

        // Broadcast actions (Service → Activity)
        const val BROADCAST_PROGRESS = "com.example.chatsnap.DOWNLOAD_PROGRESS"
        const val BROADCAST_COMPLETED = "com.example.chatsnap.DOWNLOAD_COMPLETED"
        const val BROADCAST_FAILED = "com.example.chatsnap.DOWNLOAD_FAILED"

        // Broadcast extras
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_ETA = "eta"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_SIZE = "size"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEMP_FILE_PATH = "temp_file_path"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        const val EXTRA_LINE = "line"

        private const val FOREGROUND_NOTIFICATION_ID = 9001
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val activeTasks = ConcurrentHashMap<String, TaskInfo>()
    private val notificationIdCounter = AtomicInteger(9100)
    private lateinit var notificationManager: NotificationManager
    private lateinit var db: AppDatabase

    // Regex patterns to parse yt-dlp logs
    private val speedRegex = """at\s+([\d.]+\w+/s)""".toRegex()
    private val sizeRegex = """of\s+([\d.]+\w+)""".toRegex()

    // Throttle notification updates to avoid flooding
    private val lastNotificationUpdate = ConcurrentHashMap<String, Long>()

    data class TaskInfo(
        val taskId: String,
        val url: String,
        val formatLabel: String,
        val formatOption: String,
        val isPlaylist: Boolean,
        val notificationId: Int,
        val userId: String = "",
        var title: String = "Preparing download...",
        var progress: Int = 0,
        var speed: String = "",
        var size: String = "",
        var eta: String = "",
        var tempFilePath: String? = null
    )

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        db = AppDatabase.getInstance(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return START_NOT_STICKY
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val formatLabel = intent.getStringExtra(EXTRA_FORMAT_LABEL) ?: "Best"
                val formatOption = intent.getStringExtra(EXTRA_FORMAT_OPTION) ?: "bestvideo+bestaudio/best"
                val isPlaylist = intent.getBooleanExtra(EXTRA_IS_PLAYLIST, false)
                val userId = intent.getStringExtra(EXTRA_USER_ID) ?: ""

                val taskInfo = TaskInfo(
                    taskId = taskId,
                    url = url,
                    formatLabel = formatLabel,
                    formatOption = formatOption,
                    isPlaylist = isPlaylist,
                    notificationId = notificationIdCounter.getAndIncrement(),
                    userId = userId
                )
                activeTasks[taskId] = taskInfo

                // Start as foreground with summary notification
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        FOREGROUND_NOTIFICATION_ID,
                        buildSummaryNotification(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(FOREGROUND_NOTIFICATION_ID, buildSummaryNotification())
                }

                // Launch download coroutine
                serviceScope.launch {
                    executeDownload(taskInfo)
                }
            }
            ACTION_CANCEL_DOWNLOAD -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return START_NOT_STICKY
                cancelDownload(taskId)
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Download progress notifications"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildSummaryNotification(): Notification {
        val activeCount = activeTasks.size
        val openIntent = Intent(this, DownloaderActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("ChatSnap Downloads")
            .setContentText("$activeCount download${if (activeCount != 1) "s" else ""} active")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun buildTaskNotification(task: TaskInfo): Notification {
        val cancelIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
            putExtra(EXTRA_TASK_ID, task.taskId)
        }
        val cancelPendingIntent = PendingIntent.getService(
            this, task.taskId.hashCode(), cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, DownloaderActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, task.taskId.hashCode() + 1000, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(task.title)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)

        if (task.progress > 0) {
            builder.setProgress(100, task.progress, false)
            val statsText = buildString {
                append("${task.progress}%")
                if (task.speed.isNotEmpty()) append(" • ${task.speed}")
                if (task.size.isNotEmpty()) append(" • ${task.size}")
                if (task.eta.isNotEmpty()) append(" • ETA ${task.eta}")
            }
            builder.setContentText(statsText)
        } else {
            builder.setProgress(0, 0, true)
            builder.setContentText("Fetching video info...")
        }

        return builder.build()
    }

    private fun showCompletedNotification(task: TaskInfo) {
        val openIntent = Intent(this, DownloaderActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, task.taskId.hashCode() + 2000, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download Complete")
            .setContentText(task.title)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(task.notificationId + 5000, notification)
    }

    private fun showFailedNotification(task: TaskInfo, error: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download Failed")
            .setContentText("${task.title}: $error")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${task.title}\n$error"))
            .build()

        notificationManager.notify(task.notificationId + 6000, notification)
    }

    private fun isTwitterUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("twitter.com") || lower.contains("x.com")
    }

    private fun extractTweetId(url: String): String? {
        // Match /status/DIGITS pattern from URLs like:
        // https://x.com/i/status/2066485316740071562
        // https://twitter.com/user/status/123456
        val regex = """/status/(\d+)""".toRegex()
        return regex.find(url)?.groupValues?.get(1)
    }

    /**
     * Twitter fallback: Uses the public vxtwitter API to get direct video MP4 URL
     * when yt-dlp fails on Twitter/X links.
     */
    private suspend fun downloadTwitterFallback(task: TaskInfo): File {
        val tweetId = extractTweetId(task.url)
            ?: throw Exception("Could not extract tweet ID from URL")

        Log.d(TAG, "Twitter fallback: fetching video info for tweet $tweetId via vxtwitter API")

        task.title = "Fetching Twitter video info..."
        notificationManager.notify(task.notificationId, buildTaskNotification(task))
        sendBroadcast(task, BROADCAST_PROGRESS)

        // Call vxtwitter API
        val apiUrl = URL("https://api.vxtwitter.com/status/$tweetId")
        val conn = apiUrl.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "ChatSnap/1.0")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            conn.disconnect()
            throw Exception("Twitter API returned HTTP $responseCode")
        }

        val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val json = JSONObject(responseBody)

        // Check if media exists
        if (!json.optBoolean("hasMedia", false)) {
            throw Exception("This tweet does not contain any media")
        }

        // Get video URL from mediaURLs array
        val mediaUrls = json.optJSONArray("mediaURLs")
        if (mediaUrls == null || mediaUrls.length() == 0) {
            throw Exception("No media URLs found in tweet")
        }

        // Find the best video URL (prefer mp4)
        var videoUrl: String? = null
        for (i in 0 until mediaUrls.length()) {
            val url = mediaUrls.getString(i)
            if (url.contains(".mp4") || url.contains("video")) {
                videoUrl = url
                break
            }
        }
        if (videoUrl == null) {
            videoUrl = mediaUrls.getString(0)
        }

        // Get title from tweet text or username
        val tweetText = json.optString("text", "")
        val userName = json.optString("user_name", "TwitterVideo")
        val displayTitle = if (tweetText.isNotEmpty() && tweetText.length <= 50) {
            tweetText.replace(Regex("[^\\w\\s-]"), "").trim().ifEmpty { userName }
        } else {
            "${userName}_$tweetId"
        }
        task.title = "$displayTitle.mp4"
        sendBroadcast(task, BROADCAST_PROGRESS)

        Log.d(TAG, "Twitter fallback: downloading from $videoUrl")

        // Download the video file directly
        val cacheDownloadsDir = File(cacheDir, "downloads")
        if (!cacheDownloadsDir.exists()) cacheDownloadsDir.mkdirs()

        val destFile = File(cacheDownloadsDir, "${displayTitle}_$tweetId.mp4")

        val videoConn = URL(videoUrl).openConnection() as HttpURLConnection
        videoConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        videoConn.connectTimeout = 30000
        videoConn.readTimeout = 60000

        val totalBytes = videoConn.contentLength.toLong()
        var downloadedBytes = 0L
        var lastNotifyTime = 0L

        BufferedInputStream(videoConn.inputStream).use { input ->
            FileOutputStream(destFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    // Update progress
                    if (totalBytes > 0) {
                        val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                        task.progress = progress
                        task.size = "${downloadedBytes / 1024}KB / ${totalBytes / 1024}KB"
                    } else {
                        task.size = "${downloadedBytes / 1024}KB"
                    }

                    // Throttle notification updates
                    val now = System.currentTimeMillis()
                    if (now - lastNotifyTime > 800) {
                        lastNotifyTime = now
                        notificationManager.notify(task.notificationId, buildTaskNotification(task))
                        sendBroadcast(task, BROADCAST_PROGRESS)
                    }
                }
            }
        }
        videoConn.disconnect()

        task.progress = 100
        task.tempFilePath = destFile.absolutePath
        Log.d(TAG, "Twitter fallback: download complete. File: ${destFile.absolutePath} (${destFile.length()} bytes)")

        return destFile
    }

    private suspend fun executeDownload(task: TaskInfo) {
        try {
            // Ensure engine is ready
            if (!ChatSnapApplication.awaitInitialization()) {
                throw Exception("Download engine failed to initialize. Please restart the app.")
            }

            val cacheDownloadsDir = File(cacheDir, "downloads")
            if (!cacheDownloadsDir.exists()) cacheDownloadsDir.mkdirs()

            // Show initial notification
            notificationManager.notify(task.notificationId, buildTaskNotification(task))

            val urlLower = task.url.lowercase()
            var tempFile: File? = null
            var usedTwitterFallback = false

            // For Twitter/X URLs, try yt-dlp first, then fallback to vxtwitter API
            if (isTwitterUrl(task.url)) {
                try {
                    tempFile = executeYtDlp(task, cacheDownloadsDir)
                } catch (ytdlpError: Exception) {
                    Log.w(TAG, "yt-dlp failed for Twitter URL, trying vxtwitter fallback: ${ytdlpError.message}")
                    tempFile = downloadTwitterFallback(task)
                    usedTwitterFallback = true
                }
            } else {
                tempFile = executeYtDlp(task, cacheDownloadsDir)
            }

            // Copy to public downloads
            if (!tempFile.exists()) {
                // Last resort: find newest file in cache
                val files = cacheDownloadsDir.listFiles()
                tempFile = files?.maxByOrNull { it.lastModified() }
            }

            var savedFilePath: String? = null
            if (tempFile != null && tempFile.exists()) {
                savedFilePath = tempFile.name
                val copied = saveFileToPublicDownloads(tempFile)
                if (!copied) {
                    throw Exception("Failed to copy file to public Downloads folder.")
                }
            } else {
                throw Exception("Downloaded file not found in cache directory.")
            }

            // Save to history DB
            db.downloadHistoryDao().insert(
                DownloadHistoryEntity(
                    userId = task.userId,
                    url = task.url,
                    title = task.title,
                    formatLabel = task.formatLabel,
                    filePath = "Downloads/ChatSnapDownloads/$savedFilePath",
                    fileSize = task.size,
                    status = "COMPLETED"
                )
            )

            // Remove progress notification, show completed
            notificationManager.cancel(task.notificationId)
            showCompletedNotification(task)

            // Broadcast completion to activity
            sendBroadcast(task, BROADCAST_COMPLETED)

        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${task.taskId}", e)

            // Save failed entry to history
            try {
                db.downloadHistoryDao().insert(
                    DownloadHistoryEntity(
                        userId = task.userId,
                        url = task.url,
                        title = task.title,
                        formatLabel = task.formatLabel,
                        filePath = null,
                        fileSize = null,
                        status = "FAILED",
                        errorMessage = e.message
                    )
                )
            } catch (dbEx: Exception) {
                Log.e(TAG, "Failed to save history", dbEx)
            }

            // Remove progress notification, show failed
            notificationManager.cancel(task.notificationId)
            showFailedNotification(task, e.message ?: "Unknown error")

            // Broadcast failure
            val failIntent = Intent(BROADCAST_FAILED).apply {
                putExtra(EXTRA_TASK_ID, task.taskId)
                putExtra(EXTRA_ERROR_MESSAGE, e.message ?: "Unknown error")
                putExtra(EXTRA_TITLE, task.title)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(failIntent)

        } finally {
            activeTasks.remove(task.taskId)

            // Update summary or stop service if no more tasks
            if (activeTasks.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                notificationManager.notify(FOREGROUND_NOTIFICATION_ID, buildSummaryNotification())
            }
        }
    }

    /**
     * Standard yt-dlp download execution. Returns the downloaded temp file.
     */
    private suspend fun executeYtDlp(task: TaskInfo, cacheDownloadsDir: File): File {
        val request = YoutubeDLRequest(task.url)

        request.addOption("-o", "${cacheDownloadsDir.absolutePath}/%(title)s.%(ext)s")

        // Playlist setting
        if (task.isPlaylist) {
            request.addOption("--yes-playlist")
        } else {
            request.addOption("--no-playlist")
        }

        val isAudioOnly = task.formatOption == "bestaudio"
        if (isAudioOnly) {
            request.addOption("-f", "bestaudio")
            request.addOption("--extract-audio")
            request.addOption("--audio-format", "mp3")
        } else {
            request.addOption("-f", task.formatOption)
            request.addOption("--merge-output-format", "mp4")
        }

        // Reliability options & global User-Agent spoofing
        request.addOption("--no-check-certificates")
        request.addOption("--no-cache-dir")
        request.addOption("--socket-timeout", "30")
        request.addOption("--retries", "5")
        request.addOption("--force-ipv4")
        request.addOption("--user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")

        val urlLower = task.url.lowercase()

        // Twitter/X fix: Use syndication API + referer header
        if (isTwitterUrl(task.url)) {
            request.addOption("--extractor-args", "twitter:api=syndication")
            request.addOption("--add-header", "Referer:https://twitter.com/")
            Log.d(TAG, "Twitter/X URL detected, using syndication API extractor")
        }

        // YouTube optimization: use android and web_embedded players for best extraction compatibility
        if (urlLower.contains("youtube.com") || urlLower.contains("youtu.be")) {
            request.addOption("--extractor-args", "youtube:player_client=android,web_embedded")
        }

        Log.d(TAG, "Starting yt-dlp download: ${task.url} | format: ${task.formatOption}")

        // Execute yt-dlp
        val response = YoutubeDL.getInstance().execute(request, task.taskId) { progress, etaInSeconds, line ->
            handleProgress(task, progress, etaInSeconds, line ?: "")
        }

        Log.d(TAG, "yt-dlp finished. Exit: ${response.exitCode}")

        // Find the downloaded file
        var tempFile = task.tempFilePath?.let { File(it) }
        if (tempFile == null || !tempFile.exists()) {
            val files = cacheDownloadsDir.listFiles()
            tempFile = files?.maxByOrNull { it.lastModified() }
        }

        if (tempFile == null || !tempFile.exists()) {
            throw Exception("yt-dlp completed but downloaded file not found")
        }

        return tempFile
    }

    private fun handleProgress(task: TaskInfo, progress: Float, etaInSeconds: Long, line: String) {
        if (progress >= 0f) {
            task.progress = progress.toInt()
        }

        // Format ETA
        if (etaInSeconds > 0) {
            val minutes = etaInSeconds / 60
            val seconds = etaInSeconds % 60
            task.eta = String.format("%02d:%02d", minutes, seconds)
        } else if (progress >= 100f) {
            task.eta = "Merging..."
        }

        // Parse title & file path
        if (line.contains("[download] Destination:") || line.contains("[Download] Destination:")) {
            val fullPath = line.substringAfter("Destination:").trim()
            task.title = File(fullPath).name
            task.tempFilePath = fullPath
        } else if (line.contains("has already been downloaded") && task.title.startsWith("Preparing")) {
            val fileName = line.substringAfter("[download]").substringBefore("has already").trim()
            task.title = File(fileName).name
            task.tempFilePath = fileName
        } else if (line.contains("[Merger]") && line.contains("Merging formats")) {
            val mergedPath = line.substringAfter("\"").substringBefore("\"")
            if (mergedPath.isNotEmpty()) {
                task.tempFilePath = mergedPath
                task.title = File(mergedPath).name
            }
        }

        // Parse speed and size
        speedRegex.find(line)?.let { task.speed = it.groupValues[1] }
        sizeRegex.find(line)?.let { task.size = it.groupValues[1] }

        // Throttle notification updates to every 800ms
        val now = System.currentTimeMillis()
        val lastUpdate = lastNotificationUpdate[task.taskId] ?: 0L
        if (now - lastUpdate > 800) {
            lastNotificationUpdate[task.taskId] = now
            notificationManager.notify(task.notificationId, buildTaskNotification(task))
        }

        // Broadcast progress to activity (throttled)
        if (now - lastUpdate > 500) {
            sendBroadcast(task, BROADCAST_PROGRESS)
        }
    }

    private fun sendBroadcast(task: TaskInfo, action: String) {
        val intent = Intent(action).apply {
            putExtra(EXTRA_TASK_ID, task.taskId)
            putExtra(EXTRA_PROGRESS, task.progress)
            putExtra(EXTRA_SPEED, task.speed)
            putExtra(EXTRA_SIZE, task.size)
            putExtra(EXTRA_ETA, task.eta)
            putExtra(EXTRA_TITLE, task.title)
            putExtra(EXTRA_TEMP_FILE_PATH, task.tempFilePath)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun cancelDownload(taskId: String) {
        try {
            YoutubeDL.getInstance().destroyProcessById(taskId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy process: $taskId", e)
        }

        val task = activeTasks.remove(taskId)
        if (task != null) {
            notificationManager.cancel(task.notificationId)

            // Save cancelled entry
            serviceScope.launch {
                try {
                    db.downloadHistoryDao().insert(
                        DownloadHistoryEntity(
                            url = task.url,
                            title = task.title,
                            formatLabel = task.formatLabel,
                            filePath = null,
                            fileSize = null,
                            status = "CANCELLED"
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save cancelled history", e)
                }
            }
        }

        // Broadcast cancellation as failure
        val cancelIntent = Intent(BROADCAST_FAILED).apply {
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_ERROR_MESSAGE, "Cancelled by user")
            putExtra(EXTRA_TITLE, task?.title ?: "Download")
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(cancelIntent)

        if (activeTasks.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            notificationManager.notify(FOREGROUND_NOTIFICATION_ID, buildSummaryNotification())
        }
    }

    private fun saveFileToPublicDownloads(srcFile: File): Boolean {
        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, srcFile.name)
            put(MediaStore.MediaColumns.MIME_TYPE, getMimeType(srcFile))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ChatSnapDownloads")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        } else {
            val destFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "ChatSnapDownloads/${srcFile.name}"
            )
            destFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
            try {
                srcFile.inputStream().use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                srcFile.delete()
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy to legacy path", e)
                return false
            }
        }

        if (uri == null) return false

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                srcFile.inputStream().use { inp -> inp.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            srcFile.delete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to MediaStore", e)
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            false
        }
    }

    private fun getMimeType(file: File): String {
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }
}
