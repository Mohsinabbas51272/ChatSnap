package com.example.chatsnap

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.chatsnap.databinding.ActivityQrScannerBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult

class QRScannerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityQrScannerBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            binding.barcodeScanner.resume()
        } else {
            Toast.makeText(this, "Camera permission is required to scan QR codes", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        checkCameraPermission()

        binding.barcodeScanner.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                result?.text?.let {
                    binding.barcodeScanner.pause()
                    handleScannedCode(it)
                }
            }
        })

        binding.btnManualEntry.setOnClickListener {
            showManualEntryDialog()
        }

        binding.btnBack.setOnClickListener { finish() }
    }

    private fun handleScannedCode(code: String) {
        val myUid = auth.currentUser?.uid ?: return
        if (code == myUid) {
            Toast.makeText(this, "That's your own QR code!", Toast.LENGTH_SHORT).show()
            binding.barcodeScanner.resume()
            return
        }

        firestore.collection("users").document(code).get().addOnSuccessListener { doc ->
            if (doc.exists()) {
                val name = doc.getString("name") ?: "User"
                sendRequest(code, name)
            } else {
                Toast.makeText(this, "Invalid User QR Code", Toast.LENGTH_SHORT).show()
                binding.barcodeScanner.resume()
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Error: ${it.message}", Toast.LENGTH_SHORT).show()
            binding.barcodeScanner.resume()
        }
    }

    private fun sendRequest(targetUid: String, targetName: String) {
        val myUid = auth.currentUser?.uid ?: return
        val requestId = "${myUid}_$targetUid"
        
        val request = hashMapOf(
            "requestId" to requestId,
            "fromId" to myUid,
            "toId" to targetUid,
            "status" to "PENDING",
            "source" to "QR Scanner",
            "timestamp" to com.google.firebase.Timestamp.now()
        )

        firestore.collection("friendRequests").document(requestId).set(request)
            .addOnSuccessListener {
                Toast.makeText(this, "Friend request sent to $targetName", Toast.LENGTH_LONG).show()
                finish()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to send request", Toast.LENGTH_SHORT).show()
                binding.barcodeScanner.resume()
            }
    }

    private fun showManualEntryDialog() {
        val input = EditText(this)
        input.hint = "Paste friend code here"
        
        AlertDialog.Builder(this)
            .setTitle("Manual Entry")
            .setMessage("Enter the unique friend code share with you.")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isNotEmpty()) {
                    handleScannedCode(code)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkCameraPermission() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            binding.barcodeScanner.resume()
        } else {
            requestPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.barcodeScanner.resume()
    }

    override fun onPause() {
        super.onPause()
        binding.barcodeScanner.pause()
    }
}
