package com.example.chatsnap.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiApiClient {
    var apiKey: String = com.example.chatsnap.BuildConfig.GROQ_API_KEY

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    suspend fun generateResponse(prompt: String, chatHistory: List<Pair<String, Boolean>> = emptyList()): String = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) {
            return@withContext "API Key not configured. Please set your Groq API key."
        }

        val url = "https://api.groq.com/openai/v1/chat/completions"

        try {
            val messagesArray = JSONArray()

            // System prompt
            messagesArray.put(JSONObject().apply {
                put("role", "system")
                put("content", "You are a helpful AI assistant inside ChatSnap app. Be concise, friendly and helpful. Respond in the same language the user writes in.")
            })

            // Add chat history for multi-turn conversation
            for (turn in chatHistory) {
                val role = if (turn.second) "user" else "assistant"
                messagesArray.put(JSONObject().apply {
                    put("role", role)
                    put("content", turn.first)
                })
            }

            // Add current prompt
            messagesArray.put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })

            // Construct payload (OpenAI-compatible format)
            val root = JSONObject().apply {
                put("model", "llama-3.3-70b-versatile")
                put("messages", messagesArray)
                put("temperature", 0.7)
                put("max_tokens", 1024)
            }

            val requestBody = root.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) {
                    val errMsg = try {
                        val errObj = JSONObject(body ?: "")
                        errObj.getJSONObject("error").getString("message")
                    } catch (e: Exception) {
                        body ?: "Request failed with code ${response.code}"
                    }
                    return@withContext "Groq API Error: $errMsg"
                }

                val jsonResponse = JSONObject(body)
                val choices = jsonResponse.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val choice = choices.getJSONObject(0)
                    val message = choice.optJSONObject("message")
                    if (message != null) {
                        return@withContext message.optString("content", "No response generated.")
                    }
                }
                "Error: Failed to parse response from Groq."
            }
        } catch (e: Exception) {
            e.printStackTrace()
            "Network error: ${e.message ?: "Unknown error"}"
        }
    }
}
