package com.example.chatsnap.models

data class FriendRequest(
    val requestId: String = "",
    val fromId: String = "",
    val fromName: String = "",
    val toId: String = "",
    val status: String = "PENDING", // PENDING, ACCEPTED, REJECTED
    val source: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
