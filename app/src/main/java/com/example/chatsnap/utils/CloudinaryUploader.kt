package com.example.chatsnap.utils

import android.content.Context
import android.net.Uri
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.Sink
import okio.buffer
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.TimeUnit

object CloudinaryUploader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    // Default or Firestore configured Cloudinary credentials
    var cloudName: String = "dmsmn6f7i"
    var uploadPreset: String = "chatsnap_preset"

    private var isConfigLoaded = false

    suspend fun loadConfigFromFirestore() {
        if (isConfigLoaded) return
        try {
            val doc = FirebaseFirestore.getInstance()
                .collection("config")
                .document("cloudinary")
                .get()
                .await()

            if (doc != null && doc.exists()) {
                val cn = doc.getString("cloudName")
                val up = doc.getString("uploadPreset")
                if (!cn.isNullOrEmpty()) cloudName = cn
                if (!up.isNullOrEmpty()) uploadPreset = up
            }
            isConfigLoaded = true
        } catch (e: Exception) {
            android.util.Log.e("CloudinaryUploader", "Config load failed, using defaults", e)
        }
    }

    suspend fun uploadMedia(
        context: Context,
        uri: Uri,
        resourceType: String = "auto", // "image", "video", or "auto"
        onProgress: ((Int) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        loadConfigFromFirestore()
        try {
            val contentResolver = context.contentResolver
            val inputStream: InputStream = contentResolver.openInputStream(uri) ?: return@withContext null
            val bytes = inputStream.readBytes()
            inputStream.close()

            val mimeType = contentResolver.getType(uri) ?: if (resourceType == "video") "video/mp4" else "image/jpeg"
            val fileName = "upload_${System.currentTimeMillis()}.${if (mimeType.contains("video")) "mp4" else "jpg"}"

            val requestBodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("upload_preset", uploadPreset)
                .addFormDataPart(
                    "file",
                    fileName,
                    bytes.toRequestBody(mimeType.toMediaTypeOrNull())
                )

            val rawRequestBody = requestBodyBuilder.build()
            val finalRequestBody = if (onProgress != null) {
                ProgressRequestBody(rawRequestBody, onProgress)
            } else {
                rawRequestBody
            }

            val endpointUrl = "https://api.cloudinary.com/v1_1/$cloudName/$resourceType/upload"
            val request = Request.Builder()
                .url(endpointUrl)
                .post(finalRequestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBodyStr = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(responseBodyStr)
                val secureUrl = json.optString("secure_url")
                if (secureUrl.isNotEmpty()) {
                    return@withContext secureUrl
                }
                json.optString("url")
            } else {
                android.util.Log.e("CloudinaryUploader", "Cloudinary HTTP ${response.code}: $responseBodyStr")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("CloudinaryUploader", "Exception uploading to Cloudinary: ${e.message}", e)
            null
        }
    }

    private class ProgressRequestBody(
        private val delegate: RequestBody,
        private val onProgress: (Int) -> Unit
    ) : RequestBody() {
        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()

        override fun writeTo(sink: BufferedSink) {
            val countingSink = CountingSink(sink, contentLength(), onProgress)
            val bufferedSink = countingSink.buffer()
            delegate.writeTo(bufferedSink)
            bufferedSink.flush()
        }
    }

    private class CountingSink(
        delegate: Sink,
        private val totalBytes: Long,
        private val onProgress: (Int) -> Unit
    ) : ForwardingSink(delegate) {
        private var bytesWritten = 0L

        override fun write(source: Buffer, byteCount: Long) {
            super.write(source, byteCount)
            bytesWritten += byteCount
            if (totalBytes > 0) {
                val progress = ((bytesWritten * 100) / totalBytes).toInt()
                onProgress(progress.coerceAtMost(100))
            }
        }
    }
}
