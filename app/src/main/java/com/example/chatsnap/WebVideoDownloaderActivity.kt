package com.example.chatsnap

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.Toast
import android.widget.TextView
import android.widget.ImageView
import android.widget.CheckBox
import android.view.ViewGroup
import android.view.LayoutInflater
import android.content.ContentValues
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import coil.load
import com.example.chatsnap.databinding.ActivityWebVideoDownloaderBinding
import com.example.chatsnap.models.DownloadTask
import com.example.chatsnap.services.DownloadService
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

class WebVideoDownloaderActivity : BaseActivity() {

    data class DetectedVideo(
        val videoUrl: String,
        val pageUrl: String,
        val title: String,
        val thumbnailUrl: String? = null,
        var isSelected: Boolean = true
    )

    private lateinit var binding: ActivityWebVideoDownloaderBinding
    private var detectedUrl: String? = null
    private val TAG = "WebVideoDownloader"
    private val detectedVideosList = mutableListOf<DetectedVideo>()
    private var lastDomain: String? = null
    private var shouldClearSessionOnLoad = false

    companion object {
        private const val PREF_NAME = "web_browser_prefs"
        private const val KEY_LAST_USER = "last_active_user_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // ── Per-user WebView session isolation ──
        // On API 28+, each Firebase UID gets its own data directory (cookies, localStorage, cache).
        // On older APIs, we detect user switches and wipe the shared session manually.
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            try {
                android.webkit.WebView.setDataDirectorySuffix(currentUserId)
            } catch (e: Exception) {
                Log.w(TAG, "setDataDirectorySuffix failed: ${e.message}")
            }
        }

        super.onCreate(savedInstanceState)
        binding = ActivityWebVideoDownloaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Detect if a different user logged in and clear stale session data
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val lastUserId = prefs.getString(KEY_LAST_USER, null)
        if (lastUserId != null && lastUserId != currentUserId) {
            Log.d(TAG, "User switched from $lastUserId to $currentUserId — clearing WebView session.")
            clearWebViewSession()
        }
        prefs.edit().putString(KEY_LAST_USER, currentUserId).apply()

        setupToolbar()
        setupWebView()
        setupShortcuts()

        // FAB Click listener to trigger one-tap download
        binding.fabDownload.setOnClickListener {
            triggerDownload()
        }

        // Show dashboard initially
        showDashboard()

