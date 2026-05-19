package com.example.chatsnap

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.chatsnap.databinding.ActivityCallBinding
import io.agora.rtc2.*
import io.agora.rtc2.video.VideoEncoderConfiguration
import io.agora.rtc2.video.VideoCanvas

class CallActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCallBinding
    private val appId = "46de926758a6412c86529258fde19b4a"
    private var channelName: String? = null
    private var mRtcEngine: RtcEngine? = null
    private var isMuted = false
    private var isVideoDisabled = false
    private lateinit var auth: com.google.firebase.auth.FirebaseAuth

    private val mRtcEventHandler = object : IRtcEngineEventHandler() {
        override fun onUserJoined(uid: Int, elapsed: Int) {
            runOnUiThread {
                android.util.Log.d("AGORA_CALL", "Remote user joined: uid=$uid")
                setupRemoteVideo(uid)
                binding.tvCallStatus.text = "Connected"
            }
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            runOnUiThread {
                android.util.Log.d("AGORA_CALL", "Remote user offline: uid=$uid reason=$reason")
                binding.remoteVideoViewContainer.removeAllViews()
                binding.tvCallStatus.text = "User Offline"
                finish()
            }
        }

        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            runOnUiThread {
                android.util.Log.d("AGORA_CALL", "✅ Joined channel '$channel' successfully! My uid=$uid")
                binding.tvCallStatus.text = "Waiting for partner..."
                com.example.chatsnap.utils.TaskUtils.markTaskAsDone("TASK_CALL")
            }
        }

        override fun onError(err: Int) {
            runOnUiThread {
                android.util.Log.e("AGORA_CALL", "❌ Agora Error Code: $err")
                val errorMsg = when (err) {
                    1 -> "General Error"
                    2 -> "Invalid Argument"
                    3 -> "SDK Not Ready"
                    4 -> "SDK Not Supported"
                    5 -> "Request Rejected"
                    7 -> "SDK Not Initialized"
                    9 -> "No Permission"
                    10 -> "API Call Timeout"
                    17 -> "Join Channel Rejected"
                    101 -> "Invalid App ID — Check your Agora App ID"
                    102 -> "Invalid Channel Name"
                    109 -> "Token Expired"
                    110 -> "Invalid Token — Go to console.agora.io → Your Project → Set Security to 'APP ID only (Testing Mode)'"
                    111 -> "Connection Interrupted"
                    112 -> "Connection Lost"
                    120 -> "Banned By Server"
                    123 -> "Channel Banned"
                    else -> "Error #$err"
                }
                android.util.Log.e("AGORA_CALL", "❌ Error: $errorMsg")
                binding.tvCallStatus.text = "Error: $errorMsg"
                Toast.makeText(this@CallActivity, "Call Error: $errorMsg", Toast.LENGTH_LONG).show()
            }
        }

        override fun onConnectionStateChanged(state: Int, reason: Int) {
            runOnUiThread {
                val stateStr = when (state) {
                    1 -> "Disconnected"
                    2 -> "Connecting..."
                    3 -> "Connected"
                    4 -> "Reconnecting..."
                    5 -> "Failed"
                    else -> "State $state"
                }
                android.util.Log.d("AGORA_CALL", "Connection: $stateStr (reason=$reason)")
                if (state == 5) {
                    binding.tvCallStatus.text = "Connection Failed"
                    Toast.makeText(this@CallActivity, "Connection failed. Check your Agora project settings.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private var callListener: com.google.firebase.firestore.ListenerRegistration? = null

    private fun listenToCallStatus() {
        val id = currentCallId ?: return
        callListener = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("calls").document(id)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                val status = snapshot.getString("status")
                if (status == "rejected") {
                    Toast.makeText(this, "Call Declined", Toast.LENGTH_SHORT).show()
                    finish()
                } else if (status == "completed") {
                    Toast.makeText(this, "Call Ended", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
    }

    private fun logCallToFirestore() {
        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        val callId = db.collection("calls").document().id
        currentCallId = callId
        
        val logData = hashMapOf(
            "id" to callId,
            "callerId" to auth.uid,
            "callerName" to intent.getStringExtra("callerName"),
            "receiverId" to intent.getStringExtra("receiverId"),
            "receiverName" to intent.getStringExtra("receiverName"),
            "type" to intent.getStringExtra("callType"),
            "status" to "pending",
            "timestamp" to System.currentTimeMillis(),
            "channelName" to channelName
        )
        
        db.collection("calls").document(callId).set(logData)
            .addOnSuccessListener {
                listenToCallStatus()
                val receiverId = intent.getStringExtra("receiverId")
                val callerName = intent.getStringExtra("callerName") ?: "Someone"
                val callType = intent.getStringExtra("callType") ?: "Voice"
                if (receiverId != null) {
                    com.example.chatsnap.utils.FcmNotificationSender.sendNotification(
                        receiverId = receiverId,
                        senderName = callerName,
                        messageContent = "Incoming $callType Call... 📞",
                        chatId = channelName ?: "",
                        type = "CALL"
                    )
                }
            }
        // Mark task as done for the caller immediately
        com.example.chatsnap.utils.TaskUtils.markTaskAsDone("TASK_CALL")
    }

    private var currentCallId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        auth = com.google.firebase.auth.FirebaseAuth.getInstance()

        val callType = intent.getStringExtra("callType") ?: "Voice"
        val receiverName = intent.getStringExtra("receiverName") ?: "Unknown"
        channelName = intent.getStringExtra("channelName")

        android.util.Log.d("AGORA_CALL", "=== CALL ACTIVITY STARTED ===")
        android.util.Log.d("AGORA_CALL", "App ID: $appId")
        android.util.Log.d("AGORA_CALL", "Channel: $channelName")
        android.util.Log.d("AGORA_CALL", "Call Type: $callType")
        android.util.Log.d("AGORA_CALL", "isCaller: ${intent.getBooleanExtra("isCaller", false)}")

        binding.tvCallType.text = "$callType Call"
        binding.tvCallerName.text = receiverName

        if (checkPermissions()) {
            initializeAndJoinChannel(callType == "Video")
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA), 22)
        }

        // Log call immediately if I'm the caller
        if (intent.getBooleanExtra("isCaller", false)) {
            logCallToFirestore()
        } else {
            currentCallId = intent.getStringExtra("callId")
            listenToCallStatus()
        }

        binding.btnEndCall.setOnClickListener {
            finish()
        }

        binding.btnMute.setOnClickListener {
            isMuted = !isMuted
            mRtcEngine?.muteLocalAudioStream(isMuted)
            binding.btnMute.alpha = if (isMuted) 0.5f else 1f
        }

        binding.btnToggleCamera.setOnClickListener {
            isVideoDisabled = !isVideoDisabled
            mRtcEngine?.muteLocalVideoStream(isVideoDisabled)
            binding.localVideoViewContainer.visibility = if (isVideoDisabled) View.GONE else View.VISIBLE
            binding.btnToggleCamera.alpha = if (isVideoDisabled) 0.5f else 1f
        }

        binding.btnSwitchCamera.setOnClickListener {
            mRtcEngine?.switchCamera()
        }
    }

    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun initializeAndJoinChannel(isVideo: Boolean) {
        try {
            android.util.Log.d("AGORA_CALL", "Initializing Agora RTC Engine...")

            val config = RtcEngineConfig()
            config.mContext = baseContext
            config.mAppId = appId
            config.mEventHandler = mRtcEventHandler
            mRtcEngine = RtcEngine.create(config)

            android.util.Log.d("AGORA_CALL", "RTC Engine created successfully")

            // CRITICAL: Always enable audio for both voice and video calls
            mRtcEngine?.enableAudio()
            mRtcEngine?.setDefaultAudioRoutetoSpeakerphone(true)

            if (isVideo) {
                mRtcEngine?.enableVideo()
                mRtcEngine?.setVideoEncoderConfiguration(VideoEncoderConfiguration(
                    VideoEncoderConfiguration.VD_640x360,
                    VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_15,
                    VideoEncoderConfiguration.STANDARD_BITRATE,
                    VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_FIXED_PORTRAIT
                ))
                setupLocalVideo()
                binding.ivCallerProfile.visibility = View.GONE
            } else {
                mRtcEngine?.disableVideo()
                binding.localVideoViewContainer.visibility = View.GONE
                binding.remoteVideoViewContainer.visibility = View.GONE
                binding.btnToggleCamera.visibility = View.GONE
                binding.btnSwitchCamera.visibility = View.GONE
            }

            val options = ChannelMediaOptions().apply {
                channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
                clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
                publishMicrophoneTrack = true
                publishCameraTrack = isVideo
                autoSubscribeAudio = true
                autoSubscribeVideo = isVideo
            }

            android.util.Log.d("AGORA_CALL", "Joining channel: '$channelName' with token=null (APP ID mode)")
            val result = mRtcEngine?.joinChannel(null, channelName, 0, options)
            android.util.Log.d("AGORA_CALL", "joinChannel result: $result (0 = success, negative = error)")

            if (result != null && result < 0) {
                Toast.makeText(this, "Failed to join channel (error: $result)", Toast.LENGTH_LONG).show()
                binding.tvCallStatus.text = "Join Failed: $result"
            }

        } catch (e: Exception) {
            android.util.Log.e("AGORA_CALL", "Engine init FAILED: ${e.message}", e)
            Toast.makeText(this, "Engine Init Failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupLocalVideo() {
        val container = binding.localVideoViewContainer
        val surfaceView = android.view.SurfaceView(baseContext)
        surfaceView.setZOrderMediaOverlay(true)
        container.addView(surfaceView)
        mRtcEngine?.setupLocalVideo(VideoCanvas(surfaceView, VideoCanvas.RENDER_MODE_HIDDEN, 0))
    }

    private fun setupRemoteVideo(uid: Int) {
        val container = binding.remoteVideoViewContainer
        if (container.childCount >= 1) return
        val surfaceView = android.view.SurfaceView(baseContext)
        container.addView(surfaceView)
        mRtcEngine?.setupRemoteVideo(VideoCanvas(surfaceView, VideoCanvas.RENDER_MODE_HIDDEN, uid))
    }

    override fun onDestroy() {
        super.onDestroy()
        callListener?.remove()
        mRtcEngine?.stopPreview()
        mRtcEngine?.leaveChannel()
        RtcEngine.destroy()
        
        // Mark call as completed in database
        currentCallId?.let { id ->
            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("calls").document(id).update("status", "completed")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 22 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initializeAndJoinChannel(intent.getStringExtra("callType") == "Video")
        }
    }
}
