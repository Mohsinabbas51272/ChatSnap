package com.example.chatsnap

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AlertDialog
import androidx.viewpager2.widget.ViewPager2
import com.example.chatsnap.adapters.AuraFeedAdapter
import com.example.chatsnap.databinding.DialogUploadAuraVideoBinding
import com.example.chatsnap.databinding.FragmentAuraFeedBinding
import com.example.chatsnap.models.AuraVideo
import com.example.chatsnap.utils.AuraFeedRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class AuraFeedFragment : Fragment() {

    private var _binding: FragmentAuraFeedBinding? = null
    private val binding get() = _binding!!

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val currentUid = auth.currentUser?.uid ?: ""

    private var videosList = mutableListOf<AuraVideo>()
    private lateinit var adapter: AuraFeedAdapter
    private var currentTab = "FOR_YOU" // FOR_YOU, FOLLOWING, MY_VIDEOS

    // Paging parameters
    private var lastTimestamp: Long? = null
    private var isLoading = false
    private var isLastPage = false

    private val videoPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleSelectedVideo(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAuraFeedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViewPager()
        setupToggles()
        setupListeners()
        loadFeed(clearExisting = true)
    }

    private fun setupViewPager() {
        adapter = AuraFeedAdapter(
            videosList,
            requireContext(),
            lifecycleScope,
            onVideoDeletedListener = { videoId ->
                val index = videosList.indexOfFirst { it.id == videoId }
                if (index != -1) {
                    videosList.removeAt(index)
                    adapter.notifyItemRemoved(index)
                    checkEmptyState()
                }
            }
        )
        binding.viewPagerVideos.adapter = adapter

        // Sync vertical autoplay
        binding.viewPagerVideos.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                
                // Triggers autoplay for current page and pauses others
                adapter.playVideoAt(position, binding.viewPagerVideos)

                // Paginate when reaching near the end
                if (position == videosList.size - 2 && !isLoading && !isLastPage && currentTab != "MY_VIDEOS") {
                    loadFeed(clearExisting = false)
                }
            }
        })
    }

    private fun setupToggles() {
        binding.feedToggleGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked) {
                adapter.releaseAllPlayers()
                when (checkedId) {
                    R.id.btnForYou -> {
                        currentTab = "FOR_YOU"
                        loadFeed(clearExisting = true)
                    }
                    R.id.btnFollowing -> {
                        currentTab = "FOLLOWING"
                        loadFeed(clearExisting = true)
                    }
                    R.id.btnMyVideos -> {
                        currentTab = "MY_VIDEOS"
                        loadFeed(clearExisting = true)
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        binding.btnUploadVideo.setOnClickListener {
            // Select video from gallery
            videoPickerLauncher.launch("video/*")
        }
    }

    private fun loadFeed(clearExisting: Boolean) {
        if (isLoading) return
        isLoading = true

        if (clearExisting) {
            lastTimestamp = null
            isLastPage = false
            videosList.clear()
            adapter.notifyDataSetChanged()
        }

        lifecycleScope.launch {
            val list = when (currentTab) {
                "FOR_YOU" -> AuraFeedRepository.getForYouFeed(10, lastTimestamp)
                "FOLLOWING" -> AuraFeedRepository.getFollowingFeed(currentUid, 10, lastTimestamp)
                "MY_VIDEOS" -> AuraFeedRepository.getMyVideos(currentUid)
                else -> emptyList()
            }

            if (list.isNotEmpty()) {
                if (currentTab != "MY_VIDEOS") {
                    lastTimestamp = list.last().timestamp
                } else {
                    isLastPage = true // My videos returns all, no pagination needed for now
                }
                
                if (list.size < 10) {
                    isLastPage = true
                }

                videosList.addAll(list)
                adapter.notifyDataSetChanged()
                
                // Play first video if we cleared existing lists
                if (clearExisting && videosList.isNotEmpty()) {
                    binding.viewPagerVideos.post {
                        binding.viewPagerVideos.currentItem = 0
                        adapter.playVideoAt(0, binding.viewPagerVideos)
                    }
                }
            } else {
                isLastPage = true
            }

            checkEmptyState()
            isLoading = false
        }
    }

    private fun checkEmptyState() {
        if (videosList.isEmpty()) {
            binding.layoutEmptyState.visibility = View.VISIBLE
            binding.tvEmptyState.text = when (currentTab) {
                "FOLLOWING" -> "Follow creators to see their videos here!"
                "MY_VIDEOS" -> "You haven't posted any videos yet."
                else -> "No videos available."
            }
        } else {
            binding.layoutEmptyState.visibility = View.GONE
        }
    }

    private fun handleSelectedVideo(uri: Uri) {
        val sizeBytes = AuraFeedRepository.getVideoSize(requireContext(), uri)
        val sizeMb = sizeBytes / (1024.0 * 1024.0)

        // Validate Firestore 1MB document size limit
        // Base64 encoding adds 33% size overhead. To ensure the final document (including metadata)
        // stays under 1MB, the raw video file must be under 730 KB (0.71 MB).
        if (sizeMb > 0.71) {
            Toast.makeText(
                context,
                "Selected video is too large (%.2f MB). Please choose a video smaller than 730 KB (e.g. 3-5 seconds long).".format(sizeMb),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        showUploadDetailsDialog(uri)
    }

    private fun showUploadDetailsDialog(uri: Uri) {
        val dialogViewBinding = DialogUploadAuraVideoBinding.inflate(LayoutInflater.from(requireContext()))
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogViewBinding.root)
            .setCancelable(false)
            .create()

        dialogViewBinding.btnCancelUpload.setOnClickListener { dialog.dismiss() }
        dialogViewBinding.btnConfirmPost.setOnClickListener {
            val caption = dialogViewBinding.etVideoCaption.text.toString().trim()
            val hashtagsInput = dialogViewBinding.etVideoHashtags.text.toString().trim()
            val musicInput = dialogViewBinding.etVideoMusic.text.toString().trim()

            if (caption.isEmpty()) {
                dialogViewBinding.etVideoCaption.error = "Please enter a caption"
                return@setOnClickListener
            }

            dialog.dismiss()
            publishVideo(uri, caption, hashtagsInput, musicInput)
        }

        dialog.show()
    }

    private fun publishVideo(uri: Uri, caption: String, hashtagsInput: String, musicInput: String) {
        binding.cardUploadProgress.visibility = View.VISIBLE
        binding.tvUploadStatus.text = "Compressing & Encoding Video..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val base64Video = AuraFeedRepository.encodeVideoToBase64(requireContext(), uri)

                if (base64Video == null) {
                    withContext(Dispatchers.Main) {
                        binding.cardUploadProgress.visibility = View.GONE
                        Toast.makeText(context, "Failed to process video file", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // Fetch current user profile metadata on IO thread using await
                val userDoc = firestore.collection("users").document(currentUid).get().await()
                val username = userDoc.getString("name") ?: "user"
                val photoUrl = userDoc.getString("profileImageUrl") ?: ""

                val tags = hashtagsInput.split(" ", ",")
                    .map { it.replace("#", "").trim() }
                    .filter { it.isNotEmpty() }

                val videoRef = firestore.collection("aura_videos").document()
                val newVideo = AuraVideo(
                    id = videoRef.id,
                    creatorUid = currentUid,
                    creatorUsername = username,
                    creatorPhotoUrl = photoUrl,
                    videoUrl = base64Video,
                    caption = caption,
                    hashtags = tags,
                    musicName = musicInput.ifEmpty { "Original Sound - $username" },
                    timestamp = System.currentTimeMillis()
                )

                // Save to Firestore, properly awaited
                videoRef.set(newVideo).await()

                withContext(Dispatchers.Main) {
                    binding.cardUploadProgress.visibility = View.GONE
                    Toast.makeText(context, "Video published successfully! 🎉", Toast.LENGTH_SHORT).show()
                    // Switch to My Videos tab to immediately show the upload
                    currentTab = "MY_VIDEOS"
                    binding.feedToggleGroup.check(R.id.btnMyVideos)
                    loadFeed(clearExisting = true)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.cardUploadProgress.visibility = View.GONE
                    Toast.makeText(context, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        adapter.releaseAllPlayers()
    }

    override fun onResume() {
        super.onResume()
        if (videosList.isNotEmpty()) {
            binding.viewPagerVideos.post {
                val current = binding.viewPagerVideos.currentItem
                adapter.playVideoAt(current, binding.viewPagerVideos)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        adapter.releaseAllPlayers()
        _binding = null
    }
}
