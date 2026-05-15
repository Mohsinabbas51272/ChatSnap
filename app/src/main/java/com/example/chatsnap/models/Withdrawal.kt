package com.example.chatsnap.models

import com.google.firebase.Timestamp

data class Withdrawal(
    val id: String = "",
    val uid: String = "",
    val userDisplayName: String = "",
    val amount: Long = 0,
    val accountDetails: String = "",
    val status: String = "PENDING", // PENDING, COMPLETED, REJECTED
    val timestamp: Timestamp = Timestamp.now(),
    val transactionId: String = ""
)
