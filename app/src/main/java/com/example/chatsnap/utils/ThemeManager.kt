package com.example.chatsnap.utils

import android.content.Context
import com.example.chatsnap.R

object ThemeManager {
    private const val PREF_NAME = "theme_prefs"
    private const val KEY_THEME = "selected_theme"

    enum class AppTheme(val styleRes: Int, val displayName: String, val price: Int) {
        DEFAULT(R.style.Theme_ChatSnap, "Electric Purple (Default)", 0),
        FULL_BLACK(R.style.Theme_ChatSnap_FullBlack, "Full Black (Dark)", 0),
        FULL_WHITE(R.style.Theme_ChatSnap_FullWhite, "Full White (Light)", 0),
        EMERALD_GREEN(R.style.Theme_ChatSnap_EmeraldGreen, "Sage & Mint (Light)", 0),
        SAKURA_PINK(R.style.Theme_ChatSnap_SakuraPink, "Sakura Rose (Light)", 0),
        NEON_PINK(R.style.Theme_ChatSnap_NeonPink, "Deep Pink (Light)", 0),
        MAROON(R.style.Theme_ChatSnap_Maroon, "Maroon (Light)", 0),
        MAGENTA(R.style.Theme_ChatSnap_Magenta, "Ocean Sky (Light)", 0),
        TEAL(R.style.Theme_ChatSnap_Teal, "Teal Ice (Light)", 0),
        CORAL(R.style.Theme_ChatSnap_Coral, "Red (Light)", 0)
    }

    fun setTheme(context: Context, theme: AppTheme) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME, theme.name).apply()
    }

    fun getTheme(context: Context): AppTheme {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val themeName = prefs.getString(KEY_THEME, AppTheme.DEFAULT.name)
        return try {
            AppTheme.valueOf(themeName ?: AppTheme.DEFAULT.name)
        } catch (e: Exception) {
            AppTheme.DEFAULT
        }
    }

    fun applyTheme(context: android.app.Activity) {
        val theme = getTheme(context)
        context.setTheme(theme.styleRes)
    }

    fun syncThemeToFirestore(context: Context, theme: AppTheme) {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .update("selectedTheme", theme.name)
    }

    fun syncThemeFromFirestore(context: android.app.Activity, onThemeChanged: () -> Unit) {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .get()
            .addOnSuccessListener { doc ->
                val themeName = doc.getString("selectedTheme")
                if (themeName != null) {
                    try {
                        val theme = AppTheme.valueOf(themeName)
                        if (theme != getTheme(context)) {
                            setTheme(context, theme)
                            onThemeChanged()
                        }
                    } catch (e: Exception) {}
                }
            }
    }
}
