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
    private val appId = "fe650d5f0f0148dca03f84b4ec6f83e0"
    private var channelName: String? = null
    private var mRtcEngine: RtcEngine? = null
    private var isMuted = false
    private var isVideoDisabled = false
    private lateinit var auth: com.google.firebase.auth.FirebaseAuth

    private val mRtcEventHandler = object : IRtcEngineEventHandler() {
        override fun onUserJoined(uid: Int, elapsed: Int) {
            runOnUiThread {
                setupRemoteVideo(uid)
                binding.tvCallStatus.text = "Connected"
            }
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            runOnUiThread {
                binding.remoteVideoViewContainer.removeAllViews()
                binding.tvCallStatus.text = "User Offline"
                finish()
            }
        }

        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            runOnUiThread {
                binding.tvCallStatus.text = "Waiting for partner..."
                com.example.chatsnap.utils.TaskUtils.markTaskAsDone("TASK_CALL")
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
            val config = RtcEngineConfig()
            config.mContext = baseContext
            config.mAppId = appId
            config.mEventHandler = mRtcEventHandler
            mRtcEngine = RtcEngine.create(config)

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

            mRtcEngine?.joinChannel(null, channelName, 0, ChannelMediaOptions())
        } catch (e: Exception) {
            Toast.makeText(this, "Engine Init Failed", Toast.LENGTH_SHORT).show()
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
        mRtcEngine?.stopPreview()
        mRtcEngine?.leaveChannel()
        RtcEngine.destroy()
        
        // Mark call as completed if it was the initiator
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
