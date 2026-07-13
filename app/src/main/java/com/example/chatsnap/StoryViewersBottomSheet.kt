package com.example.chatsnap

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatsnap.adapters.StoryViewersAdapter
import com.example.chatsnap.databinding.LayoutStoryViewersBinding
import com.example.chatsnap.models.Story
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

import com.google.firebase.firestore.FirebaseFirestore

class StoryViewersBottomSheet(private val story: Story) : BottomSheetDialogFragment() {

    private var _binding: LayoutStoryViewersBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutStoryViewersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvOwnViews.text = story.ownViewCount.toString()
        binding.tvTotalViews.text = story.totalViews.toString()
        binding.tvSummary.text = "Total ${story.totalViews} views from ${story.viewers.size} viewers"

        binding.rvViewers.layoutManager = LinearLayoutManager(context)
        val sortedViewers = story.viewers.sortedByDescending { it.viewCount }
        binding.rvViewers.adapter = StoryViewersAdapter(sortedViewers)

        loadReactions()
    }

    private fun loadReactions() {
        if (story.id.isEmpty()) return
        val db = FirebaseFirestore.getInstance()
        db.collection("stories").document(story.id)
            .collection("reactions")
            .get()
            .addOnSuccessListener { snapshot ->
                if (_binding == null) return@addOnSuccessListener
                val list = snapshot.documents.mapNotNull { it.getString("emoji") }
                if (list.isNotEmpty()) {
                    val grouped = list.groupBy { it }.map { "${it.key} ${it.value.size}" }.joinToString("   ")
                    binding.tvReactionsSummary.text = "Reactions:  $grouped"
                    binding.tvReactionsSummary.visibility = View.VISIBLE
                } else {
                    binding.tvReactionsSummary.visibility = View.GONE
                }
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "StoryViewersBottomSheet"
    }
}
