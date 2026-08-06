package com.example.chatsnap.utils

import android.util.Base64
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
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

object FcmNotificationSender {
    private const val TAG = "FCM_SENDER"
    private const val PROJECT_ID = "chatsnap-aa8c7"
    private const val FCM_V1_URL = "https://fcm.googleapis.com/v1/projects/$PROJECT_ID/messages:send"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val SCOPE = "https://www.googleapis.com/auth/firebase.messaging"

    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val formMediaType = "application/x-www-form-urlencoded".toMediaType()

    // Cache OAuth2 access token to avoid regenerating for every notification
    private var cachedAccessToken: String? = null
    private var tokenExpiryTime: Long = 0

    fun sendNotification(
        receiverId: String,
        senderName: String,
        messageContent: String,
        chatId: String,
        type: String = "SINGLE"
    ) {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (receiverId == currentUid) {
            Log.d(TAG, "Self-notification check: receiverId is current user. Skipping.")
            return
        }
        val db = FirebaseFirestore.getInstance()

        // 1. Fetch recipient's FCM device token
        db.collection("users").document(receiverId).get()
            .addOnSuccessListener { userDoc ->
                if (!userDoc.exists()) return@addOnSuccessListener
                val fcmToken = userDoc.getString("fcmToken")
                if (fcmToken.isNullOrEmpty()) {
                    Log.w(TAG, "Recipient $receiverId has no FCM token stored")
                    return@addOnSuccessListener
                }
                val notifEnabled = userDoc.getBoolean("notificationsEnabled") ?: true
                if (!notifEnabled) {
                    Log.d(TAG, "Recipient $receiverId has muted notifications in settings. Skipping push notification.")
                    return@addOnSuccessListener
                }

                // 2. Fetch service account credentials from Firestore
                db.collection("config").document("admin").get()
                    .addOnSuccessListener { configDoc ->
                        val email = configDoc.getString("serviceAccountEmail")
                        val privateKey = configDoc.getString("serviceAccountPrivateKey")

                        if (email.isNullOrEmpty() || privateKey.isNullOrEmpty()) {
                            Log.e(TAG, "=================================================================")
                            Log.e(TAG, "PUSH NOTIFICATIONS DISABLED: No service account configured!")
                            Log.e(TAG, "To enable real-time push notifications:")
                            Log.e(TAG, "1. Firebase Console -> Project Settings -> Service Accounts tab")
                            Log.e(TAG, "2. Click 'Generate New Private Key' -> downloads a JSON file")
                            Log.e(TAG, "3. Open that JSON file and copy these two values:")
                            Log.e(TAG, "   - client_email  (e.g. firebase-adminsdk-xxx@chatsnap-aa8c7.iam.gserviceaccount.com)")
                            Log.e(TAG, "   - private_key   (the long -----BEGIN PRIVATE KEY----- ... block)")
                            Log.e(TAG, "4. In Firestore -> 'config' collection -> 'admin' document, add:")
                            Log.e(TAG, "   Field: serviceAccountEmail  = <paste client_email>")
                            Log.e(TAG, "   Field: serviceAccountPrivateKey = <paste private_key>")
                            Log.e(TAG, "=================================================================")
                            return@addOnSuccessListener
                        }

                        // 3. Authenticate and send via FCM v1 API
                        sendWithV1Api(fcmToken, email, privateKey, senderName, messageContent, chatId, currentUid, type)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to load config: ${e.message}")
                    }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to load recipient: ${e.message}")
            }
    }

