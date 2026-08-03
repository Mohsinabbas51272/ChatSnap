package com.example.chatsnap.media.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.OnBackPressedCallback
import com.example.chatsnap.BaseActivity
import com.example.chatsnap.utils.ThemeManager

class MediaHubActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        setContent {
            SnapchatTheme {
                MediaHubScreen(
                    onBackClick = { finish() }
                )
            }
        }
    }
}
