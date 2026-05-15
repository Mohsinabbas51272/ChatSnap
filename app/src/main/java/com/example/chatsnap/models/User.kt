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
    val online: Boolean = false
)