        // ── Smart Back Navigation ──
        // WebView browsing  → go back in WebView history
        // WebView, no history → show dashboard
        // Dashboard (home)  → finish() → back to chats
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val isDashboardVisible = binding.scrollDashboard.visibility == View.VISIBLE
                when {
                    !isDashboardVisible && binding.webView.canGoBack() -> {
                        // Step 1: navigate back inside the website
                        binding.webView.goBack()
                    }
                    !isDashboardVisible -> {
                        // Step 2: no more web history → return to dashboard
                        showDashboard()
                    }
                    else -> {
                        // Step 3: already on dashboard → exit to previous screen (chats)
                        finish()
                    }
                }
            }
        })
    }

    /** Wipes all WebView cookies, cache and localStorage for the current profile. */
    private fun clearWebViewSession() {
        // Clear cookies immediately
        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()

        // Clear cache and history (safe even before any page is loaded)
        binding.webView.clearCache(true)
        binding.webView.clearHistory()
        binding.webView.clearFormData()

        // Schedule JS localStorage/sessionStorage clear for after first page load
        shouldClearSessionOnLoad = true

        // Reset playlist
        detectedVideosList.clear()
        lastDomain = null
        Log.d(TAG, "WebView session wiped for user switch.")
    }

    private fun setupToolbar() {
        binding.btnBrowserExit.setOnClickListener {
            finish()
        }

        binding.btnWebBack.setOnClickListener {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            }
        }

        binding.btnWebForward.setOnClickListener {
            if (binding.webView.canGoForward()) {
                binding.webView.goForward()
            }
        }

        binding.btnWebRefresh.setOnClickListener {
            binding.webView.reload()
        }

        binding.btnWebHome.setOnClickListener {
            showDashboard()
        }

        // Address EditText actions
        binding.etWebUrl.setOnEditorActionListener { textView, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                var url = textView.text.toString().trim()
                if (url.isNotEmpty()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        // If it doesn't contain a dot, treat as search query
                        url = if (url.contains(".") && !url.contains(" ")) {
                            "https://$url"
                        } else {
                            "https://www.google.com/search?q=" + Uri.encode(url)
                        }
                    }
                    loadUrlInWebView(url)
                    hideKeyboard()
                }
                true
            } else {
                false
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView

        // Enable Cookies Persistence
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptCanOpenWindowsAutomatically = true // Allow window open redirects
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            
            // Set standard Chrome Mobile user agent to spoof desktop/mobile app and bypass WebView login restrictions
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
        }

        // JS Bridge
        webView.addJavascriptInterface(VideoDetectorBridge { videoUrl, pageUrl, title, poster ->
            runOnUiThread {
                handleVideoDetected(videoUrl, pageUrl, title, poster)
            }
        }, "VideoDetectorBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                // Keep links inside WebView unless it is an external protocol
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load external link: ${e.message}")
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.pbLoader.visibility = View.VISIBLE
                binding.pbLoader.progress = 10
                url?.let {
                    binding.etWebUrl.setText(it)
                    updateUserAgentForUrl(it)
                    
                    try {
                        val uri = Uri.parse(it)
                        val host = uri.host ?: ""
                        if (host.isNotEmpty() && host != lastDomain) {
                            detectedVideosList.clear()
                            lastDomain = host
                            Log.d(TAG, "Domain changed to $host. Playlist cleared.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse URL host", e)
                    }
                }
                // Hide download button on page transition
                hideDownloadButton()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.pbLoader.visibility = View.GONE
                url?.let {
                    binding.etWebUrl.setText(it)
                }

                // If a user switch was detected, clear localStorage/sessionStorage now that page is loaded
                if (shouldClearSessionOnLoad) {
                    shouldClearSessionOnLoad = false
                    view?.evaluateJavascript(
                        "(function(){ try{ localStorage.clear(); sessionStorage.clear(); }catch(e){} })()",
                        null
                    )
                    Log.d(TAG, "localStorage/sessionStorage cleared after user switch.")
                }

                // Inject the focused video detector JS script
                injectVideoDetector()
            }

            // Intercept direct media requests (like mp4, m3u8) on generic sites
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                val path = request.url.path?.lowercase() ?: ""
                
                if (path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".webm") || 
                    path.endsWith(".m3u8") || path.endsWith(".mpd")) {
                    runOnUiThread {
                        val currentUrl = webView.url ?: ""
                        if (!isSupportedSocialPlatform(currentUrl)) {
                            // On generic sites, intercept direct mp4/m3u8 URLs
                            handleVideoDetected(url, currentUrl, "Direct Stream Video", "")
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                binding.pbLoader.progress = newProgress
                if (newProgress >= 100) {
                    binding.pbLoader.visibility = View.GONE
                } else {
                    binding.pbLoader.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setupShortcuts() {
        binding.cardTikTok.setOnClickListener { loadUrlInWebView("https://www.tiktok.com") }
        binding.cardInstagram.setOnClickListener { loadUrlInWebView("https://www.instagram.com") }
        binding.cardTwitter.setOnClickListener { loadUrlInWebView("https://x.com") }
        binding.cardWhatsApp.setOnClickListener { loadUrlInWebView("https://web.whatsapp.com") }
        binding.cardYouTube.setOnClickListener { loadUrlInWebView("https://www.youtube.com") }
        binding.cardFacebook.setOnClickListener { loadUrlInWebView("https://www.facebook.com") }
    }

    private fun loadUrlInWebView(url: String) {
        binding.scrollDashboard.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        updateUserAgentForUrl(url)
        binding.webView.loadUrl(url)
    }

    private fun updateUserAgentForUrl(url: String) {
        val lowerUrl = url.lowercase()
        val isWhatsApp = lowerUrl.contains("web.whatsapp.com") || lowerUrl.contains("whatsapp.com")
        if (isWhatsApp) {
            // Spoof desktop Chrome User-Agent to prevent WhatsApp Web from redirecting to mobile block page
            binding.webView.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        } else {
            // Restore Mobile Chrome User-Agent for standard mobile social media viewing
            binding.webView.settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
        }
    }

    private fun showDashboard() {
        binding.webView.stopLoading()
        binding.webView.loadUrl("about:blank")
        binding.webView.visibility = View.GONE
        binding.scrollDashboard.visibility = View.VISIBLE
        binding.etWebUrl.setText("")
        hideDownloadButton()
    }

    private fun handleVideoDetected(videoUrl: String, pageUrl: String, title: String = "", poster: String = "") {
        val targetUrl = when {
            // For WhatsApp Web, we must use the direct blob URL because yt-dlp cannot scrape WhatsApp
            pageUrl.lowercase().contains("whatsapp.com") -> videoUrl
            isSupportedSocialPlatform(pageUrl) -> pageUrl
            videoUrl.isNotEmpty() && (videoUrl.startsWith("http") || videoUrl.startsWith("blob:")) -> videoUrl
            else -> pageUrl
        }

        if (targetUrl.isEmpty() || targetUrl == "about:blank") return

        val videoTitle = if (title.isNotEmpty()) title else "Video from ${Uri.parse(pageUrl).host ?: "Social Platform"}"
        val posterUrl = if (poster.isNotEmpty() && poster.startsWith("http")) poster else null

        // Add to playlist without duplicates
        val exists = detectedVideosList.any { it.videoUrl == videoUrl }
        if (!exists && videoUrl.isNotEmpty()) {
            detectedVideosList.add(DetectedVideo(videoUrl = videoUrl, pageUrl = pageUrl, title = videoTitle, thumbnailUrl = posterUrl))
        }

        detectedUrl = targetUrl
        showDownloadButton()
    }

    private fun showDownloadButton() {
        if (binding.fabDownload.visibility != View.VISIBLE) {
            binding.fabDownload.visibility = View.VISIBLE
            binding.fabDownload.alpha = 0f
            binding.fabDownload.scaleX = 0.5f
            binding.fabDownload.scaleY = 0.5f
            binding.fabDownload.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .setInterpolator(android.view.animation.OvershootInterpolator())
                .start()
        }
    }

    private fun hideDownloadButton() {
        if (binding.fabDownload.visibility == View.VISIBLE) {
            binding.fabDownload.animate()
                .alpha(0f)
                .scaleX(0.5f)
                .scaleY(0.5f)
                .setDuration(200)
                .withEndAction {
                    binding.fabDownload.visibility = View.GONE
                    detectedUrl = null
                }
                .start()
        } else {
            detectedUrl = null
        }
    }

    private fun triggerDownload() {
        if (detectedVideosList.size > 1) {
            showDetectedVideosBottomSheet()
        } else {
            val video = detectedVideosList.firstOrNull()
            if (video != null) {
                downloadSingleVideoItem(video)
                Toast.makeText(this, "✓ Added to Download Queue!", Toast.LENGTH_SHORT).show()
            } else {
                val url = detectedUrl ?: return
                downloadSingleVideoItem(DetectedVideo(url, url, "Focused Web Video"))
                Toast.makeText(this, "✓ Added to Download Queue!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun downloadSingleVideoItem(item: DetectedVideo) {
        val url = item.videoUrl
        
        if (url.startsWith("blob:")) {
            // Trigger JavaScript chunked transfer of the WhatsApp Web / Blob video
            val safeUrl = url.replace("'", "\\'")
            val cleanTitle = item.title.replace("[^a-zA-Z0-9]".toRegex(), "_").take(30)
            val filename = "whatsapp_${cleanTitle}_${System.currentTimeMillis()}.mp4"
            binding.webView.evaluateJavascript("window.downloadBlob('$safeUrl', '$filename')", null)
            return
        }
        
        // Standard social media platforms (or direct mp4 links) download using service
        val finalDownloadUrl = if (isSupportedSocialPlatform(item.pageUrl)) item.pageUrl else url
        
        val taskId = "dl_task_${System.currentTimeMillis()}_${(100..999).random()}"
        val formatLabel = "Best"
        val formatOption = "bestvideo+bestaudio/best"
        
        val isYt = finalDownloadUrl.lowercase().contains("youtube.com") || finalDownloadUrl.lowercase().contains("youtu.be")
        val ytId = if (isYt) extractYoutubeId(finalDownloadUrl) else null
        val customThumbnail = item.thumbnailUrl ?: if (!ytId.isNullOrEmpty()) "https://img.youtube.com/vi/$ytId/mqdefault.jpg" else null

        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: ""

        val newTask = DownloadTask(
            id = taskId,
            url = finalDownloadUrl,
            formatLabel = formatLabel,
            formatOption = formatOption,
            isPlaylist = false,
            title = if (item.title.length > 30) item.title.take(30) + "..." else item.title,
            thumbnailUrl = customThumbnail,
            userId = currentUserId
        )

        // Queue it in background downloads
        DownloadService.sharedTasks.add(0, newTask)

        // Launch the service
        val intent = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START_DOWNLOAD
            putExtra(DownloadService.EXTRA_TASK_ID, newTask.id)
            putExtra(DownloadService.EXTRA_URL, newTask.url)
            putExtra(DownloadService.EXTRA_FORMAT_LABEL, newTask.formatLabel)
            putExtra(DownloadService.EXTRA_FORMAT_OPTION, newTask.formatOption)
            putExtra(DownloadService.EXTRA_IS_PLAYLIST, newTask.isPlaylist)
            putExtra(DownloadService.EXTRA_USER_ID, currentUserId)
            putExtra(DownloadService.EXTRA_THUMBNAIL_URL, newTask.thumbnailUrl)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // Asynchronously load thumbnail if not youtube and none provided
        if (newTask.thumbnailUrl.isNullOrEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val thumb = fetchOgImageThumbnail(finalDownloadUrl)
                if (!thumb.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        newTask.thumbnailUrl = thumb
                    }
                }
            }
        }
    }

    private fun showDetectedVideosBottomSheet() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_detected_videos, null)
        dialog.setContentView(dialogView)

        val rv = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvDetectedVideos)
        val cbSelectAll = dialogView.findViewById<CheckBox>(R.id.cbSelectAll)
        val btnDownload = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDownloadSelected)

        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        
        fun updateButtonText() {
            val selectedCount = detectedVideosList.count { it.isSelected }
            btnDownload.text = "Download Selected ($selectedCount)"
            btnDownload.isEnabled = selectedCount > 0
        }

        val adapter = DetectedVideosAdapter(detectedVideosList) {
            cbSelectAll.setOnCheckedChangeListener(null)
            cbSelectAll.isChecked = detectedVideosList.all { it.isSelected }
            cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
                detectedVideosList.forEach { it.isSelected = isChecked }
                rv.adapter?.notifyDataSetChanged()
                updateButtonText()
            }
            updateButtonText()
        }
        rv.adapter = adapter

        cbSelectAll.isChecked = detectedVideosList.all { it.isSelected }
        cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
            detectedVideosList.forEach { it.isSelected = isChecked }
            adapter.notifyDataSetChanged()
            updateButtonText()
        }

        updateButtonText()

        btnDownload.setOnClickListener {
            dialog.dismiss()
            val selectedVideos = detectedVideosList.filter { it.isSelected }
            if (selectedVideos.isNotEmpty()) {
                selectedVideos.forEach { item ->
                    downloadSingleVideoItem(item)
                }
                Toast.makeText(this, "✓ Added ${selectedVideos.size} videos to Download Queue!", Toast.LENGTH_LONG).show()
            }
        }

        dialog.show()
    }

    inner class DetectedVideosAdapter(
        private val videos: List<DetectedVideo>,
        private val onSelectionChanged: () -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<DetectedVideosAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val cbSelect: CheckBox = view.findViewById(R.id.cbSelect)
            val ivThumbnail: ImageView = view.findViewById(R.id.ivVideoThumbnail)
            val tvTitle: TextView = view.findViewById(R.id.tvVideoTitle)
            val tvUrl: TextView = view.findViewById(R.id.tvVideoUrl)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_detected_video, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = videos[position]
            holder.tvTitle.text = item.title
            
            holder.tvUrl.text = try {
                val uri = android.net.Uri.parse(item.videoUrl)
                if (item.videoUrl.startsWith("blob:")) "WhatsApp Web (Decrypted Blob)"
                else uri.host ?: item.videoUrl
            } catch (e: Exception) {
                item.videoUrl
            }

            holder.cbSelect.setOnCheckedChangeListener(null)
            holder.cbSelect.isChecked = item.isSelected
            holder.cbSelect.setOnCheckedChangeListener { _, isChecked ->
                item.isSelected = isChecked
                onSelectionChanged()
            }

            holder.itemView.setOnClickListener {
                holder.cbSelect.isChecked = !holder.cbSelect.isChecked
            }

            if (!item.thumbnailUrl.isNullOrEmpty()) {
                holder.ivThumbnail.load(item.thumbnailUrl) {
                    crossfade(true)
                    placeholder(android.R.drawable.ic_media_play)
                    error(android.R.drawable.ic_media_play)
                }
            } else {
                holder.ivThumbnail.setImageResource(android.R.drawable.ic_media_play)
            }
        }

        override fun getItemCount() = videos.size
    }

    private fun injectVideoDetector() {
        val js = """
            (function() {
                // ── State tracking ──
                var currentFocusedVideo = null;
                var currentFocusedSrc = "";  // track src so same element re-fires on src swap

                function findPermalink(video) {
                    var current = video;
                    while (current && current !== document.body) {
                        var links = current.getElementsByTagName('a');
                        for (var i = 0; i < links.length; i++) {
                            var href = links[i].href;
                            if (href) {
                                if (href.indexOf('/video/') !== -1 || 
                                    href.indexOf('/p/') !== -1 || 
                                    href.indexOf('/reel/') !== -1 || 
                                    href.indexOf('/status/') !== -1 || 
                                    href.indexOf('/watch?v=') !== -1 || 
                                    href.indexOf('/shorts/') !== -1) {
                                    return href;
                                }
                            }
                        }
                        current = current.parentElement;
                    }
                    return null;
                }

                function isElementInViewport(el) {
                    var rect = el.getBoundingClientRect();
                    var viewHeight = window.innerHeight || document.documentElement.clientHeight;
                    var viewWidth = window.innerWidth || document.documentElement.clientWidth;
                    
                    if (rect.bottom < 0 || rect.top > viewHeight || rect.right < 0 || rect.left > viewWidth) {
                        return 0;
                    }
                    
                    var visibleHeight = Math.min(rect.bottom, viewHeight) - Math.max(rect.top, 0);
                    var visibleWidth = Math.min(rect.right, viewWidth) - Math.max(rect.left, 0);
                    var visibleArea = visibleHeight * visibleWidth;
                    var totalArea = rect.height * rect.width;
                    
                    return totalArea > 0 ? (visibleArea / totalArea) : 0;
                }

                function notifyVideo(video) {
                    var src = video.src;
                    if (!src || src.indexOf('blob:') === 0) {
                        var sources = video.getElementsByTagName('source');
                        for (var i = 0; i < sources.length; i++) {
                            if (sources[i].src && sources[i].src.indexOf('blob:') !== 0) {
                                src = sources[i].src;
                                break;
                            }
                        }
                    }
                    
                    var permalink = findPermalink(video);
                    var pageUrl = permalink || window.location.href;
                    
                    // Extract poster and adjacent text title
                    var poster = video.poster || "";
                    var titleText = "";
                    var current = video;
                    for (var i = 0; i < 3 && current; i++) {
                        var text = current.innerText || "";
                        if (text.trim().length > 10) {
                            titleText = text.trim().substring(0, 60).replace(/\n/g, " ");
                            break;
                        }
                        current = current.parentElement;
                    }
                    if (!titleText) {
                        titleText = "Video on " + window.location.hostname;
                    }

                    if (src && src.indexOf('http') === 0) {
                        window.VideoDetectorBridge.onVideoDetected(src, pageUrl, titleText, poster);
                    } else if (src && src.indexOf('blob:') === 0) {
                        window.VideoDetectorBridge.onVideoDetected(src, pageUrl, titleText, poster);
                    } else {
                        window.VideoDetectorBridge.onVideoDetected("", pageUrl, titleText, poster);
                    }
                }

                // Pick best playing video in viewport
                function pickBestVideo() {
                    var vids = document.getElementsByTagName('video');
                    var bestVideo = null;
                    var bestRatio = 0;

                    for (var i = 0; i < vids.length; i++) {
                        var video = vids[i];
                        if (!video.paused && !video.ended) {
                            var ratio = isElementInViewport(video);
                            if (ratio > bestRatio) {
                                bestRatio = ratio;
                                bestVideo = video;
                            }
                        }
                    }

                    // Lowered threshold to 15% visibility to detect playing videos covered by overlays
                    if (bestVideo && bestRatio >= 0.15) {
                        // Get the actual current src (blob or http) for this video
                        var activeSrc = bestVideo.src || bestVideo.currentSrc || "";
                        // Re-notify if: different element OR same element with different src (TikTok/Instagram reuse same <video>)
                        if (bestVideo !== currentFocusedVideo || activeSrc !== currentFocusedSrc) {
                            currentFocusedVideo = bestVideo;
                            currentFocusedSrc = activeSrc;
                            notifyVideo(bestVideo);
                        }
                    } else {
                        if (currentFocusedVideo) {
                            currentFocusedVideo = null;
                            currentFocusedSrc = "";
                            window.VideoDetectorBridge.onVideoPaused();
                        }
                    }
                }

                function setupVideoListeners(video) {
                    if (video._csTracked) return;
                    video._csTracked = true;

                    video.addEventListener('play', function() { pickBestVideo(); });
                    video.addEventListener('playing', function() { pickBestVideo(); });
                    video.addEventListener('pause', function() { setTimeout(pickBestVideo, 300); });
                    video.addEventListener('ended', function() { setTimeout(pickBestVideo, 300); });
                    // 'loadeddata' fires when new src is loaded into same <video> element (TikTok/Instagram/FB)
                    video.addEventListener('loadeddata', function() { setTimeout(pickBestVideo, 100); });
                    // 'emptied' fires when src is cleared before new one is set
                    video.addEventListener('emptied', function() {
                        if (currentFocusedVideo === video) {
                            currentFocusedSrc = "";
                        }
                    });
                }

                // Initial scan
                var videos = document.getElementsByTagName('video');
                for (var i = 0; i < videos.length; i++) {
                    setupVideoListeners(videos[i]);
                }

                // Track scroll events on window to update active video immediately
                window.addEventListener('scroll', function() {
                    pickBestVideo();
                }, true);

                // SPA history updates
                var pushState = history.pushState;
                history.pushState = function() {
                    pushState.apply(history, arguments);
                    setTimeout(scanAll, 500);
                };
                var replaceState = history.replaceState;
                history.replaceState = function() {
                    replaceState.apply(history, arguments);
                    setTimeout(scanAll, 500);
                };
                window.addEventListener('popstate', function() { setTimeout(scanAll, 500); });

                function scanAll() {
                    var vids = document.getElementsByTagName('video');
                    for (var i = 0; i < vids.length; i++) {
                        setupVideoListeners(vids[i]);
                    }
                    pickBestVideo();
                }

                // Global function to chunk and download blob URLs
                window.downloadBlob = function(blobUrl, filename) {
                    fetch(blobUrl)
                      .then(function(r) { return r.blob(); })
                      .then(function(blob) {
                          var reader = new FileReader();
                          reader.onloadend = function() {
                              var base64 = reader.result.split(',')[1];
                              var chunkSize = 256 * 1024;
                              var totalChunks = Math.ceil(base64.length / chunkSize);
                              var transferId = "blob_" + Date.now();
                              
                              window.VideoDetectorBridge.onBlobTransferStart(transferId, totalChunks, filename);
                              
                              for (var i = 0; i < totalChunks; i++) {
                                  var start = i * chunkSize;
                                  var end = Math.min(start + chunkSize, base64.length);
                                  var chunk = base64.substring(start, end);
                                  window.VideoDetectorBridge.onBlobChunkReceived(transferId, i, chunk);
                              }
                          };
                          reader.readAsDataURL(blob);
                      }).catch(function(err) {
                          console.error("Blob fetch failed: " + err);
                      });
                };

                // MutationObserver for lazy loading
                var observer = new MutationObserver(function(mutations) {
                    mutations.forEach(function(mutation) {
                        mutation.addedNodes.forEach(function(node) {
                            if (node.tagName === 'VIDEO') {
                                setupVideoListeners(node);
                            } else if (node.getElementsByTagName) {
                                var childVideos = node.getElementsByTagName('video');
                                for (var i = 0; i < childVideos.length; i++) {
                                    setupVideoListeners(childVideos[i]);
                                }
                            }
                        });
                    });
                });
                observer.observe(document.body, { childList: true, subtree: true });
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(js, null)
    }

    private fun isSupportedSocialPlatform(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("tiktok.com") ||
               lower.contains("instagram.com") || lower.contains("instagr.am") ||
               lower.contains("x.com") || lower.contains("twitter.com") ||
               lower.contains("youtube.com") || lower.contains("youtu.be") ||
               lower.contains("facebook.com") || lower.contains("fb.watch")
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

            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }

            val html = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

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
            Log.e(TAG, "Failed to fetch thumbnail for $url: ${e.message}")
        }
        return null
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etWebUrl.windowToken, 0)
    }

    // Keep track of active base64 blob transfers
    private val activeTransfers = java.util.concurrent.ConcurrentHashMap<String, BlobTransfer>()

    data class BlobTransfer(
        val transferId: String,
        val totalChunks: Int,
        val filename: String,
        val chunks: Array<String?>
    )

    private fun saveCompletedBlobTransfer(transfer: BlobTransfer) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Assemble base64 string
                val base64Builder = StringBuilder()
                for (chunk in transfer.chunks) {
                    base64Builder.append(chunk)
                }
                val base64Data = base64Builder.toString()
                val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)

                // 2. Write to temp file in cache
                val cacheFolder = File(cacheDir, "downloads")
                if (!cacheFolder.exists()) cacheFolder.mkdirs()
                val tempFile = File(cacheFolder, transfer.filename)
                tempFile.writeBytes(bytes)

                // 3. Save to public downloads
                val displayName = transfer.filename
                val success = saveFileToPublicDownloads(tempFile)

                withContext(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(this@WebVideoDownloaderActivity, "✓ Video saved to Downloads!", Toast.LENGTH_LONG).show()
                        
                        // Add to download history DB
                        saveToHistoryDb(displayName)

                        // Update shared task status
                        val task = DownloadService.sharedTasks.find { it.id == transfer.transferId }
                        if (task != null) {
                            task.status = DownloadTask.Status.COMPLETED
                            task.progress = 100
                            task.speed = "Done"
                            task.eta = ""
                        }
                        // Send complete broadcast
                        val compIntent = Intent(DownloadService.BROADCAST_COMPLETED).apply {
                            putExtra(DownloadService.EXTRA_TASK_ID, transfer.transferId)
                            putExtra(DownloadService.EXTRA_TITLE, transfer.filename)
                        }
                        LocalBroadcastManager.getInstance(this@WebVideoDownloaderActivity).sendBroadcast(compIntent)
                    } else {
                        Toast.makeText(this@WebVideoDownloaderActivity, "Error: Failed to save video", Toast.LENGTH_SHORT).show()
                        markTaskFailed(transfer.transferId, transfer.filename, "Failed to write bytes to disk")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save blob video: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@WebVideoDownloaderActivity, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    markTaskFailed(transfer.transferId, transfer.filename, e.localizedMessage ?: "Unknown extraction error")
                }
            }
        }
    }

    private fun markTaskFailed(transferId: String, filename: String, errorMsg: String) {
        val task = DownloadService.sharedTasks.find { it.id == transferId }
        if (task != null) {
            task.status = DownloadTask.Status.FAILED
            task.speed = "Error"
        }
        val failIntent = Intent(DownloadService.BROADCAST_FAILED).apply {
            putExtra(DownloadService.EXTRA_TASK_ID, transferId)
            putExtra(DownloadService.EXTRA_TITLE, filename)
            putExtra(DownloadService.EXTRA_ERROR_MESSAGE, errorMsg)
        }
        LocalBroadcastManager.getInstance(this@WebVideoDownloaderActivity).sendBroadcast(failIntent)
    }

    private fun saveToHistoryDb(filename: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = com.example.chatsnap.models.AppDatabase.getInstance(this@WebVideoDownloaderActivity)
                val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
                db.downloadHistoryDao().insert(
                    com.example.chatsnap.models.DownloadHistoryEntity(
                        userId = currentUserId,
                        url = "whatsapp_blob_download",
                        title = filename,
                        formatLabel = "MP4",
                        filePath = "Downloads/ChatSnapDownloads/$filename",
                        fileSize = "Blob File",
                        status = "COMPLETED",
                        thumbnailUrl = ""
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert into download history", e)
            }
        }
    }

    private fun saveFileToPublicDownloads(srcFile: File): Boolean {
        val resolver = contentResolver
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, srcFile.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/ChatSnapDownloads")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        } else {
            val destFile = File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "ChatSnapDownloads/${srcFile.name}"
            )
            destFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
            try {
                srcFile.inputStream().use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                srcFile.delete()
                return true
            } catch (e: java.lang.Exception) {
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
                contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            srcFile.delete()
            true
        } catch (e: java.lang.Exception) {
            Log.e(TAG, "Failed to save to MediaStore", e)
            try { resolver.delete(uri, null, null) } catch (_: java.lang.Exception) {}
            false
        }
    }

    inner class VideoDetectorBridge(private val onDetected: (String, String, String, String) -> Unit) {
        @JavascriptInterface
        fun onVideoDetected(videoUrl: String, pageUrl: String, title: String, poster: String) {
            onDetected(videoUrl, pageUrl, title, poster)
        }

        @JavascriptInterface
        fun onVideoPaused() {
            runOnUiThread {
                hideDownloadButton()
            }
        }

        @JavascriptInterface
        fun onBlobTransferStart(transferId: String, totalChunks: Int, filename: String) {
            Log.d(TAG, "Blob transfer started: $transferId, chunks: $totalChunks, file: $filename")
            
            // Add task directly to DownloadService sharedTasks so it appears in standard downloader UI
            val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
            val newTask = DownloadTask(
                id = transferId,
                url = "whatsapp_blob_download",
                formatLabel = "MP4",
                formatOption = "",
                isPlaylist = false,
                status = DownloadTask.Status.DOWNLOADING,
                title = filename,
                progress = 0,
                speed = "Extracting...",
                userId = currentUserId
            )
            DownloadService.sharedTasks.add(0, newTask)

            // Broadcast initial progress to update DownloaderActivity queue
            val initIntent = Intent(DownloadService.BROADCAST_PROGRESS).apply {
                putExtra(DownloadService.EXTRA_TASK_ID, transferId)
                putExtra(DownloadService.EXTRA_PROGRESS, 0)
                putExtra(DownloadService.EXTRA_SPEED, "Extracting...")
                putExtra(DownloadService.EXTRA_TITLE, filename)
            }
            LocalBroadcastManager.getInstance(this@WebVideoDownloaderActivity).sendBroadcast(initIntent)

            activeTransfers[transferId] = BlobTransfer(
                transferId = transferId,
                totalChunks = totalChunks,
                filename = filename,
                chunks = arrayOfNulls(totalChunks)
            )
            runOnUiThread {
                Toast.makeText(this@WebVideoDownloaderActivity, "Extracting video from browser memory...", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun onBlobChunkReceived(transferId: String, chunkIndex: Int, chunkData: String) {
            val transfer = activeTransfers[transferId] ?: return
            if (chunkIndex in 0 until transfer.totalChunks) {
                transfer.chunks[chunkIndex] = chunkData
            }

            // Calculate and broadcast progress updates to downloader UI
            val progress = ((chunkIndex + 1) * 100) / transfer.totalChunks
            val task = DownloadService.sharedTasks.find { it.id == transferId }
            if (task != null) {
                task.progress = progress
            }
            val progressIntent = Intent(DownloadService.BROADCAST_PROGRESS).apply {
                putExtra(DownloadService.EXTRA_TASK_ID, transferId)
                putExtra(DownloadService.EXTRA_PROGRESS, progress)
                putExtra(DownloadService.EXTRA_SPEED, "Extracting...")
                putExtra(DownloadService.EXTRA_TITLE, transfer.filename)
            }
            LocalBroadcastManager.getInstance(this@WebVideoDownloaderActivity).sendBroadcast(progressIntent)

            // Check if all chunks received
            val isComplete = transfer.chunks.all { it != null }
            if (isComplete) {
                activeTransfers.remove(transferId)
                saveCompletedBlobTransfer(transfer)
            }
        }
    }
}
