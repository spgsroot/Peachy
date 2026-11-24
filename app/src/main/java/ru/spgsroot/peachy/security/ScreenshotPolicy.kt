package ru.spgsroot.peachy.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.view.Window
import android.view.WindowManager

/** Release builds keep FLAG_SECURE. A debuggable build does not set it, and does not clear anyone else's. */
fun screenshotsBlocked(debuggable: Boolean): Boolean = !debuggable

fun Window.applyScreenshotPolicy(context: Context) {
    val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    if (screenshotsBlocked(debuggable)) {
        setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }
}
