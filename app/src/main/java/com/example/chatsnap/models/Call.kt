package com.example.chatsnap.models

data class Call(
    val id: String = "",
    val callerId: String = "",
    val callerName: String = "",
    val receiverId: String = "",
    val receiverName: String = "",
    val type: String = "voice", // voice or video
    val status: String = "completed", // missed, rejected, completed
    val timestamp: Long = System.currentTimeMillis()
)
