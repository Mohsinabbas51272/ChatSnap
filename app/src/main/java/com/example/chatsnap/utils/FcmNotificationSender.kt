package com.example.chatsnap.utils

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

object FcmNotificationSender {
    private const val TAG = "FCM_SENDER"
    private val client = OkHttpClient()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    fun sendNotification(
        receiverId: String,
        senderName: String,
        messageContent: String,
        chatId: String,
        type: String = "SINGLE"
    ) {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = FirebaseFirestore.getInstance()

        // 1. Fetch recipient's FCM Token
        db.collection("users").document(receiverId).get()
            .addOnSuccessListener { userDoc ->
                if (!userDoc.exists()) return@addOnSuccessListener
                val fcmToken = userDoc.getString("fcmToken")
                if (fcmToken.isNullOrEmpty()) {
                    Log.d(TAG, "Recipient has no FCM Token stored")
                    return@addOnSuccessListener
                }

                // 2. Fetch FCM Server Key (or use fallback Web API Key)
                db.collection("config").document("admin").get()
                    .addOnSuccessListener { configDoc ->
                        val serverKey = configDoc.getString("fcmServerKey") 
                            ?: "AIzaSyCAt4U4iqLsl17R7olSLyPi0OtCmP2NQVQ" // Firebase standard Web API key

                        dispatchFcmPost(fcmToken, serverKey, senderName, messageContent, chatId, currentUid, type)
                    }
                    .addOnFailureListener {
                        // Fallback in case config lookup fails
                        val fallbackKey = "AIzaSyCAt4U4iqLsl17R7olSLyPi0OtCmP2NQVQ"
                        dispatchFcmPost(fcmToken, fallbackKey, senderName, messageContent, chatId, currentUid, type)
                    }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to load recipient token: ${e.message}")
            }
    }

    private fun dispatchFcmPost(
        token: String,
        serverKey: String,
        senderName: String,
        messageContent: String,
        chatId: String,
        senderId: String,
        type: String
    ) {
        try {
            // Build legacy FCM send request payload
            val root = JSONObject()
            root.put("to", token)

            val notification = JSONObject()
            notification.put("title", senderName)
            notification.put("body", messageContent)
            notification.put("sound", "default")
            root.put("notification", notification)

            val data = JSONObject()
            data.put("title", senderName)
            data.put("body", messageContent)
            data.put("chatId", chatId)
            data.put("senderId", senderId)
            data.put("type", type)
            root.put("data", data)

            val requestBody = root.toString().toRequestBody(mediaType)
            val request = Request.Builder()
                .url("https://fcm.googleapis.com/fcm/send")
                .post(requestBody)
                .addHeader("Authorization", "key=$serverKey")
                .addHeader("Content-Type", "application/json")
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Notification post failed: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "Notification sent successfully!")
                    } else {
                        val responseBody = response.body?.string() ?: ""
                        Log.e(TAG, "FCM response error: ${response.code} - $responseBody")
                        if (response.code == 401) {
                            Log.e(TAG, "---------------------------------------------------------------------------------")
                            Log.e(TAG, "CRITICAL: PUSH NOTIFICATIONS FAILED DUE TO INVALID FCM KEY!")
                            Log.e(TAG, "The server key 'key=$serverKey' is unauthorized. To fix this immediately:")
                            Log.e(TAG, "1. Open your Firebase Console -> Click Project Settings (Gear icon) -> Cloud Messaging tab.")
                            Log.e(TAG, "2. Under 'Cloud Messaging API (Legacy)', click Enable. (If it shows 'Disabled', click the 3 dots, select 'Manage API in Google Cloud Console', enable it there, then reload this page).")
                            Log.e(TAG, "3. Copy the generated 'Server Key'.")
                            Log.e(TAG, "4. Open your Firestore Database console -> Go to 'config' collection -> 'admin' document.")
                            Log.e(TAG, "5. Set the field 'fcmServerKey' to the copied Server Key string.")
                            Log.e(TAG, "---------------------------------------------------------------------------------")
                        }
                    }
                    response.close()
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Failed to compile FCM request JSON: ${e.message}")
        }
    }
}
