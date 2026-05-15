package com.example.chatsnap.models

data class Message(
    val messageId: String = "",
    val senderId: String = "",
    val receiverId: String = "",
    val conversationId: String = "",
    val content: String = "",
    val type: String = "TEXT", // TEXT, IMAGE, VIDEO, AUDIO, DOCUMENT, LOCATION, SNAP, POLL
    val timestamp: Any? = null,
    val status: String = "SENT", // SENT, DELIVERED, READ
    val viewed: Boolean = false,
    val mediaUrl: String? = null,
    val fileName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val replyToMessageId: String? = null,
    val isDeleted: Boolean = false,
    val reactions: Map<String, String> = emptyMap(), // userId -> emoji
    val isGroup: Boolean = false,
    // Snapchat-like one-time view
    val isSnap: Boolean = false, 
    val snapDuration: Int = 10,
    // Poll functionality
    val pollQuestion: String = "",
    val pollOptions: List<String> = emptyList(),
    val pollVotes: Map<String, Int> = emptyMap(), // userId -> selectedOptionIndex
    val effect: String = "NONE" // NONE, SHOUT, WHISPER, BALLOONS
)
