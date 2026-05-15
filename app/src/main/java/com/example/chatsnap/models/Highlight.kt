package com.example.chatsnap.models

import com.google.firebase.Timestamp

data class Highlight(
    val id: String = "",
    val userId: String = "",
    val storyId: String = "",
    val mediaUrl: String = "",
    val mediaType: String = "", // image, video
    val timestamp: Timestamp = Timestamp.now(),
    val displayName: String = ""
)
