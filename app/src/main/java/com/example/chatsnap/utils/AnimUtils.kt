package com.example.chatsnap.utils

import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

object AnimUtils {
    fun fadeInAndSlideUp(view: View, delay: Long = 0) {
        view.alpha = 0f
        view.translationY = 50f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(300)
            .setStartDelay(delay)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    fun pulse(view: View) {
        view.animate()
            .scaleX(1.1f)
            .scaleY(1.1f)
            .setDuration(200)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(200)
                    .start()
            }
            .start()
    }

    fun animateStaggered(views: List<View>, startDelay: Long = 0) {
        views.forEachIndexed { index, view ->
            fadeInAndSlideUp(view, startDelay + (index * 30))
        }
    }

    fun flip(view: View, onFlip: () -> Unit) {
        view.animate()
            .rotationY(90f)
            .setDuration(250)
            .withEndAction {
                onFlip()
                view.rotationY = -90f
                view.animate()
                    .rotationY(0f)
                    .setDuration(250)
                    .start()
            }
            .start()
    }
}
