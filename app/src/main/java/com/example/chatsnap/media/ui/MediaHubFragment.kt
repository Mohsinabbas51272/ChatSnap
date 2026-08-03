package com.example.chatsnap.media.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment

class MediaHubFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                SnapchatTheme {
                    MediaHubScreen(
                        onBackClick = {
                            activity?.onBackPressedDispatcher?.onBackPressed()
                        }
                    )
                }
            }
        }
    }
}
