package com.example.chatsnap.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.chatsnap.ChatActivity
import com.example.chatsnap.MainActivity
import com.example.chatsnap.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        updateTokenInFirestore(token)
    }

    private fun updateTokenInFirestore(token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance().collection("users").document(uid)
            .set(mapOf("fcmToken" to token), com.google.firebase.firestore.SetOptions.merge())
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        android.util.Log.d("FCM_TEST", "Message received from: ${remoteMessage.from}")

        val senderId = remoteMessage.data["senderId"]
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        if (senderId != null && senderId == currentUid) {
            android.util.Log.d("FCM_TEST", "Self message notification received, ignoring.")
            return
        }

        val title = remoteMessage.notification?.title ?: remoteMessage.data["title"] ?: "New Message"
        val rawBody = remoteMessage.notification?.body ?: remoteMessage.data["body"] ?: "Check your messages"
        val type = remoteMessage.data["type"]
        val chatId = remoteMessage.data["chatId"]

        val body = if (type != "CALL") {
            if (rawBody.lowercase().contains("snap")) {
                "send you snap"
            } else {
                "send you chat"
            }
        } else {
            rawBody
        }

        android.util.Log.d("FCM_TEST", "Title: $title, Body: $body")
        showNotification(title, body, chatId, senderId, type)
    }

    private fun showNotification(title: String, body: String, chatId: String?, senderId: String?, type: String?) {
        val channelId = "chat_notifications"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Chat Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Real-time chat and call notifications"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = if (chatId != null) {
            Intent(this, ChatActivity::class.java).apply {
                putExtra("chatId", chatId)
                if (type == "GROUP") {
                    putExtra("isGroup", true)
                    putExtra("groupId", senderId) // Assuming senderId is used as groupId for group notifications
                } else {
                    putExtra("receiverId", senderId)
                }
            }
        } else {
            Intent(this, MainActivity::class.java)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)

        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }
}
