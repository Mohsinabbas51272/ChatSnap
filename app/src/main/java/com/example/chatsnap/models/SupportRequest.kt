package com.example.chatsnap.models

import com.google.firebase.Timestamp

data class SupportRequest(
    val id: String = "",
    val uid: String = "",
    val userName: String = "",
    val title: String = "",
    val message: String = "",
    val contact: String = "",
    val status: String = "open", // open, in-progress, resolved
    val response: String? = null,
    val timestamp: Timestamp = Timestamp.now()
)
