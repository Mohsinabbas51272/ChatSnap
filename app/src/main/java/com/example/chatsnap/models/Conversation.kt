package com.example.chatsnap.models

data class Conversation(
    val partnerId: String = "",
    val partnerName: String = "",
    val partnerPhotoUrl: String? = null,
    val lastMessage: String = "",
    val lastMessageTimestamp: Long = 0,
    val lastMessageSenderId: String = "",
    val lastMessageType: String = "TEXT",
    val lastMessageViewed: Boolean = false,
    val unreadCount: Int = 0,
    val isOnline: Boolean = false,
    val isSecret: Boolean = false,
    val isGroup: Boolean = false,
    val streakCount: Int = 0,
    val isExpiringSoon: Boolean = false,
    val isPartnerAdmin: Boolean = false
)
