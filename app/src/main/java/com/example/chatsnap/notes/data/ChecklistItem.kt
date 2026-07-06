package com.example.chatsnap.notes.data

import java.util.UUID

data class ChecklistItem(
    val id: String = UUID.randomUUID().toString(),
    var text: String,
    var isChecked: Boolean
)
