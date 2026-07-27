package com.example.chatsnap

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import coil.load
import com.example.chatsnap.databinding.ActivityMediaViewerBinding

class MediaViewerActivity : BaseActivity() {
    private lateinit var binding: ActivityMediaViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = intent.getStringExtra("mediaUrl") ?: ""
        val type = intent.getStringExtra("mediaType") ?: "IMAGE"
        val isSnap = type == "SNAP"

        if (isSnap) {
            binding.btnDownload.visibility = View.GONE
            Toast.makeText(this, "One-time view Snap", Toast.LENGTH_SHORT).show()
        }

        if (type == "VIDEO") {
            binding.ivFullMedia.visibility = View.GONE
            binding.vvFullMedia.visibility = View.VISIBLE
            if (url.startsWith("data:video") || (url.length > 500 && !url.startsWith("http"))) {
                try {
                    val clean = if (url.contains(",")) url.substringAfter(",") else url
                    val bytes = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
                    val tmp = java.io.File.createTempFile("chat_video", ".mp4", cacheDir)
                    java.io.FileOutputStream(tmp).use { it.write(bytes) }
                    binding.vvFullMedia.setVideoPath(tmp.absolutePath)
                } catch (e: Exception) {
                    Toast.makeText(this, "Error playing video", Toast.LENGTH_SHORT).show()
                }
            } else {
                binding.vvFullMedia.setVideoURI(android.net.Uri.parse(url))
            }
            binding.vvFullMedia.start()
        } else {
            binding.ivFullMedia.visibility = View.VISIBLE
            binding.vvFullMedia.visibility = View.GONE
            
            if (url.startsWith("data:image")) {
                try {
                    val cleanBase64 = url.substringAfter(",")
                    val decodedString = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                    binding.ivFullMedia.setImageBitmap(bitmap)
                } catch (e: Exception) {
                    Toast.makeText(this, "Error loading image", Toast.LENGTH_SHORT).show()
                }
            } else {
                binding.ivFullMedia.load(url)
            }
        }

        binding.btnClose.setOnClickListener { finish() }
        binding.btnDownload.setOnClickListener {
            Toast.makeText(this, "Downloading to gallery...", Toast.LENGTH_SHORT).show()
            // Implementation for download could be added here
        }
    }
}
