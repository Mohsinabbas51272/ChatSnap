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
import com.example.chatsnap.models.DownloadTask
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
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLParameters
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import java.net.Socket
import java.net.InetAddress
import java.io.IOException

class DownloadService : Service() {

    companion object {
        const val TAG = "DownloadService"
        const val CHANNEL_ID = "chatsnap_downloads"
        const val CHANNEL_NAME = "ChatSnap Downloads"

        // Global in-memory list of all active/queued tasks.
        // It persists across Activity lifecycle but is cleared when app is fully closed/killed.
        val sharedTasks = java.util.concurrent.CopyOnWriteArrayList<DownloadTask>()
        var maxConcurrentTasks = 2

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
        const val EXTRA_THUMBNAIL_URL = "thumbnail_url"

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
        var tempFilePath: String? = null,
        var thumbnailUrl: String? = null
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
                val thumbnailUrl = intent.getStringExtra(EXTRA_THUMBNAIL_URL)

                val taskInfo = TaskInfo(
                    taskId = taskId,
                    url = url,
                    formatLabel = formatLabel,
                    formatOption = formatOption,
                    isPlaylist = isPlaylist,
                    notificationId = notificationIdCounter.getAndIncrement(),
                    userId = userId,
                    thumbnailUrl = thumbnailUrl
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

    private fun isTikTokUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("tiktok.com")
    }

    private fun isInstagramUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("instagram.com") || lower.contains("instagr.am")
    }

