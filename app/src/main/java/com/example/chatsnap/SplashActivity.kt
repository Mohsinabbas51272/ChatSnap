package com.example.chatsnap

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class SplashActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val logoContainer = findViewById<android.view.View>(R.id.logoContainer)
        logoContainer.alpha = 0f
        logoContainer.scaleX = 0.8f
        logoContainer.scaleY = 0.8f
        
        logoContainer.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(1000)
            .setInterpolator(android.view.animation.OvershootInterpolator())
            .start()

        val auth = FirebaseAuth.getInstance()
        val db = FirebaseFirestore.getInstance()
        
        Handler(Looper.getMainLooper()).postDelayed({
            val user = auth.currentUser
            if (user != null) {
                db.collection("users").document(user.uid).get().addOnSuccessListener { doc ->
                    val lockCode = doc.getString("appLockCode")
                    if (!lockCode.isNullOrEmpty()) {
                        val intent = Intent(this, AppLockActivity::class.java)
                        intent.putExtra("EXPECTED_CODE", lockCode)
                        startActivity(intent)
                    } else {
                        startActivity(Intent(this, MainActivity::class.java))
                    }
                    finish()
                }.addOnFailureListener {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            } else {
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
        }, 500)
    }
}