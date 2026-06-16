package com.example.chatsnap

import android.app.Application
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.ffmpeg.FFmpeg
import com.yausername.aria2c.Aria2c
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ChatSnapApplication : Application() {

    companion object {
        // Thread-safe init tracking
        private val initLatch = CountDownLatch(1)
        private val isInitialized = AtomicBoolean(false)
        private var initError: String? = null

        /**
         * Blocks the calling thread until YoutubeDL initialization is complete.
         * Returns true if initialized successfully, false otherwise.
         * Has a 30-second timeout to prevent permanent blocking.
         */
        fun awaitInitialization(): Boolean {
            if (isInitialized.get()) return true
            return try {
                initLatch.await(30, TimeUnit.SECONDS)
                isInitialized.get()
            } catch (e: InterruptedException) {
                Log.e("ChatSnapApplication", "Init wait interrupted", e)
                false
            }
        }

        fun getInitError(): String? = initError
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize YoutubeDL, FFmpeg and Aria2c asynchronously
        CoroutineScope(Dispatchers.IO).launch {
            try {
                YoutubeDL.getInstance().init(this@ChatSnapApplication)
                Log.d("ChatSnapApplication", "YoutubeDL initialized successfully")
            } catch (e: Exception) {
                Log.e("ChatSnapApplication", "Failed to initialize YoutubeDL", e)
                initError = "YoutubeDL init failed: ${e.message}"
                initLatch.countDown()
                return@launch
            }

            try {
                FFmpeg.getInstance().init(this@ChatSnapApplication)
                Log.d("ChatSnapApplication", "FFmpeg initialized successfully")
            } catch (e: Exception) {
                Log.e("ChatSnapApplication", "Failed to initialize FFmpeg", e)
                // Non-fatal: continue even if FFmpeg fails
            }

            try {
                Aria2c.getInstance().init(this@ChatSnapApplication)
                Log.d("ChatSnapApplication", "Aria2c initialized successfully")
            } catch (e: Exception) {
                Log.e("ChatSnapApplication", "Failed to initialize Aria2c", e)
                // Non-fatal: continue even if Aria2c fails
            }

            isInitialized.set(true)
            initLatch.countDown()
            Log.d("ChatSnapApplication", "All download engines initialized and ready")
        }
    }
}