    private fun getVideoResolutionLabel(filePath: String): String? {
        var retriever: android.media.MediaMetadataRetriever? = null
        try {
            retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(filePath)
            val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (width != null && height != null) {
                val minDim = Math.min(width, height)
                return "${minDim}p"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve video resolution for $filePath: ${e.message}")
        } finally {
            try {
                retriever?.release()
            } catch (_: Exception) {}
        }
        return null
    }

    private fun generateVideoThumbnail(videoFilePath: String): String? {
        var retriever: android.media.MediaMetadataRetriever? = null
        try {
            retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(videoFilePath)
            val bitmap = retriever.frameAtTime
            if (bitmap != null) {
                val thumbnailDir = File(filesDir, "thumbnails")
                if (!thumbnailDir.exists()) {
                    thumbnailDir.mkdirs()
                }
                val thumbnailFile = File(thumbnailDir, "thumb_${System.currentTimeMillis()}.jpg")
                FileOutputStream(thumbnailFile).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                }
                return thumbnailFile.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate thumbnail for $videoFilePath: ${e.message}")
        } finally {
            try {
                retriever?.release()
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Resolves shortened TikTok URLs (vm.tiktok.com, vt.tiktok.com) to full URLs.
     * The third-party APIs need the full tiktok.com/@user/video/ID format.
     */
    private suspend fun resolveTikTokUrl(url: String): String {
        val lower = url.lowercase()
        if (lower.contains("vm.tiktok.com") || lower.contains("vt.tiktok.com") || !lower.contains("/video/")) {
            try {
                Log.d(TAG, "Resolving short TikTok URL: $url")
                val conn = withContext(Dispatchers.IO) {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = false
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    c.connectTimeout = 10000
                    c.readTimeout = 10000
                    c.requestMethod = "HEAD"
                    c.connect()
                    c
                }
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (!location.isNullOrEmpty()) {
                    // Strip tracking params
                    val cleanUrl = location.split("?").first()
                    Log.d(TAG, "Resolved TikTok URL: $cleanUrl")
                    return cleanUrl
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve TikTok URL, using original: ${e.message}")
            }
        }
        // Strip tracking params from full URLs too
        return url.split("?").first()
    }

    /**
     * TikTok download: Multi-approach fallback system.
     * Approach 1: Lovetik proxy (works without VPN, direct stream CDN)
     * Approach 2: tikwm.com API (POST)
     * Approach 3: tikwm.com API (GET alternative)
     * Approach 4: yt-dlp (last resort)
     */
    private suspend fun downloadTikTok(task: TaskInfo): File {
        val resolvedUrl = resolveTikTokUrl(task.url)
        val cacheDownloadsDir = File(cacheDir, "downloads")
        if (!cacheDownloadsDir.exists()) cacheDownloadsDir.mkdirs()

        Log.d(TAG, "TikTok: Trying Approach 1: Lovetik for $resolvedUrl")
        try {
            return downloadTikTokViaLovetik(task, resolvedUrl)
        } catch (e0: Exception) {
            Log.w(TAG, "TikTok Approach 1 (Lovetik) failed: ${e0.message}. Trying Approach 2: tikwm POST API...")
            try {
                return downloadTikTokViaTikwm(task, resolvedUrl)
            } catch (e1: Exception) {
                Log.w(TAG, "TikTok Approach 2 (tikwm POST) failed: ${e1.message}. Trying Approach 3: tikwm GET API...")
                try {
                    return downloadTikTokViaTikwmGet(task, resolvedUrl)
                } catch (e2: Exception) {
                    Log.w(TAG, "TikTok Approach 3 (tikwm GET) failed: ${e2.message}. Trying Approach 4: yt-dlp...")
                    try {
                        return executeYtDlp(task, cacheDownloadsDir, resolvedUrl)
                    } catch (e3: Exception) {
                        Log.e(TAG, "TikTok Approach 4 (yt-dlp) failed: ${e3.message}")
                        throw Exception("TikTok download failed. Checked Lovetik, tikwm, and yt-dlp. Last error: ${e3.message}")
                    }
                }
            }
        }
    }

    /**
     * TikTok via lovetik.com API (Proxies media bytes via cdn-1.lovetik.com directly)
     */
    private suspend fun downloadTikTokViaLovetik(task: TaskInfo, resolvedUrl: String): File {
        Log.d(TAG, "TikTok Lovetik: fetching video info for: $resolvedUrl")

        task.title = "Fetching TikTok video info (Lovetik)..."
        notificationManager.notify(task.notificationId, buildTaskNotification(task))
        sendBroadcast(task, BROADCAST_PROGRESS)

        val apiUrl = URL("https://lovetik.com/api/ajax/search")
        val conn = withContext(Dispatchers.IO) {
            val c = apiUrl.openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.setRequestProperty("Accept", "application/json, text/plain, */*")
            c.setRequestProperty("Origin", "https://lovetik.com")
            c.setRequestProperty("Referer", "https://lovetik.com/")
            c.connectTimeout = 20000
            c.readTimeout = 20000
            c.doOutput = true
            c.instanceFollowRedirects = true
            c
        }

        val postData = "query=" + java.net.URLEncoder.encode(resolvedUrl, "UTF-8")
        withContext(Dispatchers.IO) {
            conn.outputStream.use { os ->
                os.write(postData.toByteArray(Charsets.UTF_8))
            }
        }

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            conn.disconnect()
            throw Exception("Lovetik API returned HTTP $responseCode")
        }

        val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val json = JSONObject(responseBody)
        val status = json.optString("status", "")
        if (status != "ok") {
            val mess = json.optString("mess", "Failed to parse TikTok video details via Lovetik")
            throw Exception(mess)
        }

        val links = json.optJSONArray("links") ?: throw Exception("Invalid Lovetik API response (missing links)")
        if (links.length() == 0) throw Exception("No download links found in Lovetik response")

        // Try to find "HD Original" or any clean mp4 download URL
        var downloadUrl = ""
        var bestLabel = ""
        for (i in 0 until links.length()) {
            val obj = links.optJSONObject(i) ?: continue
            val label = obj.optString("t", "").lowercase()
            val url = obj.optString("a", "")
            if (url.isNotEmpty() && (label.contains("hd") || label.contains("original") || label.contains("mp4"))) {
                downloadUrl = url
                bestLabel = label
                if (label.contains("hd") || label.contains("original")) {
                    break
                }
            }
        }

        if (downloadUrl.isEmpty()) {
            val firstObj = links.optJSONObject(0)
            downloadUrl = firstObj?.optString("a", "") ?: ""
            bestLabel = firstObj?.optString("t", "") ?: ""
        }

        if (downloadUrl.isEmpty()) throw Exception("No direct download URL found in Lovetik links")

        val authorName = json.optString("author", "TikTokVideo")
        val videoId = json.optString("id", System.currentTimeMillis().toString())

        val thumbUrl = json.optString("cover", "")
        if (thumbUrl.isNotEmpty()) {
            task.thumbnailUrl = thumbUrl
        }

        val displayTitle = "${authorName.replace("@", "")}_$videoId"
        task.title = "$displayTitle.mp4"
        sendBroadcast(task, BROADCAST_PROGRESS)

        Log.d(TAG, "TikTok Lovetik: downloading from $downloadUrl (Format: $bestLabel)")
        return downloadFileDirectly(task, downloadUrl, "${displayTitle}.mp4")
    }

    /**
     * TikTok via tikwm.com API
     */
    private suspend fun downloadTikTokViaTikwm(task: TaskInfo, resolvedUrl: String): File {
        Log.d(TAG, "TikTok tikwm: fetching video info for: $resolvedUrl")

        task.title = "Fetching TikTok video info..."
        notificationManager.notify(task.notificationId, buildTaskNotification(task))
        sendBroadcast(task, BROADCAST_PROGRESS)

        val apiUrl = URL("https://www.tikwm.com/api/")
        val conn = apiUrl.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Accept", "application/json, text/plain, */*")
        conn.setRequestProperty("Origin", "https://www.tikwm.com")
        conn.setRequestProperty("Referer", "https://www.tikwm.com/")
        conn.connectTimeout = 20000
        conn.readTimeout = 20000
        conn.doOutput = true
        conn.instanceFollowRedirects = true

        val postData = "url=" + java.net.URLEncoder.encode(resolvedUrl, "UTF-8") + "&count=12&cursor=0&web=1&hd=1"
        conn.outputStream.use { os ->
            os.write(postData.toByteArray(Charsets.UTF_8))
        }

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            conn.disconnect()
            throw Exception("tikwm API returned HTTP $responseCode")
        }

        val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val json = JSONObject(responseBody)
        val code = json.optInt("code", -1)
        if (code != 0) {
            val msg = json.optString("msg", "Failed to parse TikTok video details")
            throw Exception(msg)
        }

        val data = json.optJSONObject("data") ?: throw Exception("Invalid API response (missing data)")
        
        var videoUrl = data.optString("hdplay", "")
        if (videoUrl.isEmpty()) videoUrl = data.optString("play", "")
        if (videoUrl.isEmpty()) videoUrl = data.optString("wmplay", "")
        if (videoUrl.isEmpty()) throw Exception("No playable video URL found")

        if (videoUrl.startsWith("/")) {
            videoUrl = "https://www.tikwm.com$videoUrl"
        }

        val titleText = data.optString("title", "")
        val authorObj = data.optJSONObject("author")
        val authorName = authorObj?.optString("unique_id", "TikTokVideo") ?: "TikTokVideo"
        val videoId = data.optString("id", System.currentTimeMillis().toString())

        // Get thumbnail
        val thumbUrl = data.optString("cover", data.optString("origin_cover", ""))
        if (thumbUrl.isNotEmpty()) {
            task.thumbnailUrl = thumbUrl
        }

        val displayTitle = if (titleText.isNotEmpty()) {
            titleText.replace(Regex("[^\\w\\s-]"), "").trim().take(40).ifEmpty { authorName }
        } else {
            "${authorName}_$videoId"
        }
        task.title = "$displayTitle.mp4"
        sendBroadcast(task, BROADCAST_PROGRESS)

        // Try tikwm proxy URL first (bypasses CDN blocks in regions like Pakistan)
        val proxyUrl = "https://www.tikwm.com/video/media/hdplay/$videoId.mp4"
        Log.d(TAG, "TikTok tikwm: trying proxy download from $proxyUrl")
        try {
            return downloadFileDirectly(task, proxyUrl, "${displayTitle}_$videoId.mp4")
        } catch (proxyError: Exception) {
            Log.w(TAG, "TikTok tikwm proxy download failed: ${proxyError.message}, trying direct CDN URL...")
            // Fallback: try standard play proxy URL
            val playProxyUrl = "https://www.tikwm.com/video/media/play/$videoId.mp4"
            try {
                return downloadFileDirectly(task, playProxyUrl, "${displayTitle}_$videoId.mp4")
            } catch (playProxyError: Exception) {
                Log.w(TAG, "TikTok tikwm play proxy also failed: ${playProxyError.message}, trying direct CDN...")
                // Final fallback: try direct CDN URL
                Log.d(TAG, "TikTok tikwm: downloading from direct CDN $videoUrl")
                return downloadFileDirectly(task, videoUrl, "${displayTitle}_$videoId.mp4")
            }
        }
    }

    /**
     * TikTok via tikwm.com GET API (alternative endpoint)
     */
    private suspend fun downloadTikTokViaTikwmGet(task: TaskInfo, resolvedUrl: String): File {
        Log.d(TAG, "TikTok tikwm GET: fetching video info for: $resolvedUrl")

        task.title = "Fetching TikTok video (alt)..."
        notificationManager.notify(task.notificationId, buildTaskNotification(task))
        sendBroadcast(task, BROADCAST_PROGRESS)

        val encodedUrl = java.net.URLEncoder.encode(resolvedUrl, "UTF-8")
        val apiUrl = URL("https://www.tikwm.com/api/?url=$encodedUrl&hd=1")
        val conn = withContext(Dispatchers.IO) {
            val c = apiUrl.openConnection() as HttpURLConnection
            c.requestMethod = "GET"
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
            c.setRequestProperty("Accept", "application/json, text/plain, */*")
            c.setRequestProperty("Referer", "https://www.tikwm.com/")
            c.connectTimeout = 20000
            c.readTimeout = 20000
            c.instanceFollowRedirects = true
            c
        }

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            conn.disconnect()
            throw Exception("tikwm GET API returned HTTP $responseCode")
        }

        val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        Log.d(TAG, "tikwm GET response: ${responseBody.take(300)}")

        val json = JSONObject(responseBody)
        val code = json.optInt("code", -1)
        if (code != 0) {
            val msg = json.optString("msg", "Failed to parse TikTok video details")
            throw Exception(msg)
        }

        val data = json.optJSONObject("data") ?: throw Exception("Invalid API response (missing data)")

        var videoUrl = data.optString("hdplay", "")
        if (videoUrl.isEmpty()) videoUrl = data.optString("play", "")
        if (videoUrl.isEmpty()) videoUrl = data.optString("wmplay", "")
        if (videoUrl.isEmpty()) throw Exception("No playable video URL found in GET response")

        if (videoUrl.startsWith("/")) {
            videoUrl = "https://www.tikwm.com$videoUrl"
        }

        val titleText = data.optString("title", "")
        val authorObj = data.optJSONObject("author")
        val authorName = authorObj?.optString("unique_id", "TikTokVideo") ?: "TikTokVideo"
        val videoId = data.optString("id", System.currentTimeMillis().toString())

        val thumbUrl = data.optString("cover", data.optString("origin_cover", ""))
        if (thumbUrl.isNotEmpty()) {
            task.thumbnailUrl = thumbUrl
        }

        val displayTitle = if (titleText.isNotEmpty()) {
            titleText.replace(Regex("[^\\w\\s-]"), "").trim().take(40).ifEmpty { authorName }
        } else {
            "${authorName}_$videoId"
        }
        task.title = "$displayTitle.mp4"
        sendBroadcast(task, BROADCAST_PROGRESS)

        // Try tikwm proxy URL first (bypasses CDN blocks in regions like Pakistan)
        val proxyUrl = "https://www.tikwm.com/video/media/hdplay/$videoId.mp4"
        Log.d(TAG, "TikTok tikwm GET: trying proxy download from $proxyUrl")
        try {
            return downloadFileDirectly(task, proxyUrl, "${displayTitle}_$videoId.mp4")
        } catch (proxyError: Exception) {
            Log.w(TAG, "TikTok tikwm GET proxy download failed: ${proxyError.message}, trying play proxy...")
            val playProxyUrl = "https://www.tikwm.com/video/media/play/$videoId.mp4"
            try {
                return downloadFileDirectly(task, playProxyUrl, "${displayTitle}_$videoId.mp4")
            } catch (playProxyError: Exception) {
                Log.w(TAG, "TikTok tikwm GET play proxy also failed: ${playProxyError.message}, trying direct CDN...")
                Log.d(TAG, "TikTok tikwm GET: downloading from direct CDN $videoUrl")
                return downloadFileDirectly(task, videoUrl, "${displayTitle}_$videoId.mp4")
            }
        }
    }

    /**
     * Instagram fallback: Scrapes the Instagram page for og:video meta tags
     * by pretending to be a social media bot (like Facebook's crawler).
     * Instagram serves video embed data to social media crawlers.
     */
    private suspend fun downloadInstagramFallback(task: TaskInfo): File {
        Log.d(TAG, "Instagram fallback: scraping video URL for: ${task.url}")

        task.title = "Fetching Instagram video info..."
        notificationManager.notify(task.notificationId, buildTaskNotification(task))
        sendBroadcast(task, BROADCAST_PROGRESS)

        var videoUrl: String? = null
        var displayTitle = "Instagram_${System.currentTimeMillis()}"

        // Approach 1: Scrape og:video from Instagram page using Facebook bot UA
        try {
            val result = scrapeInstagramOgMeta(task.url)
            videoUrl = result.first
            // Set thumbnail from og:image if available
            if (result.second != null && task.thumbnailUrl.isNullOrEmpty()) {
                task.thumbnailUrl = result.second
            }
            Log.d(TAG, "Instagram og:video scrape result: $videoUrl, thumb: ${result.second}")
        } catch (e: Exception) {
            Log.w(TAG, "Instagram og:video scrape failed: ${e.message}")
        }

        // Approach 2: Try DDInstagram proxy
        if (videoUrl.isNullOrEmpty()) {
            try {
                videoUrl = fetchViaDDInstagram(task.url)
                Log.d(TAG, "DDInstagram result: $videoUrl")
            } catch (e: Exception) {
                Log.w(TAG, "DDInstagram failed: ${e.message}")
            }
        }

        // Approach 3: Try InstaFix proxy (similar to DDInstagram but different service)
        if (videoUrl.isNullOrEmpty()) {
            try {
                videoUrl = fetchViaInstaFix(task.url)
                Log.d(TAG, "InstaFix result: $videoUrl")
            } catch (e: Exception) {
                Log.w(TAG, "InstaFix failed: ${e.message}")
            }
        }

        if (videoUrl.isNullOrEmpty()) {
            throw Exception("Could not extract video URL from Instagram. The post may be private or not contain a video.")
        }

        // Extract a reasonable filename from the URL
        val shortcode = extractInstagramShortcode(task.url) ?: System.currentTimeMillis().toString()
        displayTitle = "Instagram_$shortcode"
        task.title = "$displayTitle.mp4"
        sendBroadcast(task, BROADCAST_PROGRESS)

        Log.d(TAG, "Instagram fallback: downloading from $videoUrl")

        return downloadFileDirectly(task, videoUrl, "${displayTitle}.mp4")
    }

    /**
     * Scrapes Instagram page HTML for og:video AND og:image meta tags.
     * Uses Facebook's crawler User-Agent so Instagram serves rich embed data.
     * Returns Pair(videoUrl, imageUrl) - either can be null.
     */
    private fun scrapeInstagramOgMeta(url: String): Pair<String?, String?> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        // Use Facebook's crawler User-Agent - Instagram serves og:video to social bots
        conn.setRequestProperty("User-Agent", "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)")
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.5")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            conn.disconnect()
            throw Exception("Instagram returned HTTP $responseCode")
        }

        val html = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        var videoUrl: String? = null
        var imageUrl: String? = null

        // Extract og:image for thumbnail
        val ogImagePatterns = listOf(
            """<meta\s+(?:property|name)=["']og:image["']\s+content=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE),
            """<meta\s+content=["']([^"']+)["']\s+(?:property|name)=["']og:image["']""".toRegex(RegexOption.IGNORE_CASE)
        )
        for (pattern in ogImagePatterns) {
            val match = pattern.find(html)
            if (match != null) {
                imageUrl = match.groupValues[1].replace("&amp;", "&")
                break
            }
        }

        // Look for og:video meta tag
        val ogVideoPattern = """<meta\s+(?:property|name)=["']og:video["']\s+content=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
        val ogVideoMatch = ogVideoPattern.find(html)
        if (ogVideoMatch != null) {
            videoUrl = ogVideoMatch.groupValues[1].replace("&amp;", "&")
            return Pair(videoUrl, imageUrl)
        }

        // Also try reversed attribute order: content first, then property
        val ogVideoPattern2 = """<meta\s+content=["']([^"']+)["']\s+(?:property|name)=["']og:video["']""".toRegex(RegexOption.IGNORE_CASE)
        val ogVideoMatch2 = ogVideoPattern2.find(html)
        if (ogVideoMatch2 != null) {
            videoUrl = ogVideoMatch2.groupValues[1].replace("&amp;", "&")
            return Pair(videoUrl, imageUrl)
        }

        // Try og:video:secure_url
        val secureVideoPattern = """<meta\s+(?:property|name)=["']og:video:secure_url["']\s+content=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
        val secureVideoMatch = secureVideoPattern.find(html)
        if (secureVideoMatch != null) {
            videoUrl = secureVideoMatch.groupValues[1].replace("&amp;", "&")
            return Pair(videoUrl, imageUrl)
        }

        return Pair(null, imageUrl)
    }

    /**
     * Tries to fetch Instagram video URL via DDInstagram proxy.
     * DDInstagram serves video embeds by changing the Instagram URL hostname.
     */
    private fun fetchViaDDInstagram(originalUrl: String): String? {
        // Convert instagram.com URL to ddinstagram.com
        val ddUrl = originalUrl
            .replace("www.instagram.com", "ddinstagram.com")
            .replace("instagram.com", "ddinstagram.com")
            .replace("instagr.am", "ddinstagram.com")

        val conn = URL(ddUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)")
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            conn.disconnect()
            return null
        }

        val html = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        // Look for og:video in DDInstagram response
        val ogVideoPattern = """<meta\s+(?:property|name)=["']og:video["']\s+content=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
        val match = ogVideoPattern.find(html)
        if (match != null) {
            return match.groupValues[1].replace("&amp;", "&")
        }

        // Try content-first attribute order
        val ogVideoPattern2 = """<meta\s+content=["']([^"']+)["']\s+(?:property|name)=["']og:video["']""".toRegex(RegexOption.IGNORE_CASE)
        val match2 = ogVideoPattern2.find(html)
        if (match2 != null) {
            return match2.groupValues[1].replace("&amp;", "&")
        }

        // Look for direct video link in the HTML
        val videoSrcPattern = """<source\s+src=["']([^"']+\.mp4[^"']*)["']""".toRegex(RegexOption.IGNORE_CASE)
        val videoMatch = videoSrcPattern.find(html)
        if (videoMatch != null) {
            return videoMatch.groupValues[1].replace("&amp;", "&")
        }

        return null
    }

    /**
     * Tries to fetch Instagram video URL via InstaFix proxy.
     */
    private fun fetchViaInstaFix(originalUrl: String): String? {
        // Convert instagram.com URL to d.ddinstagram.com (direct download endpoint)
        val fixUrl = originalUrl
            .replace("www.instagram.com", "d.ddinstagram.com")
            .replace("instagram.com", "d.ddinstagram.com")
            .replace("instagr.am", "d.ddinstagram.com")

        val conn = URL(fixUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = false // We want to capture the redirect URL

        val responseCode = conn.responseCode
        if (responseCode in 301..302) {
            val redirectUrl = conn.getHeaderField("Location")
            conn.disconnect()
            if (!redirectUrl.isNullOrEmpty() && (redirectUrl.contains(".mp4") || redirectUrl.contains("video"))) {
                return redirectUrl
            }
        }
        conn.disconnect()
        return null
    }

    /**
     * Extracts the shortcode from an Instagram URL.
     * e.g., https://www.instagram.com/reel/ABC123/ -> ABC123
     */
    private fun extractInstagramShortcode(url: String): String? {
        val regex = """/(?:reel|p|tv)/([A-Za-z0-9_-]+)""".toRegex()
        return regex.find(url)?.groupValues?.get(1)
    }

    /**
     * Generic helper: Downloads a file from a direct URL with progress tracking.
     */
    private suspend fun downloadFileDirectly(task: TaskInfo, videoUrl: String, filename: String): File {
        val cacheDownloadsDir = File(cacheDir, "downloads")
        if (!cacheDownloadsDir.exists()) cacheDownloadsDir.mkdirs()

        val destFile = File(cacheDownloadsDir, filename)

        val videoConn = URL(videoUrl).openConnection() as HttpURLConnection
        if (videoConn is HttpsURLConnection && isTikTokUrl(task.url)) {
            try {
                val defaultFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
                videoConn.sslSocketFactory = SNIStrippingSSLSocketFactory(defaultFactory)
                videoConn.hostnameVerifier = HostnameVerifier { hostname, session ->
                    val hostLower = hostname.lowercase()
                    if (hostLower.contains("tiktokcdn") || hostLower.contains("v16m") || hostLower.contains("akamaized")) {
                        true
                    } else {
                        HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
                    }
                }
                Log.d(TAG, "Applied SNIStrippingSSLSocketFactory and HostnameVerifier for TikTok download: $videoUrl")
            } catch (sslEx: Exception) {
                Log.w(TAG, "Failed to apply SNIStrippingSSLSocketFactory: ${sslEx.message}")
            }
        }
        videoConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
        videoConn.setRequestProperty("Accept", "*/*")
        videoConn.setRequestProperty("Accept-Encoding", "identity")
        videoConn.connectTimeout = 30000
        videoConn.readTimeout = 60000
        videoConn.instanceFollowRedirects = true

        val totalBytes = videoConn.contentLength.toLong()
        var downloadedBytes = 0L
        var lastNotifyTime = 0L
        val startTime = System.currentTimeMillis()

        BufferedInputStream(videoConn.inputStream).use { input ->
            FileOutputStream(destFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    // Update progress, speed and size in MB
                    val now = System.currentTimeMillis()
                    val timeDiff = now - startTime
                    val speed = if (timeDiff > 0) {
                        val bytesPerSec = (downloadedBytes * 1000) / timeDiff
                        val mbPerSec = bytesPerSec.toDouble() / (1024 * 1024)
                        String.format(java.util.Locale.US, "%.2f MB/s", mbPerSec)
                    } else {
                        "0.00 MB/s"
                    }
                    task.speed = speed

                    val downloadedMb = downloadedBytes.toDouble() / (1024 * 1024)
                    if (totalBytes > 0) {
                        val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                        task.progress = progress
                        val totalMb = totalBytes.toDouble() / (1024 * 1024)
                        task.size = String.format(java.util.Locale.US, "%.2f MB", totalMb)
                    } else {
                        task.size = String.format(java.util.Locale.US, "%.2f MB", downloadedMb)
                    }

                    // Throttle notification updates
                    if (now - lastNotifyTime > 800) {
                        lastNotifyTime = now
                        notificationManager.notify(task.notificationId, buildTaskNotification(task))
                        sendBroadcast(task, BROADCAST_PROGRESS)
                    }
                }
            }
        }
        videoConn.disconnect()

        if (!destFile.exists() || destFile.length() == 0L) {
            throw Exception("Download produced an empty file")
        }

        task.progress = 100
        task.tempFilePath = destFile.absolutePath
        Log.d(TAG, "Direct download complete. File: ${destFile.absolutePath} (${destFile.length()} bytes)")

        return destFile
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

        return downloadFileDirectly(task, videoUrl, "${displayTitle}_$tweetId.mp4")
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

            // Timeout: 5 minutes for TikTok (multiple API fallbacks + yt-dlp), 3 minutes for others
            val timeoutMs = if (isTikTokUrl(task.url)) 300_000L else 180_000L
            kotlinx.coroutines.withTimeout(timeoutMs) {
                if (isTwitterUrl(task.url)) {
                    try {
                        tempFile = executeYtDlp(task, cacheDownloadsDir)
                    } catch (ytdlpError: Exception) {
                        Log.w(TAG, "yt-dlp failed for Twitter URL, trying vxtwitter fallback: ${ytdlpError.message}")
                        tempFile = downloadTwitterFallback(task)
                        usedTwitterFallback = true
                    }
                } else if (isTikTokUrl(task.url)) {
                    // TikTok: Use multi-approach system (tikwm POST → tikwm GET → yt-dlp)
                    tempFile = downloadTikTok(task)
                } else if (isInstagramUrl(task.url)) {
                    try {
                        tempFile = executeYtDlp(task, cacheDownloadsDir)
                    } catch (ytdlpError: Exception) {
                        Log.w(TAG, "yt-dlp failed for Instagram URL, trying scraper fallback: ${ytdlpError.message}")
                        tempFile = downloadInstagramFallback(task)
                    }
                } else {
                    tempFile = executeYtDlp(task, cacheDownloadsDir)
                }
            }

            // Copy to public downloads
            val finalTempFile = tempFile
            if (finalTempFile == null || !finalTempFile.exists()) {
                // Last resort: find newest file in cache
                val files = cacheDownloadsDir.listFiles()
                tempFile = files?.maxByOrNull { it.lastModified() }
            }

            var savedFilePath: String? = null
            var actualResolution: String? = null
            var localThumbnailPath: String? = null
            if (tempFile != null && tempFile.exists()) {
                savedFilePath = tempFile.name
                actualResolution = getVideoResolutionLabel(tempFile.absolutePath)
                localThumbnailPath = generateVideoThumbnail(tempFile.absolutePath)
                if (!localThumbnailPath.isNullOrEmpty()) {
                    task.thumbnailUrl = localThumbnailPath
                }
                val copied = saveFileToPublicDownloads(tempFile)
                if (!copied) {
                    throw Exception("Failed to copy file to public Downloads folder.")
                }
            } else {
                throw Exception("Downloaded file not found in cache directory.")
            }

            val displayFormat = if (!actualResolution.isNullOrEmpty()) {
                actualResolution
            } else {
                task.formatLabel
            }

            // Save to history DB
            db.downloadHistoryDao().insert(
                DownloadHistoryEntity(
                    userId = task.userId,
                    url = task.url,
                    title = task.title,
                    formatLabel = displayFormat,
                    filePath = "Downloads/ChatSnapDownloads/$savedFilePath",
                    fileSize = task.size,
                    status = "COMPLETED",
                    thumbnailUrl = task.thumbnailUrl
                )
            )

            // Remove progress notification, show completed
            notificationManager.cancel(task.notificationId)
            showCompletedNotification(task)

            // Broadcast completion to activity
            sendBroadcast(task, BROADCAST_COMPLETED)

        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${task.taskId}", e)
            try {
                YoutubeDL.getInstance().destroyProcessById(task.taskId)
            } catch (_: Exception) {}

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
                        errorMessage = e.message,
                        thumbnailUrl = task.thumbnailUrl
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
            checkAndStartQueuedDownloads()

            // Update summary or stop service if no more tasks
            if (activeTasks.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                notificationManager.notify(FOREGROUND_NOTIFICATION_ID, buildSummaryNotification())
            }
        }
    }

    private fun checkAndStartQueuedDownloads() {
        val activeCount = activeTasks.size
        if (activeCount >= maxConcurrentTasks) return

        val nextTask = sharedTasks.firstOrNull { it.status == DownloadTask.Status.QUEUED }
        if (nextTask != null) {
            nextTask.status = DownloadTask.Status.DOWNLOADING
            
            val intent = Intent(this, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_TASK_ID, nextTask.id)
                putExtra(EXTRA_URL, nextTask.url)
                putExtra(EXTRA_FORMAT_LABEL, nextTask.formatLabel)
                putExtra(EXTRA_FORMAT_OPTION, nextTask.formatOption)
                putExtra(EXTRA_IS_PLAYLIST, nextTask.isPlaylist)
                putExtra(EXTRA_USER_ID, nextTask.userId ?: "")
                putExtra(EXTRA_THUMBNAIL_URL, nextTask.thumbnailUrl)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            // Recursively start more if slots allow
            checkAndStartQueuedDownloads()
        }
    }

    /**
     * Standard yt-dlp download execution. Returns the downloaded temp file.
     */
    private suspend fun executeYtDlp(task: TaskInfo, cacheDownloadsDir: File, customUrl: String? = null): File {
        val request = YoutubeDLRequest(customUrl ?: task.url)

        request.addOption("-o", "${cacheDownloadsDir.absolutePath}/%(title)s.%(ext)s")

        // Playlist setting
        if (task.isPlaylist) {
            request.addOption("--yes-playlist")
        } else {
            request.addOption("--no-playlist")
        }

        val isNonYtSocial = isTikTokUrl(task.url) || isInstagramUrl(task.url) || isTwitterUrl(task.url)
        // Use "b" instead of "best" to suppress yt-dlp deprecation warning
        val formatOptionToUse = if (isNonYtSocial) "b" else task.formatOption

        val isAudioOnly = formatOptionToUse == "bestaudio"
        if (isAudioOnly) {
            request.addOption("-f", "bestaudio")
            request.addOption("--extract-audio")
            request.addOption("--audio-format", "mp3")
        } else {
            request.addOption("-f", formatOptionToUse)
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

        // Instagram fix: Add referer header
        if (isInstagramUrl(task.url)) {
            request.addOption("--add-header", "Referer:https://www.instagram.com/")
            Log.d(TAG, "Instagram URL detected, adding referer header")
        }

        // TikTok fix: Add referer header
        if (isTikTokUrl(task.url)) {
            request.addOption("--add-header", "Referer:https://www.tiktok.com/")
            Log.d(TAG, "TikTok URL detected, adding referer header")
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
        speedRegex.find(line)?.let { 
            val rawSpeed = it.groupValues[1]
            task.speed = convertSpeedToMb(rawSpeed)
        }
        sizeRegex.find(line)?.let { 
            val rawSize = it.groupValues[1]
            task.size = convertSizeToMb(rawSize)
        }

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
        // Also update the in-memory shared tasks list
        val sharedTask = sharedTasks.find { it.id == task.taskId }
        if (sharedTask != null) {
            sharedTask.progress = task.progress
            sharedTask.speed = task.speed
            sharedTask.size = task.size
            sharedTask.eta = task.eta
            if (!task.title.startsWith("Preparing")) {
                sharedTask.title = task.title
            }
            sharedTask.tempFilePath = task.tempFilePath
            sharedTask.thumbnailUrl = task.thumbnailUrl
            
            // Map broadcast action to DownloadTask.Status
            when (action) {
                BROADCAST_COMPLETED -> {
                    sharedTask.status = DownloadTask.Status.COMPLETED
                    sharedTask.progress = 100
                    sharedTask.speed = "Done"
                    sharedTask.eta = ""
                }
                BROADCAST_FAILED -> {
                    sharedTask.status = DownloadTask.Status.FAILED
                    sharedTask.speed = "Error"
                    sharedTask.eta = ""
                }
                BROADCAST_PROGRESS -> {
                    sharedTask.status = DownloadTask.Status.DOWNLOADING
                }
            }
        }

        val intent = Intent(action).apply {
            putExtra(EXTRA_TASK_ID, task.taskId)
            putExtra(EXTRA_PROGRESS, task.progress)
            putExtra(EXTRA_SPEED, task.speed)
            putExtra(EXTRA_SIZE, task.size)
            putExtra(EXTRA_ETA, task.eta)
            putExtra(EXTRA_TITLE, task.title)
            putExtra(EXTRA_TEMP_FILE_PATH, task.tempFilePath)
            putExtra(EXTRA_THUMBNAIL_URL, task.thumbnailUrl)
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
                            userId = task.userId,
                            url = task.url,
                            title = task.title,
                            formatLabel = task.formatLabel,
                            filePath = null,
                            fileSize = null,
                            status = "CANCELLED",
                            thumbnailUrl = task.thumbnailUrl
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save cancelled history", e)
                }
            }
        }

        // Update shared task status
        val sharedTask = sharedTasks.find { it.id == taskId }
        if (sharedTask != null) {
            sharedTask.status = DownloadTask.Status.CANCELLED
            sharedTask.speed = "Cancelled"
            sharedTask.eta = ""
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

    private fun convertSpeedToMb(speedStr: String): String {
        try {
            val clean = speedStr.trim().lowercase()
            val numberPart = clean.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return speedStr
            return when {
                clean.contains("g") -> {
                    String.format(java.util.Locale.US, "%.2f MB/s", numberPart * 1024)
                }
                clean.contains("m") -> {
                    String.format(java.util.Locale.US, "%.2f MB/s", numberPart)
                }
                clean.contains("k") -> {
                    String.format(java.util.Locale.US, "%.2f MB/s", numberPart / 1024)
                }
                else -> {
                    String.format(java.util.Locale.US, "%.2f MB/s", numberPart / (1024 * 1024))
                }
            }
        } catch (e: Exception) {
            return speedStr
        }
    }

    private fun convertSizeToMb(sizeStr: String): String {
        try {
            val clean = sizeStr.trim().lowercase()
            val numberPart = clean.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return sizeStr
            return when {
                clean.contains("g") -> {
                    String.format(java.util.Locale.US, "%.2f MB", numberPart * 1024)
                }
                clean.contains("m") -> {
                    String.format(java.util.Locale.US, "%.2f MB", numberPart)
                }
                clean.contains("k") -> {
                    String.format(java.util.Locale.US, "%.2f MB", numberPart / 1024)
                }
                else -> {
                    String.format(java.util.Locale.US, "%.2f MB", numberPart / (1024 * 1024))
                }
            }
        } catch (e: Exception) {
            return sizeStr
        }
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }
}

/**
 * A custom SSLSocketFactory that strips the SNI (Server Name Indication) extension from the TLS ClientHello
 * specifically for TikTok CDN hosts (e.g. tiktokcdn-us.com, v16m, akamaized). This allows bypassing ISP-level
 * SNI-based blocking/censorship.
 */
class SNIStrippingSSLSocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    private fun stripSNI(socket: Socket, host: String?): Socket {
        if (socket is SSLSocket && host != null) {
            val hostLower = host.lowercase()
            if (hostLower.contains("tiktokcdn") || hostLower.contains("v16m") || hostLower.contains("akamaized")) {
                try {
                    val params = socket.sslParameters
                    params.serverNames = emptyList()
                    socket.sslParameters = params
                    Log.d("SNIStrip", "Successfully stripped SNI extension for host: $host")
                } catch (e: Exception) {
                    Log.w("SNIStrip", "Failed to strip SNI for host $host: ${e.message}")
                }
            }
        }
        return socket
    }

    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket {
        return stripSNI(delegate.createSocket(s, host, port, autoClose), host)
    }

    override fun createSocket(host: String?, port: Int): Socket {
        return stripSNI(delegate.createSocket(host, port), host)
    }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
        return stripSNI(delegate.createSocket(host, port, localHost, localPort), host)
    }

    override fun createSocket(address: InetAddress?, port: Int): Socket {
        return stripSNI(delegate.createSocket(address, port), address?.hostName)
    }

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
        return stripSNI(delegate.createSocket(address, port, localAddress, localPort), address?.hostName)
    }
}
