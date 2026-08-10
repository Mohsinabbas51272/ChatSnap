package com.example.chatsnap

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.chatsnap.databinding.DialogAuraCommentsBinding
import com.example.chatsnap.databinding.ItemAuraCommentBinding
import com.example.chatsnap.models.AuraComment
import com.example.chatsnap.utils.AuraFeedRepository
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AuraCommentsBottomSheet(
    private val videoId: String,
    private val onCommentCountUpdated: (Long) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: DialogAuraCommentsBinding? = null
    private val binding get() = _binding!!

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val currentUid = auth.currentUser?.uid ?: ""

    private var commentsList = mutableListOf<AuraComment>()
    private lateinit var adapter: CommentsAdapter

    private var currentUserName = "Someone"
    private var currentUserPhoto = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogAuraCommentsBinding.inflate(inflater, container, false)
        return binding.root
    }

    @Suppress("DEPRECATION")
    override fun onStart() {
        super.onStart()
        dialog?.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let { sheet ->
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet)
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        loadCurrentUserProfile()
        loadComments()

        binding.btnCloseComments.setOnClickListener { dismiss() }
        binding.btnSendComment.setOnClickListener { postComment() }

        binding.etCommentInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && commentsList.isNotEmpty()) {
                binding.rvComments.postDelayed({
                    if (commentsList.isNotEmpty()) {
                        binding.rvComments.scrollToPosition(commentsList.size - 1)
                    }
                }, 150)
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = CommentsAdapter(commentsList)
        binding.rvComments.layoutManager = LinearLayoutManager(requireContext())
        binding.rvComments.adapter = adapter
    }

    private fun loadCurrentUserProfile() {
        if (currentUid.isEmpty()) return
        firestore.collection("users").document(currentUid).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    currentUserName = doc.getString("name") ?: "Someone"
                    currentUserPhoto = doc.getString("profileImageUrl") ?: ""
                    
                    // Load current user avatar into bottom comment input bar
                    if (currentUserPhoto.isNotEmpty()) {
                        if (currentUserPhoto.startsWith("data:image")) {
                            try {
                                val cleanBase64 = currentUserPhoto.substringAfter(",")
                                val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                                val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                                binding.ivCommentUserAvatar.setImageBitmap(bitmap)
                            } catch (e: Exception) {
                                binding.ivCommentUserAvatar.setImageResource(R.drawable.ic_launcher_foreground)
                            }
                        } else {
                            binding.ivCommentUserAvatar.load(currentUserPhoto) {
                                placeholder(R.drawable.ic_launcher_foreground)
                            }
                        }
                    } else {
                        binding.ivCommentUserAvatar.setImageResource(R.drawable.ic_launcher_foreground)
                    }
                }
            }
    }

    private fun loadComments() {
        lifecycleScope.launch {
            val list = AuraFeedRepository.getComments(videoId)
            commentsList.clear()
            commentsList.addAll(list)
            adapter.notifyDataSetChanged()
            updateTitleCount()
        }
    }

    private fun postComment() {
        val commentText = binding.etCommentInput.text.toString().trim()
        if (commentText.isEmpty()) return

        binding.btnSendComment.isEnabled = false
        lifecycleScope.launch {
            val comment = AuraFeedRepository.addComment(
                videoId,
                commentText,
                currentUid,
                currentUserName,
                currentUserPhoto
            )

            if (comment != null) {
                commentsList.add(comment)
                adapter.notifyItemInserted(commentsList.size - 1)
                binding.rvComments.scrollToPosition(commentsList.size - 1)
                binding.etCommentInput.setText("")
                
                val newCount = commentsList.size.toLong()
                onCommentCountUpdated(newCount)
                updateTitleCount()
            } else {
                Toast.makeText(context, "Comment failed to post", Toast.LENGTH_SHORT).show()
            }
            binding.btnSendComment.isEnabled = true
        }
    }

    private fun updateTitleCount() {
        binding.tvCommentsTitle.text = "Comments (${commentsList.size})"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // Inner Adapter for comments recyclerview
    inner class CommentsAdapter(private val list: List<AuraComment>) : RecyclerView.Adapter<CommentsAdapter.CommentViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
            val itemBinding = ItemAuraCommentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return CommentViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
            holder.bind(list[position])
        }

        override fun getItemCount(): Int = list.size

        inner class CommentViewHolder(private val itemBinding: ItemAuraCommentBinding) : RecyclerView.ViewHolder(itemBinding.root) {
            fun bind(comment: AuraComment) {
                itemBinding.tvCommentUsername.text = comment.username
                itemBinding.tvCommentBody.text = comment.text
                
                // Formatted Time
                val formatter = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
                itemBinding.tvCommentTime.text = formatter.format(Date(comment.timestamp))

                // Avatar
                if (comment.userPhotoUrl.isNotEmpty()) {
                    if (comment.userPhotoUrl.startsWith("data:image")) {
                        try {
                            val cleanBase64 = comment.userPhotoUrl.substringAfter(",")
                            val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                            val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                            itemBinding.ivCommentAvatar.setImageBitmap(bitmap)
                        } catch (e: Exception) {
                            itemBinding.ivCommentAvatar.setImageResource(R.drawable.ic_launcher_foreground)
                        }
                    } else {
                        itemBinding.ivCommentAvatar.load(comment.userPhotoUrl) {
                            placeholder(R.drawable.ic_launcher_foreground)
                        }
                    }
                } else {
                    itemBinding.ivCommentAvatar.setImageResource(R.drawable.ic_launcher_foreground)
                }
            }
        }
    }
}
