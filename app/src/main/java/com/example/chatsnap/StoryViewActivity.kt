package com.example.chatsnap

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import coil.load
import com.example.chatsnap.databinding.ActivityStoryViewBinding
import com.example.chatsnap.models.Story
import com.example.chatsnap.models.StoryViewerInfo
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar

class StoryViewActivity : BaseActivity() {
    private lateinit var binding: ActivityStoryViewBinding
    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    
    private var stories: MutableList<Story> = mutableListOf()
    private var counter = 0
    private val handler = Handler(Looper.getMainLooper())
    private var storyDuration = 6000L 
    private val viewedStories = mutableSetOf<String>()
    
    private var startTime = 0L
    private val progressInterval = 50L // 50ms interval for smooth animation

    // Story Music
    private var storyMediaPlayer: MediaPlayer? = null

    // Story Reactions
    private val reactionEmojis = listOf("❤️", "😂", "😮", "😢", "😍", "🔥")

    private val storyStepRunnable = object : Runnable {
        override fun run() {
            if (counter < stories.size) {
                showStory(counter)
                counter++
            } else {
                safeFinish()
            }
        }
    }

    private val progressBarRunnable = object : Runnable {
        override fun run() {
            if (counter > 0 && counter <= stories.size) {
                val pb = binding.progressContainer.getChildAt(counter - 1) as? ProgressBar
                if (pb != null) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val progress = (elapsed * 100 / storyDuration).toInt().coerceAtMost(100)
                    pb.progress = progress
                    
                    if (progress >= 100) {
                        handler.post(storyStepRunnable)
                    } else {
                        handler.postDelayed(this, progressInterval)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityStoryViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        firestore = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        val userId = intent.getStringExtra("USER_ID") ?: ""
        if (userId.isEmpty()) {
            finish()
            return
        }

        loadStories(userId)
        
        binding.btnClose.setOnClickListener { safeFinish() }
        
        binding.skip.setOnClickListener {
            nextStory()
        }

        binding.reverse.setOnClickListener {
            previousStory()
        }

        binding.btnDeleteStory.setOnClickListener {
            showDeleteConfirmation()
        }

        binding.btnHighlight.setOnClickListener {
            if (counter > 0 && counter <= stories.size) {
                addToHighlight(stories[counter - 1])
            }
        }

        // Reaction buttons
        val reactionButtons = listOf(
            binding.btnReactHeart, binding.btnReactLaugh, binding.btnReactWow,
            binding.btnReactSad, binding.btnReactLove, binding.btnReactFire
        )
        reactionButtons.forEachIndexed { i, btn ->
            btn.setOnClickListener {
                val story = if (counter > 0 && counter <= stories.size) stories[counter - 1] else return@setOnClickListener
                sendReaction(story, reactionEmojis[i])
                // Animate the tapped button
                btn.animate().scaleX(1.4f).scaleY(1.4f).setDuration(150)
                    .withEndAction { btn.animate().scaleX(1f).scaleY(1f).setDuration(150).start() }.start()
            }
        }
    }

    private fun nextStory() {
        handler.removeCallbacks(progressBarRunnable)
        if (counter < stories.size) {
            com.example.chatsnap.utils.AnimUtils.flip(binding.storyContent) {
                showStory(counter)
                counter++
            }
        } else {
            safeFinish()
        }
    }

    private fun previousStory() {
        handler.removeCallbacks(progressBarRunnable)
        counter = (counter - 2).coerceAtLeast(0)
        com.example.chatsnap.utils.AnimUtils.flip(binding.storyContent) {
            showStory(counter)
            counter++
        }
    }

    private fun showDeleteConfirmation() {
        handler.removeCallbacks(progressBarRunnable) 
        
        AlertDialog.Builder(this)
            .setTitle("Delete Story")
            .setMessage("Are you sure you want to delete this story?")
            .setPositiveButton("Delete") { _, _ ->
                if (counter > 0 && counter <= stories.size) {
                    deleteStory(stories[counter - 1])
                } else {
                    handler.postDelayed(progressBarRunnable, progressInterval) 
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                handler.postDelayed(progressBarRunnable, progressInterval) 
            }
            .setOnCancelListener {
                handler.postDelayed(progressBarRunnable, progressInterval) 
            }
            .show()
    }

    private fun safeFinish() {
        handler.removeCallbacks(progressBarRunnable)
        handler.removeCallbacks(storyStepRunnable)
        binding.vvStory.stopPlayback()
        stopStoryMusic()
        finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    private fun loadStories(userId: String) {
        val yesterday = Calendar.getInstance()
        yesterday.add(Calendar.DAY_OF_YEAR, -1)
        val timestamp24hAgo = yesterday.time.time

        firestore.collection("stories")
            .whereEqualTo("userId", userId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    if (isDestroyed || isFinishing) return@addSnapshotListener
                    android.util.Log.e("StoryViewActivity", "Error loading stories: ${e.message}")
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    safeFinish()
                    return@addSnapshotListener
                }
                
                val allUserStories = mutableListOf<Story>()
                snapshot?.documents?.forEach { doc ->
                    try {
                        val story = doc.toObject(Story::class.java)?.copy(id = doc.id)
                        if (story != null) allUserStories.add(story)
                    } catch (err: Exception) {
                        android.util.Log.e("StoryViewActivity", "Mapping failed for doc ${doc.id}")
                    }
                }

                val filteredStories = allUserStories.filter {
                    val timeMillis = when(val ts = it.timestamp) {
                        is Timestamp -> ts.toDate().time
                        is Long -> ts
                        else -> 0L
                    }
                    timeMillis >= timestamp24hAgo
                }.sortedBy { 
                    when(val ts = it.timestamp) {
                        is Timestamp -> ts.toDate().time
                        is Long -> ts
                        else -> 0L
                    }
                }

                if (filteredStories.isEmpty()) {
                    if (!isDestroyed && !isFinishing) safeFinish()
                } else {
                    val wasEmpty = stories.isEmpty()
                    stories = filteredStories.toMutableList()
                    if (wasEmpty) {
                        setupProgressBars()
                        handler.post(storyStepRunnable)
                    } else if (counter > 0 && counter <= stories.size) {
                        showStory(counter - 1)
                    }
                }
            }
    }

    private fun setupProgressBars() {
        binding.progressContainer.removeAllViews()
        for (i in stories.indices) {
            val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
            val params = android.widget.LinearLayout.LayoutParams(0, 8, 1f)
            params.setMargins(4, 0, 4, 0)
            progressBar.layoutParams = params
            progressBar.max = 100
            progressBar.progress = 0
            progressBar.progressDrawable = ContextCompat.getDrawable(this, android.R.drawable.progress_horizontal)
            binding.progressContainer.addView(progressBar)
        }
    }

    private fun showStory(index: Int) {
        if (index >= stories.size) return
        val story = stories[index]
        
        recordView(story)

        binding.tvUserName.text = story.displayName
        
        if (!story.profileImageUrl.isNullOrEmpty()) {
            binding.ivUserImage.load(story.profileImageUrl) {
                placeholder(R.drawable.ic_launcher_foreground)
                transformations(coil.transform.CircleCropTransformation())
            }
        }
        
        val isOwner = story.userId == auth.currentUser?.uid
        binding.btnDeleteStory.visibility = if (isOwner) View.VISIBLE else View.GONE
        binding.btnHighlight.visibility = if (isOwner) View.VISIBLE else View.GONE

        // Show/hide reaction bar based on ownership
        binding.reactionBar.visibility = if (isOwner) View.GONE else View.VISIBLE

        // Load current user's reaction to highlight
        if (!isOwner) {
            loadUserReaction(story)
        }
        
        if (story.mediaType == "video") {
            binding.ivStory.visibility = View.GONE
            binding.vvStory.visibility = View.VISIBLE
            playVideo(story.mediaUrl)
            storyDuration = 15000L 
        } else {
            binding.vvStory.visibility = View.GONE
            binding.ivStory.visibility = View.VISIBLE
            loadImage(story.mediaUrl)
            storyDuration = 6000L
        }

        // Handle Story Music
        stopStoryMusic()
        if (!story.musicUrl.isNullOrEmpty()) {
            binding.musicContainer.visibility = View.VISIBLE
            binding.tvMusicTitle.text = story.musicTitle ?: "Music"
            playStoryMusic(story.musicUrl!!)
        } else {
            binding.musicContainer.visibility = View.GONE
        }

        if (isOwner) {
            binding.viewCountContainer.visibility = View.VISIBLE
            binding.tvViewCount.text = "${story.viewCount} viewers"
            binding.viewCountContainer.setOnClickListener {
                showViewersDialog(story)
            }
        } else {
            binding.viewCountContainer.visibility = View.GONE
        }
        
        for (i in 0 until binding.progressContainer.childCount) {
            val pb = binding.progressContainer.getChildAt(i) as ProgressBar
            pb.progress = if (i < index) 100 else 0
        }
        
        startTime = System.currentTimeMillis()
        handler.removeCallbacks(progressBarRunnable)
        handler.postDelayed(progressBarRunnable, progressInterval)
    }

    // ---- Story Music ----
    private fun playStoryMusic(base64Audio: String) {
        try {
            val clean = if (base64Audio.contains(",")) base64Audio.substringAfter(",") else base64Audio
            val bytes = Base64.decode(clean, Base64.DEFAULT)
            val tmp = File.createTempFile("story_music", ".mp3", cacheDir)
            FileOutputStream(tmp).use { it.write(bytes) }
            storyMediaPlayer = MediaPlayer().apply {
                setDataSource(tmp.absolutePath)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            android.util.Log.e("StoryView", "Music playback error: ${e.message}")
        }
    }

    private fun stopStoryMusic() {
        try {
            storyMediaPlayer?.stop()
            storyMediaPlayer?.release()
        } catch (_: Exception) {}
        storyMediaPlayer = null
    }

    // ---- Story Reactions ----
    private fun sendReaction(story: Story, emoji: String) {
        val uid = auth.currentUser?.uid ?: return
        val reactionData = hashMapOf(
            "userId" to uid,
            "emoji" to emoji,
            "timestamp" to System.currentTimeMillis()
        )
        
        // Show immediate floating animation for instant feedback
        showFloatingReactionEmoji(emoji)
        
        firestore.collection("stories").document(story.id)
            .collection("reactions").document(uid)
            .set(reactionData)
            .addOnSuccessListener {
                loadUserReaction(story)
            }
    }

    private fun showFloatingReactionEmoji(emoji: String) {
        val container = binding.root
        val textView = android.widget.TextView(this).apply {
            text = emoji
            textSize = 36f
        }
        container.addView(textView)
        
        val density = resources.displayMetrics.density
        val startX = container.width / 2f - (18 * density)
        val startY = container.height - (120 * density)
        
        textView.x = startX
        textView.y = startY
        
        val random = java.util.Random()
        val driftX = (random.nextFloat() - 0.5f) * 150f * density
        val driftY = - (300f + random.nextFloat() * 200f) * density
        
        textView.animate()
            .translationXBy(driftX)
            .translationYBy(driftY)
            .alpha(0f)
            .scaleX(1.8f)
            .scaleY(1.8f)
            .setDuration(1500)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction {
                container.removeView(textView)
            }
            .start()
    }

    private fun loadUserReaction(story: Story) {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("stories").document(story.id)
            .collection("reactions").document(uid)
            .get()
            .addOnSuccessListener { doc ->
                val emoji = doc.getString("emoji") ?: return@addOnSuccessListener
                // Highlight the matching button
                val buttons = listOf(
                    binding.btnReactHeart to "❤️",
                    binding.btnReactLaugh to "😂",
                    binding.btnReactWow to "😮",
                    binding.btnReactSad to "😢",
                    binding.btnReactLove to "😍",
                    binding.btnReactFire to "🔥"
                )
                buttons.forEach { (btn, e) ->
                    btn.alpha = if (e == emoji) 1.0f else 0.4f
                }
            }
    }

    private fun playVideo(videoUrl: String) {
        try {
            val uri = Uri.parse(videoUrl)
            binding.vvStory.setVideoURI(uri)
            binding.vvStory.setOnPreparedListener { mp ->
                mp.isLooping = false
                binding.vvStory.start()
            }
            binding.vvStory.setOnErrorListener { _, _, _ ->
                nextStory()
                true
            }
        } catch (e: Exception) {
            nextStory()
        }
    }

    private fun showViewersDialog(story: Story) {
        handler.removeCallbacks(progressBarRunnable)
        binding.vvStory.pause()
        
        val bottomSheet = StoryViewersBottomSheet(story)
        bottomSheet.show(supportFragmentManager, StoryViewersBottomSheet.TAG)
        
        // Handle dismissal to resume story
        supportFragmentManager.registerFragmentLifecycleCallbacks(object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentDestroyed(fm: androidx.fragment.app.FragmentManager, f: androidx.fragment.app.Fragment) {
                super.onFragmentDestroyed(fm, f)
                if (f is StoryViewersBottomSheet) {
                    resumeStory()
                    supportFragmentManager.unregisterFragmentLifecycleCallbacks(this)
                }
            }
        }, false)
    }

    private fun resumeStory() {
        if (stories.isNotEmpty() && counter > 0 && stories[counter-1].mediaType == "video") {
            binding.vvStory.start()
        }
        // Adjust startTime to account for the pause
        // For simplicity, let's just restart from where it was
        // (This might jump slightly if we don't track pauseTime, but better than being stuck)
        startTime = System.currentTimeMillis() - (binding.progressContainer.getChildAt(counter-1) as ProgressBar).progress * storyDuration / 100
        handler.postDelayed(progressBarRunnable, progressInterval)
    }

    private fun loadImage(photoUrl: String) {
        if (photoUrl.startsWith("data:image") || photoUrl.length > 500) {
            try {
                val cleanBase64 = if (photoUrl.contains(",")) photoUrl.substringAfter(",") else photoUrl
                val decodedString = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                binding.ivStory.setImageBitmap(bitmap)
            } catch (e: Exception) {
                binding.ivStory.setImageResource(android.R.drawable.ic_menu_report_image)
            }
        } else {
            binding.ivStory.load(photoUrl) { 
                crossfade(true)
                placeholder(android.R.color.black)
            }
        }
    }

    private fun addToHighlight(story: Story) {
        val currentUid = auth.currentUser?.uid ?: return
        val highlightData = hashMapOf(
            "userId" to currentUid,
            "storyId" to story.id,
            "mediaUrl" to story.mediaUrl,
            "mediaType" to story.mediaType,
            "timestamp" to (story.timestamp ?: Timestamp.now()),
            "displayName" to story.displayName
        )

        firestore.collection("highlights").add(highlightData)
            .addOnSuccessListener {
                Toast.makeText(this, "Added to Highlights!", Toast.LENGTH_SHORT).show()
                binding.btnHighlight.setImageResource(android.R.drawable.btn_star_big_on)
            }
    }

    private fun deleteStory(story: Story) {
        firestore.collection("stories").document(story.id).delete()
            .addOnSuccessListener {
                Toast.makeText(this, "Story deleted", Toast.LENGTH_SHORT).show()
                stories.remove(story)
                if (stories.isEmpty()) safeFinish() else nextStory()
            }
    }

    private fun recordView(story: Story) {
        val currentUid = auth.currentUser?.uid ?: return
        if (viewedStories.contains(story.id)) return 

        viewedStories.add(story.id)
        val storyRef = firestore.collection("stories").document(story.id)

        if (story.userId == currentUid) {
            // Owner viewing their own story - count once per session
            storyRef.update("ownViewCount", FieldValue.increment(1))
            return
        }
        
        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(storyRef)
            if (!snapshot.exists()) return@runTransaction null

            val freshStory = snapshot.toObject(Story::class.java) ?: return@runTransaction null
            val alreadyViewed = freshStory.viewers.any { it.userId == currentUid }
            
            if (!alreadyViewed) {
                transaction.update(storyRef, "viewCount", freshStory.viewCount + 1)
            }
            transaction.update(storyRef, "totalViews", freshStory.totalViews + 1)
            null
        }.addOnSuccessListener {
            firestore.collection("users").document(currentUid).get().addOnSuccessListener { userDoc ->
                val name = userDoc.getString("name") ?: "User"
                val profileUrl = userDoc.getString("profileImageUrl")
                val info = StoryViewerInfo(currentUid, name, System.currentTimeMillis().toString(), 1)
                
                // Add or update viewer info
                firestore.runTransaction { transaction ->
                    val snapshot = transaction.get(storyRef)
                    val freshStory = snapshot.toObject(Story::class.java) ?: return@runTransaction null
                    val existingIndex = freshStory.viewers.indexOfFirst { it.userId == currentUid }
                    
                    if (existingIndex == -1) {
                        transaction.update(storyRef, "viewers", FieldValue.arrayUnion(info))
                    } else {
                        val updatedViewers = freshStory.viewers.toMutableList()
                        updatedViewers[existingIndex] = updatedViewers[existingIndex].copy(
                            viewCount = updatedViewers[existingIndex].viewCount + 1,
                            lastViewed = System.currentTimeMillis().toString()
                        )
                        transaction.update(storyRef, "viewers", updatedViewers)
                    }
                    null
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopStoryMusic()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(progressBarRunnable)
        handler.removeCallbacks(storyStepRunnable)
        binding.vvStory.stopPlayback()
        stopStoryMusic()
    }
}
