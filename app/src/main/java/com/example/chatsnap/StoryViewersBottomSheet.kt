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
        // Sort viewers by most views or latest
        val sortedViewers = story.viewers.sortedByDescending { it.viewCount }
        binding.rvViewers.adapter = StoryViewersAdapter(sortedViewers)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "StoryViewersBottomSheet"
    }
}
