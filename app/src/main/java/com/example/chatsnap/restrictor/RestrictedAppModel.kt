package com.example.chatsnap.restrictor

import android.graphics.drawable.Drawable

data class RestrictedAppModel(
    val appName: String,
    val packageName: String,
    val icon: Drawable,
    var isRestricted: Boolean
)
