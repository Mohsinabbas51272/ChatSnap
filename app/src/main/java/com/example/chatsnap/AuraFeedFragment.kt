package com.example.chatsnap

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.example.chatsnap.adapters.AuraFeedAdapter
import com.example.chatsnap.adapters.AuraVideoGridAdapter
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

    private lateinit var gridAdapter: AuraVideoGridAdapter
    private var myProfileVideos = mutableListOf<AuraVideo>()
    private var activeFooterTab = "HOME" // HOME, FRIENDS, INBOX, PROFILE

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
        setupTikTokFooter()
        selectFooterTab("HOME")
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
        binding.feedToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
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
                }
            }
        }
    }

    private fun setupTikTokFooter() {
        binding.btnTabHome.setOnClickListener {
            selectFooterTab("HOME")
        }
        binding.btnTabFriends.setOnClickListener {
            selectFooterTab("FRIENDS")
        }
        binding.btnTabUpload.setOnClickListener {
            videoPickerLauncher.launch("video/*")
        }
        binding.btnTabInbox.setOnClickListener {
            selectFooterTab("INBOX")
        }
        binding.btnTabProfile.setOnClickListener {
            selectFooterTab("PROFILE")
        }
    }

    private fun selectFooterTab(tab: String) {
        activeFooterTab = tab
        updateFooterTabUI(tab)

        when (tab) {
            "HOME" -> {
                binding.viewPagerVideos.visibility = View.VISIBLE
                binding.layoutTopHeader.visibility = View.VISIBLE
                binding.layoutInboxContainer.visibility = View.GONE
                binding.layoutProfileContainer.visibility = View.GONE
                binding.feedToggleGroup.check(R.id.btnForYou)
                currentTab = "FOR_YOU"
                loadFeed(clearExisting = true)
            }
            "FRIENDS" -> {
                binding.viewPagerVideos.visibility = View.VISIBLE
                binding.layoutTopHeader.visibility = View.VISIBLE
                binding.layoutInboxContainer.visibility = View.GONE
                binding.layoutProfileContainer.visibility = View.GONE
                binding.feedToggleGroup.check(R.id.btnFollowing)
                currentTab = "FOLLOWING"
                loadFeed(clearExisting = true)
            }
            "INBOX" -> {
                adapter.releaseAllPlayers()
                binding.viewPagerVideos.visibility = View.GONE
                binding.layoutTopHeader.visibility = View.GONE
                binding.layoutInboxContainer.visibility = View.VISIBLE
                binding.layoutProfileContainer.visibility = View.GONE
                binding.layoutEmptyState.visibility = View.GONE
            }
            "PROFILE" -> {
                adapter.releaseAllPlayers()
                binding.viewPagerVideos.visibility = View.GONE
                binding.layoutTopHeader.visibility = View.GONE
                binding.layoutInboxContainer.visibility = View.GONE
                binding.layoutProfileContainer.visibility = View.VISIBLE
                binding.layoutEmptyState.visibility = View.GONE
                loadUserProfileAndVideos()
            }
        }
    }

    private fun updateFooterTabUI(tab: String) {
        val white = android.graphics.Color.WHITE
        val gray = android.graphics.Color.parseColor("#A0A0A0")

        binding.ivTabHome.setColorFilter(if (tab == "HOME") white else gray)
        binding.tvTabHome.setTextColor(if (tab == "HOME") white else gray)

        binding.ivTabFriends.setColorFilter(if (tab == "FRIENDS") white else gray)
        binding.tvTabFriends.setTextColor(if (tab == "FRIENDS") white else gray)

        binding.ivTabInbox.setColorFilter(if (tab == "INBOX") white else gray)
        binding.tvTabInbox.setTextColor(if (tab == "INBOX") white else gray)

        binding.ivTabProfile.setColorFilter(if (tab == "PROFILE") white else gray)
        binding.tvTabProfile.setTextColor(if (tab == "PROFILE") white else gray)
    }

    private fun loadUserProfileAndVideos() {
        val uid = auth.currentUser?.uid ?: return

        firestore.collection("users").document(uid).get().addOnSuccessListener { doc ->
            if (_binding != null && doc.exists()) {
                val name = doc.getString("name") ?: "User"
                val photo = doc.getString("profileImageUrl")
                binding.tvProfileTitleName.text = name
                binding.tvProfileDisplayName.text = name
                binding.tvProfileHandle.text = "@${name.lowercase().replace(" ", "")}"

                if (!photo.isNullOrEmpty()) {
                    if (photo.startsWith("data:image") || photo.length > 1000) {
                        try {
                            val cleanBase64 = photo.substringAfter(",")
                            val decodedString = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                            val decodedByte = android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                            binding.ivProfileAvatar.setImageBitmap(decodedByte)
                        } catch (_: Exception) {
                            binding.ivProfileAvatar.setImageResource(R.drawable.ic_person)
                        }
                    } else {
                        binding.ivProfileAvatar.load(photo) {
                            placeholder(R.drawable.ic_person)
                        }
                    }
                } else {
                    binding.ivProfileAvatar.setImageResource(R.drawable.ic_person)
                }

                @Suppress("UNCHECKED_CAST")
                val friendsList = doc.get("friends") as? List<String> ?: emptyList()
                binding.tvFollowingCount.text = friendsList.size.toString()
            }
        }

        // Setup Grid RecyclerView
        gridAdapter = AuraVideoGridAdapter(myProfileVideos) { _, index ->
            currentTab = "MY_VIDEOS"
            binding.viewPagerVideos.visibility = View.VISIBLE
            binding.layoutTopHeader.visibility = View.VISIBLE
            binding.layoutProfileContainer.visibility = View.GONE
            videosList.clear()
            videosList.addAll(myProfileVideos)
            adapter.notifyDataSetChanged()
            binding.viewPagerVideos.post {
                binding.viewPagerVideos.currentItem = index
                adapter.playVideoAt(index, binding.viewPagerVideos)
            }
        }
        binding.rvMyVideosGrid.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.rvMyVideosGrid.adapter = gridAdapter

        lifecycleScope.launch {
            val userVideos = AuraFeedRepository.getMyVideos(uid)
            myProfileVideos.clear()
            myProfileVideos.addAll(userVideos)
            gridAdapter.updateData(myProfileVideos)

            val totalLikes = userVideos.sumOf { it.likeCount }
            binding.tvLikesCount.text = formatCount(totalLikes)
            binding.tvFollowersCount.text = formatCount((userVideos.size * 3).toLong())
        }
    }

    private fun formatCount(count: Long): String {
        return when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> count.toString()
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
                    isLastPage = true
                }
                
                if (list.size < 10) {
                    isLastPage = true
                }

                videosList.addAll(list)
                adapter.notifyDataSetChanged()
                
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

                videoRef.set(newVideo).await()

                withContext(Dispatchers.Main) {
                    binding.cardUploadProgress.visibility = View.GONE
                    Toast.makeText(context, "Video published successfully! 🎉", Toast.LENGTH_SHORT).show()
                    selectFooterTab("PROFILE")
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
        if (videosList.isNotEmpty() && activeFooterTab == "HOME") {
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
