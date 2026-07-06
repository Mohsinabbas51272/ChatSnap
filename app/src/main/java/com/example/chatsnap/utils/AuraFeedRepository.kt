package com.example.chatsnap.utils

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.example.chatsnap.models.AuraComment
import com.example.chatsnap.models.AuraVideo
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object AuraFeedRepository {
    private val firestore = FirebaseFirestore.getInstance()
    private var isDatabaseChecked = false

    // Seeds the database with high-quality sample video URLs if empty
    suspend fun checkAndSeedDatabase() {
        if (isDatabaseChecked) {
            android.util.Log.d("AuraFeed", "Seeding already checked, skipping.")
            return
        }
        try {
            android.util.Log.d("AuraFeed", "Checking database seeding...")
            val sampleDoc = firestore.collection("aura_videos").document("sample_1").get().await()
            val videoUrl = sampleDoc.getString("videoUrl") ?: ""
            val shouldSeed = !sampleDoc.exists() || videoUrl.contains("mixkit.co")
            android.util.Log.d("AuraFeed", "shouldSeed = $shouldSeed (exists: ${sampleDoc.exists()}, url: $videoUrl)")
            
            if (shouldSeed) {
                android.util.Log.d("AuraFeed", "Starting database seeding with stable URLs...")
                val samples = listOf(
                    AuraVideo(
                        id = "sample_1",
                        creatorUid = "chatsnap_admin",
                        creatorUsername = "nature_vibes",
                        creatorPhotoUrl = "",
                        videoUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
                        caption = "Beautiful yellow flowers swaying in the wind! 🌸 #nature #aesthetic #flowers",
                        hashtags = listOf("nature", "aesthetic", "flowers"),
                        musicName = "Original Sound - Nature Vibes",
                        timestamp = System.currentTimeMillis() - 100000,
                        viewCount = 1204,
                        likeCount = 385,
                        commentCount = 12,
                        shareCount = 45,
                        saveCount = 28
                    ),
                    AuraVideo(
                        id = "sample_2",
                        creatorUid = "chatsnap_admin",
                        creatorUsername = "ocean_breeze",
                        creatorPhotoUrl = "",
                        videoUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4",
                        caption = "Listen to the soothing waves... 🌊 Relieve your stress. #waves #relax #soothing",
                        hashtags = listOf("waves", "relax", "soothing"),
                        musicName = "Soothing Ocean Waves - Relaxing Music",
                        timestamp = System.currentTimeMillis() - 80000,
                        viewCount = 2309,
                        likeCount = 984,
                        commentCount = 34,
                        shareCount = 124,
                        saveCount = 92
                    ),
                    AuraVideo(
                        id = "sample_3",
                        creatorUid = "chatsnap_admin",
                        creatorUsername = "coder_hacks",
                        creatorPhotoUrl = "",
                        videoUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4",
                        caption = "Late night coding sessions are the best! 💻 Who's with me? #coding #programming #developer",
                        hashtags = listOf("coding", "programming", "developer"),
                        musicName = "Synthwave Beats - Lo-Fi Coder",
                        timestamp = System.currentTimeMillis() - 50000,
                        viewCount = 5403,
                        likeCount = 1432,
                        commentCount = 89,
                        shareCount = 320,
                        saveCount = 154
                    )
                )

                val batch = firestore.batch()
                for (video in samples) {
                    val ref = firestore.collection("aura_videos").document(video.id)
                    batch.set(ref, video)
                }
                batch.commit().await()
                android.util.Log.d("AuraFeed", "Seeding batch write completed successfully!")
            }
            isDatabaseChecked = true
        } catch (e: Exception) {
            android.util.Log.e("AuraFeed", "Error seeding database: ${e.message}", e)
        }
    }

    suspend fun getForYouFeed(limit: Int = 10, lastTimestamp: Long? = null): List<AuraVideo> {
        checkAndSeedDatabase()
        return withContext(Dispatchers.IO) {
            try {
                var query = firestore.collection("aura_videos")
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .limit(limit.toLong())

                if (lastTimestamp != null) {
                    query = query.startAfter(lastTimestamp)
                }

                val snapshot = query.get().await()
                snapshot.toObjects(AuraVideo::class.java)
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    suspend fun getFollowingFeed(uid: String, limit: Int = 10, lastTimestamp: Long? = null): List<AuraVideo> {
        return withContext(Dispatchers.IO) {
            try {
                // Get followings list
                val userDoc = firestore.collection("users").document(uid).get().await()
                @Suppress("UNCHECKED_CAST")
                val followingList = userDoc.get("following") as? List<String> ?: emptyList()

                if (followingList.isEmpty()) return@withContext emptyList()

                // Firebase 'in' query has a limit of 10 items.
                val chunk = followingList.take(10)

                val snapshot = firestore.collection("aura_videos")
                    .whereIn("creatorUid", chunk)
                    .limit(limit.toLong())
                    .get()
                    .await()

                // Sort client-side to avoid requiring a composite Firestore index
                snapshot.toObjects(AuraVideo::class.java).sortedByDescending { it.timestamp }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    suspend fun getMyVideos(uid: String): List<AuraVideo> {
        return withContext(Dispatchers.IO) {
            try {
                val snapshot = firestore.collection("aura_videos")
                    .whereEqualTo("creatorUid", uid)
                    .get()
                    .await()
                // Sort client-side to avoid requiring a composite Firestore index
                snapshot.toObjects(AuraVideo::class.java).sortedByDescending { it.timestamp }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    suspend fun likeVideo(videoId: String, uid: String): Boolean {
        return try {
            val ref = firestore.collection("aura_videos").document(videoId)
            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(ref)
                if (snapshot.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val likesList = snapshot.get("likes") as? List<String> ?: emptyList()
                    if (!likesList.contains(uid)) {
                        transaction.update(ref, "likes", FieldValue.arrayUnion(uid))
                        transaction.update(ref, "likeCount", FieldValue.increment(1))
                    }
                }
            }.await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun unlikeVideo(videoId: String, uid: String): Boolean {
        return try {
            val ref = firestore.collection("aura_videos").document(videoId)
            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(ref)
                if (snapshot.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val likesList = snapshot.get("likes") as? List<String> ?: emptyList()
                    if (likesList.contains(uid)) {
                        transaction.update(ref, "likes", FieldValue.arrayRemove(uid))
                        transaction.update(ref, "likeCount", FieldValue.increment(-1))
                    }
                }
            }.await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun saveVideo(videoId: String, uid: String): Boolean {
        return try {
            val ref = firestore.collection("aura_videos").document(videoId)
            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(ref)
                if (snapshot.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val savesList = snapshot.get("saves") as? List<String> ?: emptyList()
                    if (!savesList.contains(uid)) {
                        transaction.update(ref, "saves", FieldValue.arrayUnion(uid))
                        transaction.update(ref, "saveCount", FieldValue.increment(1))
                    }
                }
            }.await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun unsaveVideo(videoId: String, uid: String): Boolean {
        return try {
            val ref = firestore.collection("aura_videos").document(videoId)
            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(ref)
                if (snapshot.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val savesList = snapshot.get("saves") as? List<String> ?: emptyList()
                    if (savesList.contains(uid)) {
                        transaction.update(ref, "saves", FieldValue.arrayRemove(uid))
                        transaction.update(ref, "saveCount", FieldValue.increment(-1))
                    }
                }
            }.await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun addComment(videoId: String, commentText: String, userId: String, username: String, userPhotoUrl: String): AuraComment? {
        return try {
            val commentRef = firestore.collection("aura_videos").document(videoId).collection("comments").document()
            val comment = AuraComment(
                id = commentRef.id,
                videoId = videoId,
                userId = userId,
                username = username,
                userPhotoUrl = userPhotoUrl,
                text = commentText,
                timestamp = System.currentTimeMillis()
            )
            commentRef.set(comment).await()

            // Update comment count on video
            firestore.collection("aura_videos").document(videoId)
                .update("commentCount", FieldValue.increment(1))
                .await()

            comment
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun getComments(videoId: String): List<AuraComment> {
        return try {
            val snapshot = firestore.collection("aura_videos").document(videoId).collection("comments")
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .get()
                .await()
            snapshot.toObjects(AuraComment::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun recordView(videoId: String, uid: String, durationMs: Long, completionPct: Int): Boolean {
        return try {
            val videoRef = firestore.collection("aura_videos").document(videoId)
            videoRef.update("viewCount", FieldValue.increment(1))

            val interactionRef = firestore.collection("video_interactions").document()
            val interaction = hashMapOf(
                "userId" to uid,
                "videoId" to videoId,
                "durationMs" to durationMs,
                "completionPct" to completionPct,
                "timestamp" to System.currentTimeMillis()
            )
            interactionRef.set(interaction).await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun shareVideo(videoId: String): Boolean {
        return try {
            firestore.collection("aura_videos").document(videoId)
                .update("shareCount", FieldValue.increment(1))
                .await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun followUser(currentUid: String, targetUid: String): Boolean {
        if (currentUid == targetUid) return false
        return try {
            val currentUserRef = firestore.collection("users").document(currentUid)
            val targetUserRef = firestore.collection("users").document(targetUid)

            firestore.runTransaction { transaction ->
                transaction.update(currentUserRef, "following", FieldValue.arrayUnion(targetUid))
                transaction.update(targetUserRef, "followers", FieldValue.arrayUnion(currentUid))
            }.await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun unfollowUser(currentUid: String, targetUid: String): Boolean {
        return try {
            val currentUserRef = firestore.collection("users").document(currentUid)
            val targetUserRef = firestore.collection("users").document(targetUid)

            firestore.runTransaction { transaction ->
                transaction.update(currentUserRef, "following", FieldValue.arrayRemove(targetUid))
                transaction.update(targetUserRef, "followers", FieldValue.arrayRemove(currentUid))
            }.await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun isFollowing(currentUid: String, targetUid: String): Boolean {
        return try {
            val doc = firestore.collection("users").document(currentUid).get().await()
            @Suppress("UNCHECKED_CAST")
            val followingList = doc.get("following") as? List<String> ?: emptyList()
            followingList.contains(targetUid)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deleteVideo(videoId: String): Boolean {
        return try {
            firestore.collection("aura_videos").document(videoId).delete().await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // Decodes base64-encoded videos into temporary local cache files for ExoPlayer playback
    fun getPlayableUri(videoUrl: String, context: Context): Uri {
        if (videoUrl.startsWith("data:video/mp4;base64,")) {
            try {
                val cleanBase64 = videoUrl.substringAfter("base64,")
                val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                
                // Use a unique file name based on the base64 hash to cache it
                val hash = cleanBase64.take(15).hashCode()
                val tempFile = File(context.cacheDir, "aura_cache_$hash.mp4")
                
                if (!tempFile.exists()) {
                    val fos = FileOutputStream(tempFile)
                    fos.write(decodedBytes)
                    fos.flush()
                    fos.close()
                }
                return Uri.fromFile(tempFile)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return Uri.parse(videoUrl)
    }

    // Helper to check video file size before reading/encoding
    fun getVideoSize(context: Context, uri: Uri): Long {
        var size = 0L
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex != -1) {
                        size = it.getLong(sizeIndex)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return size
    }

    // Reads selected video file as bytes and encodes to base64
    fun encodeVideoToBase64(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes() ?: return null
            inputStream.close()
            
            val base64 = Base64.encodeToString(bytes, Base64.DEFAULT)
            "data:video/mp4;base64,$base64"
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
