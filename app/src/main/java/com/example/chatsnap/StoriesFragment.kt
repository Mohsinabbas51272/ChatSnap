package com.example.chatsnap

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatsnap.adapters.HighlightAdapter
import com.example.chatsnap.adapters.GroupedStory
import com.example.chatsnap.adapters.StoryGroupAdapter
import com.example.chatsnap.databinding.FragmentStoriesBinding
import com.example.chatsnap.models.Story
import com.example.chatsnap.utils.SearchableFragment
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Calendar

class StoriesFragment : Fragment(), SearchableFragment {
    private var _binding: FragmentStoriesBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    
    private lateinit var storyGroupAdapter: StoryGroupAdapter
    private lateinit var discoverAdapter: HighlightAdapter
    private val storyGroups = mutableListOf<GroupedStory>()
    private val discoverItems = mutableListOf<com.example.chatsnap.models.Highlight>()
    private var currentUserPhotoUrl: String? = null
    private var currentUserDisplayName: String? = null
    private var lastSnapshot: com.google.firebase.firestore.QuerySnapshot? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStoriesBinding.inflate(inflater, container, false)
        return binding.root
    }

    private val mediaPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleMediaSelection(it) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        fetchCurrentUserProfile()
        setupRecyclerView()
        listenForStories()
        loadDiscoverContent()
    }

    private fun fetchCurrentUserProfile() {
        val currentUid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(currentUid).addSnapshotListener { document, error ->
            if (error != null) {
                android.util.Log.e("StoriesFragment", "Error fetching user profile: ${error.message}", error)
                return@addSnapshotListener
            }
            if (document != null && document.exists()) {
                currentUserPhotoUrl = document.getString("profileImageUrl")
                currentUserDisplayName = document.getString("name")
                lastSnapshot?.let { processStorySnapshot(it) }
            }
        }
    }

    private fun listenForStories() {
        // Fetch last 100 stories to ensure we catch recent ones without requiring a complex index
        firestore.collection("stories")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("StoriesFragment", "Error fetching stories: ${error.message}", error)
                    // If index is missing, fallback to a simpler query
                    if (error.message?.contains("index") == true) {
                        firestore.collection("stories").limit(50).addSnapshotListener { s, _ ->
                            if (s != null) processStorySnapshot(s)
                        }
                    }
                    return@addSnapshotListener
                }
                if (snapshot != null) processStorySnapshot(snapshot)
            }
    }

    private fun loadDiscoverContent() {
        val twentyFourHoursAgo = Calendar.getInstance().apply { add(Calendar.HOUR, -24) }.time.time
        
        firestore.collection("highlights")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(50)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot != null) {
                    val allHighlights = snapshot.toObjects(com.example.chatsnap.models.Highlight::class.java)
                    val filtered = allHighlights.filter { highlight ->
                        val time = highlight.timestamp.toDate().time
                        time >= twentyFourHoursAgo
                    }
                    discoverItems.clear()
                    discoverItems.addAll(filtered)
                    discoverAdapter.updateData(discoverItems)
                }
            }
            .addOnFailureListener { e ->
                // Fallback if index is missing
                firestore.collection("highlights").limit(20).get().addOnSuccessListener { s ->
                    if (s != null) {
                        val allHighlights = s.toObjects(com.example.chatsnap.models.Highlight::class.java)
                        val filtered = allHighlights.filter { highlight ->
                            val time = highlight.timestamp.toDate().time
                            time >= twentyFourHoursAgo
                        }
                        discoverItems.clear()
                        discoverItems.addAll(filtered)
                        discoverAdapter.updateData(discoverItems)
                    }
                }
            }
    }

    private fun processStorySnapshot(snapshot: com.google.firebase.firestore.QuerySnapshot) {
        lastSnapshot = snapshot
        val twentyFourHoursAgo = Calendar.getInstance().apply { add(Calendar.HOUR, -24) }.time.time
        
        val allStories = mutableListOf<Story>()
        for (doc in snapshot.documents) {
            try {
                val story = doc.toObject(Story::class.java)?.copy(id = doc.id)
                if (story != null) {
                    // Manual time check for robustness (handles both Timestamp and Long)
                    val storyTime = when (val ts = story.timestamp) {
                        is Timestamp -> ts.toDate().time
                        is Long -> ts
                        else -> 0L
                    }
                    if (storyTime >= twentyFourHoursAgo) {
                        allStories.add(story)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("StoriesFragment", "Error mapping story: ${doc.id}", e)
            }
        }

        val currentUid = auth.currentUser?.uid ?: ""
        val grouped = allStories.groupBy { it.userId }.map { (userId, stories) ->
            val firstStory = stories.first()
            GroupedStory(
                userId = userId,
                displayName = if (userId == currentUid) "Your Story" else firstStory.displayName,
                userPhoto = firstStory.profileImageUrl,
                stories = stories.sortedBy { 
                    when (val ts = it.timestamp) {
                        is Timestamp -> ts.toDate().time
                        is Long -> ts
                        else -> 0L
                    }
                },
                hasUnread = userId != currentUid
            )
        }.sortedWith(compareByDescending<GroupedStory> { it.userId == currentUid }.thenByDescending { it.hasUnread })

        val hasMyStory = grouped.any { it.userId == currentUid }
        val finalGroups = if (!hasMyStory && currentUid.isNotEmpty()) {
            val myGroup = GroupedStory(
                userId = currentUid,
                displayName = currentUserDisplayName ?: "My Story",
                userPhoto = currentUserPhotoUrl,
                stories = emptyList(),
                hasUnread = false
            )
            listOf(myGroup) + grouped
        } else {
            grouped
        }

        storyGroups.clear()
        storyGroups.addAll(finalGroups)
        if (_binding != null) {
            storyGroupAdapter.updateData(storyGroups)
        }
    }

    private fun setupRecyclerView() {
        // Friends Stories Tray
        storyGroupAdapter = StoryGroupAdapter(
            storyGroups,
            onAddStoryClick = {
                showMediaPickerOptions()
            },
            onGroupClick = { group ->
                val intent = Intent(requireContext(), StoryViewActivity::class.java)
                intent.putExtra("USER_ID", group.userId)
                startActivity(intent)
            }
        )
        binding.rvStories.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvStories.adapter = storyGroupAdapter

        // Discover Section
        discoverAdapter = HighlightAdapter(discoverItems) { item ->
            val intent = Intent(requireContext(), StoryViewActivity::class.java)
            intent.putExtra("USER_ID", item.userId)
            startActivity(intent)
        }
        binding.rvDiscover.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvDiscover.adapter = discoverAdapter
    }

    fun showMediaPickerOptions() {
        val options = arrayOf("Image", "Video")
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Post a Story")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> mediaPickerLauncher.launch("image/*")
                    1 -> mediaPickerLauncher.launch("video/*")
                }
            }
            .show()
    }

    private fun handleMediaSelection(uri: Uri) {
        val mimeType = requireContext().contentResolver.getType(uri)
        val mediaType = if (mimeType?.startsWith("video") == true) "video" else "image"
        uploadStory(uri, mediaType)
    }

    private fun uploadStory(mediaUri: Uri, mediaType: String) {
        val context = context ?: return
        
        if (mediaType == "video") {
            Toast.makeText(context, "Videos require Firebase Storage (Paid Plan).", Toast.LENGTH_LONG).show()
            return
        }

        binding.storyProgressBar.visibility = View.VISIBLE
        Toast.makeText(context, "Processing story...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val inputStream = context.contentResolver.openInputStream(mediaUri)
                val bytes = inputStream?.readBytes() ?: throw Exception("Read error")
                
                if (mediaType == "image") {
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val maxDim = 1080
                    val width = bitmap.width
                    val height = bitmap.height
                    val newBitmap = if (width > maxDim || height > maxDim) {
                        val ratio = width.toFloat() / height.toFloat()
                        val newW = if (ratio > 1) maxDim else (maxDim * ratio).toInt()
                        val newH = if (ratio > 1) (maxDim / ratio).toInt() else maxDim
                        android.graphics.Bitmap.createScaledBitmap(bitmap, newW, newH, true)
                    } else {
                        bitmap
                    }

                    val out = java.io.ByteArrayOutputStream()
                    newBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 35, out)
                    val base64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.DEFAULT)
                    val finalData = "data:image/jpeg;base64,$base64"
                    
                    if (finalData.length > 950000) {
                        if (isAdded) {
                            binding.storyProgressBar.visibility = View.GONE
                            Toast.makeText(context, "Story image too large.", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                    
                    saveStoryToFirestore(finalData, "image")
                }
            } catch (e: Exception) {
                if (isAdded) {
                    binding.storyProgressBar.visibility = View.GONE
                    Toast.makeText(context, "Upload Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveStoryToFirestore(mediaUrl: String, mediaType: String) {
        val currentUid = auth.currentUser?.uid ?: return
        
        firestore.collection("users").document(currentUid).get().addOnSuccessListener { userDoc ->
            val displayName = userDoc.getString("name") ?: "User"
            val profilePic = userDoc.getString("profileImageUrl") ?: ""
            
            val storyData = Story(
                id = "", 
                userId = currentUid,
                displayName = displayName,
                profileImageUrl = profilePic,
                mediaUrl = mediaUrl,
                mediaType = mediaType,
                timestamp = Timestamp.now()
            )

            firestore.collection("stories").add(storyData)
                .addOnSuccessListener { docRef ->
                    docRef.update("id", docRef.id)
                    if (isAdded) {
                        binding.storyProgressBar.visibility = View.GONE
                        Toast.makeText(context, "Story uploaded!", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener {
                    if (isAdded) {
                        binding.storyProgressBar.visibility = View.GONE
                        Toast.makeText(context, "Save failed: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    override fun onSearch(query: String) {
        val lowerCaseQuery = query.lowercase()
        val filtered = storyGroups.filter {
            it.displayName.lowercase().contains(lowerCaseQuery)
        }
        storyGroupAdapter.updateData(filtered)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
