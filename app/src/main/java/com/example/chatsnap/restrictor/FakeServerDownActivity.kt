package com.example.chatsnap.restrictor

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.chatsnap.databinding.ActivityFakeServerDownBinding

class FakeServerDownActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFakeServerDownBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFakeServerDownBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AppRestrictionManager.isBlockScreenActive = true

        // User clicks OK -> send to Home Screen so the app is inaccessible
        binding.btnOk.setOnClickListener {
            exitToHomeScreen()
        }

        // Secret backdoor for device owner: long click on the error icon to bypass
        binding.flIconContainer.setOnLongClickListener {
            Toast.makeText(this, "Disguise bypassed", Toast.LENGTH_SHORT).show()
            AppRestrictionManager.isBlockScreenActive = false
            finish()
            true
        }

        // Intercept back button to also redirect to Home Screen
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitToHomeScreen()
            }
        })
    }

    private fun exitToHomeScreen() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(homeIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            AppRestrictionManager.isBlockScreenActive = false
            finishAffinity()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppRestrictionManager.isBlockScreenActive = false
    }
}