    private fun sendWithV1Api(
        fcmToken: String,
        serviceEmail: String,
        privateKeyPem: String,
        senderName: String,
        messageContent: String,
        chatId: String,
        senderId: String,
        type: String
    ) {
        // Use cached access token if still valid
        val now = System.currentTimeMillis() / 1000
        if (cachedAccessToken != null && now < tokenExpiryTime - 60) {
            dispatchV1Post(cachedAccessToken!!, fcmToken, senderName, messageContent, chatId, senderId, type)
            return
        }

        // Generate fresh JWT and exchange for OAuth2 access token
        try {
            val jwt = createJwt(serviceEmail, privateKeyPem)

            val formBody = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=$jwt"
            val tokenRequest = Request.Builder()
                .url(TOKEN_URL)
                .post(formBody.toRequestBody(formMediaType))
                .build()

            client.newCall(tokenRequest).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "OAuth2 token exchange failed: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: ""
                    response.close()

                    if (!response.isSuccessful) {
                        Log.e(TAG, "OAuth2 token error ${response.code}: $body")
                        return
                    }

                    try {
                        val json = JSONObject(body)
                        val accessToken = json.getString("access_token")
                        val expiresIn = json.optLong("expires_in", 3600)

                        // Cache the token
                        cachedAccessToken = accessToken
                        tokenExpiryTime = System.currentTimeMillis() / 1000 + expiresIn

                        Log.d(TAG, "OAuth2 access token obtained successfully")
                        dispatchV1Post(accessToken, fcmToken, senderName, messageContent, chatId, senderId, type)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse token response: ${e.message}")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "JWT creation failed: ${e.message}", e)
        }
    }

    /**
     * Creates a signed JWT (RS256) for Google OAuth2 service account authentication.
     */
    private fun createJwt(serviceEmail: String, privateKeyPem: String): String {
        val now = System.currentTimeMillis() / 1000
        val exp = now + 3600

        val header = JSONObject().apply {
            put("alg", "RS256")
            put("typ", "JWT")
        }

        val payload = JSONObject().apply {
            put("iss", serviceEmail)
            put("scope", SCOPE)
            put("aud", TOKEN_URL)
            put("iat", now)
            put("exp", exp)
        }

        val headerB64 = base64UrlEncode(header.toString().toByteArray())
        val payloadB64 = base64UrlEncode(payload.toString().toByteArray())
        val signingInput = "$headerB64.$payloadB64"

        // Parse PEM-formatted RSA private key
        val cleanKey = privateKeyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\n", "")
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")

        val keyBytes = Base64.decode(cleanKey, Base64.DEFAULT)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance("RSA")
        val rsaPrivateKey = keyFactory.generatePrivate(keySpec)

        // Sign with SHA256withRSA
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(rsaPrivateKey)
        signature.update(signingInput.toByteArray())
        val signedBytes = signature.sign()
        val signatureB64 = base64UrlEncode(signedBytes)

        return "$signingInput.$signatureB64"
    }

    private fun base64UrlEncode(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /**
     * Sends the actual push notification via FCM v1 HTTP API.
     */
    private fun dispatchV1Post(
        accessToken: String,
        fcmToken: String,
        senderName: String,
        messageContent: String,
        chatId: String,
        senderId: String,
        type: String
    ) {
        try {
            val messageJson = JSONObject().apply {
                put("message", JSONObject().apply {
                    put("token", fcmToken)
                    put("notification", JSONObject().apply {
                        put("title", senderName)
                        put("body", messageContent)
                    })
                    put("data", JSONObject().apply {
                        put("title", senderName)
                        put("body", messageContent)
                        put("chatId", chatId)
                        put("senderId", senderId)
                        put("type", type)
                    })
                    put("android", JSONObject().apply {
                        put("priority", "high")
                        put("notification", JSONObject().apply {
                            put("sound", "default")
                            put("channel_id", "chat_notifications")
                        })
                    })
                })
            }

            val request = Request.Builder()
                .url(FCM_V1_URL)
                .post(messageJson.toString().toRequestBody(jsonMediaType))
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "FCM v1 send failed: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: ""
                    response.close()

                    if (response.isSuccessful) {
                        Log.d(TAG, "✅ Push notification sent successfully via FCM v1!")
                    } else {
                        Log.e(TAG, "FCM v1 error ${response.code}: $body")
                        if (response.code == 401 || response.code == 403) {
                            // Token expired or invalid, force re-auth next time
                            cachedAccessToken = null
                            tokenExpiryTime = 0
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build FCM v1 request: ${e.message}")
        }
    }
}
