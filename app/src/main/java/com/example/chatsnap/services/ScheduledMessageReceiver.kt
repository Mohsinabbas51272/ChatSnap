package com.example.chatsnap.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth

class ScheduledMessageReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getStringExtra("messageId") ?: return
        Log.d("ScheduledMsg", "Alarm fired for message: $messageId")

        val db = FirebaseFirestore.getInstance()
        val auth = FirebaseAuth.getInstance()
        val currentUid = auth.currentUser?.uid

        if (currentUid == null) {
            Log.e("ScheduledMsg", "User not logged in, cannot send scheduled message")
            return
        }

        val pendingResult = goAsync()

        db.collection("scheduledMessages").document(messageId).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    Log.e("ScheduledMsg", "Scheduled message not found: $messageId")
                    pendingResult.finish()
                    return@addOnSuccessListener
                }

                val sent = doc.getBoolean("sent") ?: false
                if (sent) {
                    Log.d("ScheduledMsg", "Already sent: $messageId")
                    pendingResult.finish()
                    return@addOnSuccessListener
                }

                val isGroup = doc.getBoolean("isGroup") ?: false
                val collectionName = if (isGroup) "groupMessages" else "messages"
                val now = System.currentTimeMillis()

                val messageData = hashMapOf(
                    "messageId" to messageId,
                    "senderId" to (doc.getString("senderId") ?: ""),
                    "receiverId" to (doc.getString("receiverId") ?: ""),
                    "conversationId" to (doc.getString("conversationId") ?: ""),
                    "content" to (doc.getString("content") ?: ""),
                    "type" to (doc.getString("type") ?: "TEXT"),
                    "mediaUrl" to doc.getString("mediaUrl"),
                    "latitude" to doc.getDouble("latitude"),
                    "longitude" to doc.getDouble("longitude"),
                    "fileName" to doc.getString("fileName"),
                    "timestamp" to now,
                    "status" to "SENT",
                    "viewed" to false,
                    "isGroup" to isGroup,
                    "isDeleted" to false,
                    "isSnap" to (doc.getBoolean("isSnap") ?: false),
                    "pollQuestion" to (doc.getString("pollQuestion") ?: ""),
                    "pollOptions" to (doc.get("pollOptions") ?: emptyList<String>()),
                    "pollVotes" to hashMapOf<String, Int>(),
                    "effect" to (doc.getString("effect") ?: "NONE"),
                    "scheduledFor" to (doc.getLong("scheduledFor") ?: now),
                    "isScheduled" to true
                )

                db.collection(collectionName).document(messageId).set(messageData)
                    .addOnSuccessListener {
                        doc.reference.update("sent", true)
                            .addOnSuccessListener {
                                Log.d("ScheduledMsg", "Message sent successfully: $messageId")
                                pendingResult.finish()
                            }
                            .addOnFailureListener {
                                Log.e("ScheduledMsg", "Failed to mark as sent: ${it.message}")
                                pendingResult.finish()
                            }
                    }
                    .addOnFailureListener {
                        Log.e("ScheduledMsg", "Failed to send message: ${it.message}")
                        pendingResult.finish()
                    }
            }
            .addOnFailureListener {
                Log.e("ScheduledMsg", "Failed to read scheduled message: ${it.message}")
                pendingResult.finish()
            }
    }
}
