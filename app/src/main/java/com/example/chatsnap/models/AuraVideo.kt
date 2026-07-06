package com.example.chatsnap.models

data class AuraVideo(
    val id: String = "",
    val creatorUid: String = "",
    val creatorUsername: String = "",
    val creatorPhotoUrl: String = "",
    val videoUrl: String = "",
    val caption: String = "",
    val hashtags: List<String> = emptyList(),
    val musicName: String = "",
    val timestamp: Long = 0L,
    val viewCount: Long = 0L,
    val likeCount: Long = 0L,
    val commentCount: Long = 0L,
    val shareCount: Long = 0L,
    val saveCount: Long = 0L,
    val likes: List<String> = emptyList(),
    val saves: List<String> = emptyList()
)
