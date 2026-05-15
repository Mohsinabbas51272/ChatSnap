package com.example.chatsnap.utils

import android.content.Context
import android.graphics.Color
import coil.load

object WallpaperManager {
    private const val PREF_NAME = "wallpaper_prefs"
    private const val KEY_WALLPAPER = "selected_wallpaper"

    enum class ChatWallpaper(val colorCode: String, val label: String) {
        DEFAULT("#f9f9ff", "Default"),
        NIGHT_SKY("#1A1A2E", "Night Sky"),
        FOREST("#0D2B22", "Forest Deep"),
        SUNSET("#FFDEDE", "Warm Sunset"),
        LAVENDER("#F3E5F5", "Lavender Mist"),
        OCEAN("#E0F7FA", "Ocean Breeze")
    }

    private const val KEY_CUSTOM_URI = "custom_wallpaper_uri"
    private const val KEY_OPACITY = "wallpaper_opacity"

    fun setWallpaper(context: Context, wallpaper: ChatWallpaper) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_WALLPAPER, wallpaper.name).apply()
    }

    fun getWallpaper(context: Context): ChatWallpaper {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_WALLPAPER, ChatWallpaper.DEFAULT.name)
        return try {
            ChatWallpaper.valueOf(name ?: ChatWallpaper.DEFAULT.name)
        } catch (e: Exception) {
            ChatWallpaper.DEFAULT
        }
    }

    fun setCustomWallpaper(context: Context, uri: String?) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_URI, uri).apply()
    }

    fun getCustomWallpaper(context: Context): String? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CUSTOM_URI, null)
    }

    fun setWallpaperOpacity(context: Context, opacity: Float) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(KEY_OPACITY, opacity).apply()
    }

    fun getWallpaperOpacity(context: Context): Float {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_OPACITY, 1.0f)
    }

    fun applyWallpaper(view: android.view.View) {
        val context = view.context
        val customUri = getCustomWallpaper(context)
        
        if (!customUri.isNullOrEmpty()) {
            if (view is android.widget.ImageView) {
                view.load(customUri)
                view.alpha = getWallpaperOpacity(context)
                view.setBackgroundColor(Color.TRANSPARENT)
            } else {
                // Background for non-image views (like chat containers)
                // Use a very light/dark overlay of the theme or just the image if possible
                val typedValue = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.windowBackground, typedValue, true)
                view.setBackgroundColor(typedValue.data)
            }
        } else {
            // Theme Sync Logic
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
            val primaryColor = typedValue.data
            
            // Create a very faint version of the primary color for the background
            val alphaColor = android.graphics.Color.argb(
                40, // Low alpha for subtle look
                android.graphics.Color.red(primaryColor),
                android.graphics.Color.green(primaryColor),
                android.graphics.Color.blue(primaryColor)
            )
            
            view.setBackgroundColor(alphaColor)
            if (view is android.widget.ImageView) view.setImageDrawable(null)
            view.alpha = 1.0f
        }
    }
}
