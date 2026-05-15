package com.example.chatsnap.models

data class Group(
    val id: String = "",
    val name: String = "",
    val memberIds: List<String> = emptyList(),
    val adminId: String = "",
    val lastMessage: String = "",
    val lastMessageTimestamp: Long = 0,
    val groupImageUrl: String? = null
)
