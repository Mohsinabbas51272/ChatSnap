package com.example.chatsnap.models

data class UserMode(
    val userId: String = "",
    val emoji: String = "😊",
    val modeText: String = "", 
    val photoUrl: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val expiresAt: Long = timestamp + 14400000 
)