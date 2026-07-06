package com.example.chatsnap.models

data class AuraComment(
    val id: String = "",
    val videoId: String = "",
    val userId: String = "",
    val username: String = "",
    val userPhotoUrl: String = "",
    val text: String = "",
    val timestamp: Long = 0L
)
