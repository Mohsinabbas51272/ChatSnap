package com.example.chatsnap.models

data class Transaction(
    val id: String = "",
    val amount: Int = 0,
    val type: String = "earn", // "earn" or "withdraw"
    val source: String? = null,
    val status: String? = "pending", // "pending", "completed", "failed", "rejected"
    val timestamp: Long = System.currentTimeMillis(),
    val accountDetails: String? = null,
    val referenceId: String = ""
) {
    companion object {
        fun generateRefId(): String {
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val code = (1..6).map { chars.random() }.joinToString("")
            return "TXN-$code"
        }
    }
}
