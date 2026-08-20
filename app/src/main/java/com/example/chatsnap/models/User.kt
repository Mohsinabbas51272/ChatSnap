package com.example.chatsnap.models

data class User(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val profileImageUrl: String = "",
    val profileCompleted: Boolean = false,
    val friends: List<String> = emptyList(),
    val secretPassword: String? = null,
    val appLockCode: String? = null,
    val status: String? = null,
    val lastStatusUpdate: Long? = null,
    val isBlocked: Boolean? = false,
    val isAdmin: Boolean = false,
    val online: Boolean = false,
    val following: List<String> = emptyList(),
    val followers: List<String> = emptyList(),
    val isVerified: Boolean = false,
    val sessionId: String? = null,
    val lastSeen: Long? = null,
    val fcmToken: String? = null,
    val selectedTheme: String? = null,
    val notificationsEnabled: Boolean = true
)