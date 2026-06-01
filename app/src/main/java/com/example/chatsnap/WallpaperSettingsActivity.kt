package com.example.chatsnap

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import coil.load
import com.example.chatsnap.databinding.ActivityWallpaperSettingsBinding
import com.example.chatsnap.utils.WallpaperManager

class WallpaperSettingsActivity : BaseActivity() {
    private lateinit var binding: ActivityWallpaperSettingsBinding

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data: Intent? = result.data
            data?.data?.let { uri ->
                try {
                    // Persist permission so the app can load the URI later
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                saveWallpaper(uri.toString())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWallpaperSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        loadCurrentWallpaper()

        binding.btnPickGallery.setOnClickListener {
            // Use ACTION_OPEN_DOCUMENT to allow persistable permissions for long-term access
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            }
            pickImageLauncher.launch(intent)
        }

        binding.btnRemoveWallpaper.setOnClickListener {
            WallpaperManager.setCustomWallpaper(this, null)
            loadCurrentWallpaper()
            Toast.makeText(this, "Wallpaper removed", Toast.LENGTH_SHORT).show()
        }

        binding.sliderOpacity.addOnChangeListener { _, value, _ ->
            binding.ivWallpaperPreview.alpha = value
            WallpaperManager.setWallpaperOpacity(this, value)
        }
    }

    private fun loadCurrentWallpaper() {
        val wallpaperUri = WallpaperManager.getCustomWallpaper(this)
        val opacity = WallpaperManager.getWallpaperOpacity(this)
        
        binding.sliderOpacity.value = opacity
        binding.ivWallpaperPreview.alpha = opacity

        if (!wallpaperUri.isNullOrEmpty()) {
            binding.ivWallpaperPreview.load(wallpaperUri)
        } else {
            binding.ivWallpaperPreview.setImageResource(android.R.color.darker_gray)
        }
    }

    private fun saveWallpaper(uri: String) {
        WallpaperManager.setCustomWallpaper(this, uri)
        loadCurrentWallpaper()
        Toast.makeText(this, "Wallpaper updated", Toast.LENGTH_SHORT).show()
    }
}
