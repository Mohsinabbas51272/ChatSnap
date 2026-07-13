package com.example.chatsnap.services

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class ScheduledMessageWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    override suspend fun doWork(): Result {
        val currentUid = auth.currentUser?.uid ?: return Result.success()
        val now = System.currentTimeMillis()

        try {
            val snapshot = db.collection("scheduledMessages")
                .whereEqualTo("senderId", currentUid)
                .whereEqualTo("sent", false)
                .get()
                .await()

            for (doc in snapshot.documents) {
                val scheduledFor = doc.getLong("scheduledFor") ?: continue
                if (scheduledFor > now) continue

                val isGroup = doc.getBoolean("isGroup") ?: false
                val collectionName = if (isGroup) "groupMessages" else "messages"
                val messageId = doc.getString("messageId") ?: db.collection(collectionName).document().id

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
                    "scheduledFor" to scheduledFor,
                    "isScheduled" to true
                )

                db.collection(collectionName).document(messageId).set(messageData).await()
                doc.reference.update("sent", true).await()
                Log.d("ScheduledWorker", "Scheduled message sent: $messageId")
            }
        } catch (e: Exception) {
            Log.e("ScheduledWorker", "Error: ${e.message}", e)
            return Result.retry()
        }

        return Result.success()
    }
}
