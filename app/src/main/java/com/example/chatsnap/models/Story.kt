package com.example.chatsnap.models

import com.google.firebase.Timestamp

data class Story(
    val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val profileImageUrl: String? = null,
    val mediaUrl: String = "",
    val mediaType: String = "image", // "image" or "video"
    val filter: String = "none",
    val timestamp: Timestamp? = null,
    val viewers: List<StoryViewerInfo> = emptyList(),
    val viewCount: Int = 0, // Unique viewer count
    val totalViews: Int = 0, // Total impressions
    val ownViewCount: Int = 0 // How many times the owner viewed it
)
