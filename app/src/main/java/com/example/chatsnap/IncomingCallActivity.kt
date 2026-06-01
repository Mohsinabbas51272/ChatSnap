package com.example.chatsnap

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import coil.load
import com.example.chatsnap.databinding.ActivityIncomingCallBinding
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class IncomingCallActivity : BaseActivity() {

    private lateinit var binding: ActivityIncomingCallBinding
    private var callId: String? = null
    private var callListener: ListenerRegistration? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var pulseAnimatorSet: AnimatorSet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lock screen
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        binding = ActivityIncomingCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Extract extras
        callId = intent.getStringExtra("callId")
        val callerName = intent.getStringExtra("callerName") ?: "Unknown"
        val callType = intent.getStringExtra("callType") ?: "Voice"
        val callerPhoto = intent.getStringExtra("callerPhoto")
        val channelName = intent.getStringExtra("channelName")

        // Setup UI
        binding.tvCallerName.text = callerName
        binding.tvIncomingLabel.text = "Incoming $callType Call"

        // Load caller photo
        if (!callerPhoto.isNullOrEmpty()) {
            if (callerPhoto.startsWith("data:image") || callerPhoto.length > 1000) {
                try {
                    val cleanBase64 = if (callerPhoto.contains(",")) callerPhoto.substringAfter(",") else callerPhoto
                    val decoded = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                    binding.ivCallerAvatar.setImageBitmap(bitmap)
                } catch (_: Exception) {}
            } else {
                binding.ivCallerAvatar.load(callerPhoto) {
                    crossfade(true)
                    placeholder(R.drawable.ic_launcher_foreground)
                }
            }
        }

        // Start animations
        startPulseAnimation()

        // Start ringtone & vibration
        startRinging()
        startVibration()

        // Listen for call status changes (caller cancels, etc.)
        listenForCallStatus()

        // Accept call
        binding.btnAccept.setOnClickListener {
            stopRinging()
            stopVibration()
            callId?.let { id ->
                FirebaseFirestore.getInstance()
                    .collection("calls").document(id)
                    .update("status", "answered")
            }
            val callIntent = Intent(this, CallActivity::class.java).apply {
                putExtra("callId", callId)
                putExtra("callType", callType)
                putExtra("receiverName", callerName)
                putExtra("channelName", channelName)
                putExtra("isCaller", false)
            }
            startActivity(callIntent)
            finish()
        }

        // Decline call
        binding.btnDecline.setOnClickListener {
            stopRinging()
            stopVibration()
            callId?.let { id ->
                FirebaseFirestore.getInstance()
                    .collection("calls").document(id)
                    .update("status", "rejected")
            }
            finish()
        }

        // Entrance animation
        animateEntrance()
    }

    private fun animateEntrance() {
        binding.ivCallerAvatar.alpha = 0f
        binding.ivCallerAvatar.scaleX = 0.5f
        binding.ivCallerAvatar.scaleY = 0.5f
        binding.ivCallerAvatar.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(600)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        binding.tvCallerName.alpha = 0f
        binding.tvCallerName.translationY = 30f
        binding.tvCallerName.animate()
            .alpha(1f).translationY(0f)
            .setStartDelay(200).setDuration(500).start()

        binding.tvIncomingLabel.alpha = 0f
        binding.tvIncomingLabel.translationY = 20f
        binding.tvIncomingLabel.animate()
            .alpha(1f).translationY(0f)
            .setStartDelay(300).setDuration(500).start()

        binding.buttonsContainer.alpha = 0f
        binding.buttonsContainer.translationY = 80f
        binding.buttonsContainer.animate()
            .alpha(1f).translationY(0f)
            .setStartDelay(400).setDuration(600)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun startPulseAnimation() {
        val ring1 = binding.pulseRing1
        val ring2 = binding.pulseRing2

        val scaleX1 = ObjectAnimator.ofFloat(ring1, View.SCALE_X, 1f, 1.4f, 1f)
        val scaleY1 = ObjectAnimator.ofFloat(ring1, View.SCALE_Y, 1f, 1.4f, 1f)
        val alpha1 = ObjectAnimator.ofFloat(ring1, View.ALPHA, 0.15f, 0.05f, 0.15f)

        val scaleX2 = ObjectAnimator.ofFloat(ring2, View.SCALE_X, 1f, 1.25f, 1f)
        val scaleY2 = ObjectAnimator.ofFloat(ring2, View.SCALE_Y, 1f, 1.25f, 1f)
        val alpha2 = ObjectAnimator.ofFloat(ring2, View.ALPHA, 0.25f, 0.1f, 0.25f)

        pulseAnimatorSet = AnimatorSet().apply {
            playTogether(scaleX1, scaleY1, alpha1, scaleX2, scaleY2, alpha2)
            duration = 1500
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!isFinishing) {
                        start()
                    }
                }
            })
            start()
        }
    }

    private fun startRinging() {
        try {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@IncomingCallActivity, ringtoneUri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            android.util.Log.e("IncomingCall", "Failed to play ringtone: ${e.message}")
        }
    }

    private fun stopRinging() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (_: Exception) {}
    }

    private fun startVibration() {
        try {
            vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vm = getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator
            }

            val pattern = longArrayOf(0, 800, 400, 800, 400, 800)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            android.util.Log.e("IncomingCall", "Failed to vibrate: ${e.message}")
        }
    }

    private fun stopVibration() {
        try {
            vibrator?.cancel()
            vibrator = null
        } catch (_: Exception) {}
    }

    private fun listenForCallStatus() {
        val id = callId ?: return
        callListener = FirebaseFirestore.getInstance()
            .collection("calls").document(id)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                val status = snapshot.getString("status")
                // If caller cancelled or call completed, close this screen
                if (status == "completed" || status == "cancelled" || status == "rejected") {
                    stopRinging()
                    stopVibration()
                    finish()
                }
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRinging()
        stopVibration()
        pulseAnimatorSet?.cancel()
        callListener?.remove()
    }

    override fun onBackPressed() {
        // Prevent back press from dismissing the incoming call screen
        // User must accept or decline
    }
}
