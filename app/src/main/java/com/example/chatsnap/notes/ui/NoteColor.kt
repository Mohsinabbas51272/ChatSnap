package com.example.chatsnap.notes.ui

import android.graphics.Color

data class NoteColor(
    val name: String,
    val hexColorLight: String,
    val hexColorDark: String,
    val displayColor: String // Selector dots color
) {
    fun getBackgroundColor(isDarkMode: Boolean): Int {
        return Color.parseColor(if (isDarkMode) hexColorDark else hexColorLight)
    }

    companion object {
        val COLORS = listOf(
            NoteColor("Yellow", "#FFFCE6", "#2D2A1C", "#FFD700"), // Warm premium yellow
            NoteColor("Blue", "#F0F8FF", "#1B2A3A", "#4A90E2"),
            NoteColor("Green", "#F2FFF4", "#1C2E21", "#50C878"),
            NoteColor("Pink", "#FFF0F5", "#351B27", "#FF69B4"),
            NoteColor("Orange", "#FFF7F0", "#33241A", "#FF7F50"),
            NoteColor("Purple", "#FAF6FE", "#291A30", "#BA55D3"),
            NoteColor("Gray", "#F5F6F8", "#242526", "#8E8E93")
        )

        fun getColorByName(name: String): NoteColor {
            return COLORS.find { it.name.equals(name, ignoreCase = true) } ?: COLORS[0]
        }
    }
}
