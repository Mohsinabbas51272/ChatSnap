package com.example.chatsnap

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.chatsnap.databinding.ActivityQrProfileBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder

class QRProfileActivity : BaseActivity() {
    private lateinit var binding: ActivityQrProfileBinding
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
        
        if (user == null) {
            finish()
            return
        }

        binding.tvUserName.text = user.displayName ?: "User"
        binding.tvFriendCode.text = user.uid

        try {
            val barcodeEncoder = BarcodeEncoder()
            val bitmap: Bitmap = barcodeEncoder.encodeBitmap(user.uid, BarcodeFormat.QR_CODE, 512, 512)
            binding.ivQrCode.setImageBitmap(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnCopyCode.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = android.content.ClipData.newPlainText("Friend Code", user.uid)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Friend code copied!", Toast.LENGTH_SHORT).show()
        }

        binding.btnShare.setOnClickListener {
            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND)
            shareIntent.type = "text/plain"
            shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, "Add me on ChatSnap! My Friend Code: ${user.uid}")
            startActivity(android.content.Intent.createChooser(shareIntent, "Share via"))
        }
    }
}
