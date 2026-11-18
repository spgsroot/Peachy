package ru.spgsroot.peachy

import android.view.animation.AnticipateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.core.splashscreen.SplashScreen

private const val POP_MS = 380L
private const val POP_SCALE = 1.35f

/**
 * The brand icon draws back a little, then pops towards the viewer while the splash fades into
 * the app. It runs once the app is ready, so it is visible on every launch: an animation inside
 * the splash icon would already be over while the launch transition is still revealing the
 * window. Works the same on every API level the splash library supports.
 */
fun SplashScreen.popBrandOnExit() {
    setOnExitAnimationListener { splash ->
        splash.iconView.animate()
            .scaleX(POP_SCALE)
            .scaleY(POP_SCALE)
            .alpha(0f)
            .setDuration(POP_MS)
            .setInterpolator(AnticipateInterpolator(2f))
            .start()
        splash.view.animate()
            .alpha(0f)
            .setStartDelay(POP_MS / 3)
            .setDuration(POP_MS - POP_MS / 3)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { splash.remove() }
            .start()
    }
}
