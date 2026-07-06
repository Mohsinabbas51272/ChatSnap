package com.example.chatsnap.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.chatsnap.AuraCommentsBottomSheet
import com.example.chatsnap.R
import com.example.chatsnap.databinding.ItemAuraVideoBinding
import com.example.chatsnap.models.AuraVideo
import com.example.chatsnap.utils.AuraFeedRepository
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuraFeedAdapter(
    private var videos: MutableList<AuraVideo>,
    private val context: Context,
    private val scope: CoroutineScope,
    private val onVideoDeletedListener: (String) -> Unit
) : RecyclerView.Adapter<AuraFeedAdapter.VideoViewHolder>() {

    private val auth = FirebaseAuth.getInstance()
    private val currentUid = auth.currentUser?.uid ?: ""
    private var activeViewHolder: VideoViewHolder? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemAuraVideoBinding.inflate(LayoutInflater.from(context), parent, false)
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(videos[position])
    }

    override fun onViewAttachedToWindow(holder: VideoViewHolder) {
        super.onViewAttachedToWindow(holder)
        // If this is the activeViewHolder, we will play it
    }

    override fun onViewDetachedFromWindow(holder: VideoViewHolder) {
        super.onViewDetachedFromWindow(holder)
        holder.releasePlayer()
    }

    override fun getItemCount(): Int = videos.size

    @SuppressLint("NotifyDataSetChanged")
    fun updateData(newVideos: List<AuraVideo>) {
        this.videos = newVideos.toMutableList()
        notifyDataSetChanged()
    }

    fun playVideoAt(position: Int, viewPager: androidx.viewpager2.widget.ViewPager2) {
        val recyclerView = viewPager.getChildAt(0) as? RecyclerView ?: return
        val holder = recyclerView.findViewHolderForAdapterPosition(position) as? VideoViewHolder
        activeViewHolder?.releasePlayer()
        activeViewHolder = holder
        holder?.playVideo()
    }

    fun releaseAllPlayers() {
        activeViewHolder?.releasePlayer()
        activeViewHolder = null
    }

    inner class VideoViewHolder(private val binding: ItemAuraVideoBinding) : RecyclerView.ViewHolder(binding.root) {
        private var player: ExoPlayer? = null
        private var auraVideo: AuraVideo? = null
        private var isMuted = false
        private var playProgressJob: Job? = null
        private var startTimeMs = 0L

        fun bind(video: AuraVideo) {
            this.auraVideo = video
            startTimeMs = 0L

            // Set content details
            binding.tvCreatorName.text = "@${video.creatorUsername}"
            binding.tvCaption.text = video.caption
            binding.tvMusicName.text = video.musicName.ifEmpty { "Original Sound - ${video.creatorUsername}" }
            binding.tvMusicName.isSelected = true // Trigger marquee effect

            // Interaction Counts
            binding.tvLikeCount.text = formatCount(video.likeCount)
            binding.tvCommentCount.text = formatCount(video.commentCount)
            binding.tvShareCount.text = formatCount(video.shareCount)
            binding.tvSaveCount.text = formatCount(video.saveCount)

            // Setup Profile Photo
            if (video.creatorPhotoUrl.isNotEmpty()) {
                if (video.creatorPhotoUrl.startsWith("data:image")) {
                    try {
                        val cleanBase64 = video.creatorPhotoUrl.substringAfter(",")
                        val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                        binding.ivCreatorAvatar.setImageBitmap(bitmap)
                    } catch (e: Exception) {
                        binding.ivCreatorAvatar.setImageResource(R.drawable.ic_launcher_foreground)
                    }
                } else {
                    binding.ivCreatorAvatar.load(video.creatorPhotoUrl) {
                        placeholder(R.drawable.ic_launcher_foreground)
                    }
                }
            } else {
                binding.ivCreatorAvatar.setImageResource(R.drawable.ic_launcher_foreground)
            }

            // Setup Follow Badge
            if (video.creatorUid == currentUid) {
                binding.ivFollowBadge.visibility = View.GONE
                binding.btnOptions.visibility = View.VISIBLE
            } else {
                binding.btnOptions.visibility = View.GONE
                binding.ivFollowBadge.visibility = View.VISIBLE
                scope.launch {
                    val following = AuraFeedRepository.isFollowing(currentUid, video.creatorUid)
                    withContext(Dispatchers.Main) {
                        updateFollowUI(following)
                    }
                }
            }

            // Setup Like & Save state icons
            val isLiked = video.likes.contains(currentUid)
            updateLikeUI(isLiked)

            val isSaved = video.saves.contains(currentUid)
            updateSaveUI(isSaved)

            // Clicks listeners
            binding.ivFollowBadge.setOnClickListener { toggleFollow() }
            binding.btnLike.setOnClickListener { toggleLike() }
            binding.btnComment.setOnClickListener { showComments() }
            binding.btnSave.setOnClickListener { toggleSave() }
            binding.btnShare.setOnClickListener { shareVideo() }
            binding.btnOptions.setOnClickListener { showDeleteDialog() }

            // Gesture Overlay Tap play/pause
            binding.gestureOverlay.setOnClickListener {
                togglePlayback()
            }
        }

        private fun updateFollowUI(isFollowing: Boolean) {
            if (isFollowing) {
                binding.ivFollowBadge.setImageResource(android.R.drawable.checkbox_on_background)
                binding.ivFollowBadge.animate().rotation(360f).setDuration(300).start()
            } else {
                binding.ivFollowBadge.setImageResource(android.R.drawable.ic_input_add)
                binding.ivFollowBadge.rotation = 0f
            }
        }

        private fun updateLikeUI(isLiked: Boolean) {
            if (isLiked) {
                binding.btnLike.setImageResource(android.R.drawable.btn_star_big_on)
                binding.btnLike.imageTintList = android.content.res.ColorStateList.valueOf(
                    context.getColor(android.R.color.holo_red_light)
                )
            } else {
                binding.btnLike.setImageResource(android.R.drawable.btn_star_big_off)
                binding.btnLike.imageTintList = android.content.res.ColorStateList.valueOf(
                    context.getColor(android.R.color.white)
                )
            }
        }

        private fun updateSaveUI(isSaved: Boolean) {
            if (isSaved) {
                binding.btnSave.setImageResource(android.R.drawable.ic_menu_save)
                binding.btnSave.imageTintList = android.content.res.ColorStateList.valueOf(
                    context.getColor(android.R.color.holo_orange_light)
                )
            } else {
                binding.btnSave.setImageResource(android.R.drawable.ic_menu_save)
                binding.btnSave.imageTintList = android.content.res.ColorStateList.valueOf(
                    context.getColor(android.R.color.white)
                )
            }
        }

        private fun toggleFollow() {
            val video = auraVideo ?: return
            scope.launch {
                val isFollowing = AuraFeedRepository.isFollowing(currentUid, video.creatorUid)
                val success = if (isFollowing) {
                    AuraFeedRepository.unfollowUser(currentUid, video.creatorUid)
                } else {
                    AuraFeedRepository.followUser(currentUid, video.creatorUid)
                }

                if (success) {
                    withContext(Dispatchers.Main) {
                        updateFollowUI(!isFollowing)
                        Toast.makeText(context, if (isFollowing) "Unfollowed!" else "Following!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        private fun toggleLike() {
            val video = auraVideo ?: return
            val isLiked = video.likes.contains(currentUid)
            val updatedLikes = video.likes.toMutableList()
            var likeCount = video.likeCount

            if (isLiked) {
                updatedLikes.remove(currentUid)
                likeCount--
            } else {
                updatedLikes.add(currentUid)
                likeCount++
            }

            auraVideo = video.copy(likes = updatedLikes, likeCount = likeCount)
            updateLikeUI(!isLiked)
            binding.tvLikeCount.text = formatCount(likeCount)

            scope.launch {
                if (isLiked) {
                    AuraFeedRepository.unlikeVideo(video.id, currentUid)
                } else {
                    AuraFeedRepository.likeVideo(video.id, currentUid)
                }
            }
        }

        private fun toggleSave() {
            val video = auraVideo ?: return
            val isSaved = video.saves.contains(currentUid)
            val updatedSaves = video.saves.toMutableList()
            var saveCount = video.saveCount

            if (isSaved) {
                updatedSaves.remove(currentUid)
                saveCount--
            } else {
                updatedSaves.add(currentUid)
                saveCount++
            }

            auraVideo = video.copy(saves = updatedSaves, saveCount = saveCount)
            updateSaveUI(!isSaved)
            binding.tvSaveCount.text = formatCount(saveCount)

            scope.launch {
                if (isSaved) {
                    AuraFeedRepository.unsaveVideo(video.id, currentUid)
                } else {
                    AuraFeedRepository.saveVideo(video.id, currentUid)
                }
            }
        }

        private fun showComments() {
            val video = auraVideo ?: return
            val bottomSheet = AuraCommentsBottomSheet(video.id) { count ->
                binding.tvCommentCount.text = formatCount(count)
                auraVideo = auraVideo?.copy(commentCount = count)
            }
            // Temporarily pause play
            player?.pause()
            
            val activity = context as? androidx.fragment.app.FragmentActivity ?: return
            bottomSheet.show(activity.supportFragmentManager, "CommentsSheet")
            
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
                object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                    override fun onFragmentDestroyed(fm: androidx.fragment.app.FragmentManager, f: androidx.fragment.app.Fragment) {
                        super.onFragmentDestroyed(fm, f)
                        if (f is AuraCommentsBottomSheet) {
                            player?.play()
                            activity.supportFragmentManager.unregisterFragmentLifecycleCallbacks(this)
                        }
                    }
                }, false
            )
        }

        private fun shareVideo() {
            val video = auraVideo ?: return
            scope.launch {
                AuraFeedRepository.shareVideo(video.id)
                withContext(Dispatchers.Main) {
                    val shareCount = video.shareCount + 1
                    binding.tvShareCount.text = formatCount(shareCount)
                    auraVideo = video.copy(shareCount = shareCount)

                    val intent = Intent().apply {
                        action = Intent.ACTION_SEND
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "Checkout this AuraFeed short video by @${video.creatorUsername}: ${video.caption}")
                    }
                    context.startActivity(Intent.createChooser(intent, "Share video"))
                }
            }
        }

        private fun showDeleteDialog() {
            val video = auraVideo ?: return
            AlertDialog.Builder(context)
                .setTitle("Delete Video")
                .setMessage("Are you sure you want to delete this video?")
                .setPositiveButton("Delete") { _, _ ->
                    scope.launch {
                        val success = AuraFeedRepository.deleteVideo(video.id)
                        if (success) {
                            withContext(Dispatchers.Main) {
                                onVideoDeletedListener(video.id)
                                Toast.makeText(context, "Video deleted", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        fun playVideo() {
            val video = auraVideo ?: return
            if (player != null) {
                player?.play()
                return
            }

            scope.launch(Dispatchers.IO) {
                val uri = AuraFeedRepository.getPlayableUri(video.videoUrl, context)
                withContext(Dispatchers.Main) {
                    setupExoPlayer(uri)
                }
            }
        }

        private fun setupExoPlayer(videoUri: Uri) {
            if (player != null) return

            player = ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                setMediaItem(MediaItem.fromUri(videoUri))
                prepare()
                playWhenReady = true
                volume = if (isMuted) 0f else 1f
                
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            if (startTimeMs == 0L) {
                                startTimeMs = System.currentTimeMillis()
                            }
                            startProgressTracker()
                        }
                    }
                })
            }
            binding.playerView.player = player
        }

        private fun startProgressTracker() {
            playProgressJob?.cancel()
            playProgressJob = scope.launch(Dispatchers.Main) {
                while (player != null) {
                    val current = player?.currentPosition ?: 0
                    val duration = player?.duration ?: 1
                    val pct = (current * 100 / duration).toInt()
                    binding.pbVideo.progress = pct.coerceIn(0, 100)
                    delay(250)
                }
            }
        }

        private fun togglePlayback() {
            val p = player ?: return
            if (p.isPlaying) {
                p.pause()
                animatePlayPauseIcon(true)
            } else {
                p.play()
                animatePlayPauseIcon(false)
            }
        }

        private fun animatePlayPauseIcon(isPaused: Boolean) {
            binding.ivBigPlayPause.setImageResource(
                if (isPaused) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
            binding.ivBigPlayPause.visibility = View.VISIBLE
            binding.ivBigPlayPause.alpha = 1.0f
            
            val fadeOut = AlphaAnimation(1.0f, 0.0f).apply {
                duration = 600
                startOffset = 200
                fillAfter = true
            }
            binding.ivBigPlayPause.startAnimation(fadeOut)
            scope.launch {
                delay(800)
                withContext(Dispatchers.Main) {
                    binding.ivBigPlayPause.visibility = View.GONE
                }
            }
        }

        fun releasePlayer() {
            playProgressJob?.cancel()
            playProgressJob = null

            val p = player
            if (p != null) {
                // Record analytics before releasing
                val durationPlayed = System.currentTimeMillis() - startTimeMs
                val completionPct = if (startTimeMs > 0L) {
                    val videoDuration = p.duration
                    if (videoDuration > 0) {
                        (durationPlayed * 100 / videoDuration).toInt().coerceAtMost(100)
                    } else 0
                } else 0

                val videoId = auraVideo?.id
                if (videoId != null && durationPlayed > 1000) {
                    scope.launch {
                        AuraFeedRepository.recordView(videoId, currentUid, durationPlayed, completionPct)
                    }
                }

                p.stop()
                p.release()
                player = null
            }
            binding.playerView.player = null
            binding.pbVideo.progress = 0
        }

        private fun formatCount(count: Long): String {
            if (count < 1000) return count.toString()
            if (count < 1000000) return String.format("%.1fK", count / 1000.0)
            return String.format("%.1fM", count / 1000000.0)
        }
    }
}
