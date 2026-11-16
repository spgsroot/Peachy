package ru.spgsroot.peachy.security

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.IdentityHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ends the unlocked session when the app really leaves the foreground.
 *
 * Moving between activities of this app (the embedded browser, the share receiver) keeps the
 * session alive, and so does a document or photo picker: its result belongs to the screen that
 * opened it. A pending picker keeps the session unlocked only while its result can still arrive;
 * the app's UI becoming hidden (Home, Recents, another app, the screen going off) or coming back
 * without the result locks instead. Every other way out locks at once, and only the root screen
 * survives, so coming back to a locked app always asks for the password again.
 */
@Singleton
class AppLockGuard @Inject constructor(
    private val authSession: AuthSession
) : Application.ActivityLifecycleCallbacks {

    private val started: MutableSet<Activity> =
        Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
    private val roots: MutableSet<Activity> =
        Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
    private val pickerRequests = mutableSetOf<Int>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var returnedWithoutResult = false
    private var rootActivity: Class<out Activity>? = null

    private val lockWhenResultMissing = Runnable {
        if (!returnedWithoutResult || pickerRequests.isEmpty()) return@Runnable
        returnedWithoutResult = false
        pickerRequests.clear()
        authSession.lockIfProtected()
    }

    fun install(application: Application, root: Class<out Activity>) {
        rootActivity = root
        application.registerActivityLifecycleCallbacks(this)
    }

    /** A picker was opened for [requestCode]; its result keeps the app inside its own flow. */
    fun pickerLaunched(requestCode: Int) {
        pickerRequests.add(requestCode)
    }

    fun pickerResult(requestCode: Int) {
        if (!pickerRequests.remove(requestCode)) return
        if (pickerRequests.isEmpty()) {
            returnedWithoutResult = false
            mainHandler.removeCallbacks(lockWhenResultMissing)
        }
    }

    /** The root screen is visible again: a picker left behind must not become a way in. */
    fun rootResumed(recreated: Boolean) {
        if (!returnedWithoutResult) return
        if (pickerRequests.isEmpty()) {
            returnedWithoutResult = false
            return
        }
        if (recreated) {
            // A recreated activity can receive the pending result just after onResume.
            mainHandler.postDelayed(lockWhenResultMissing, RESULT_GRACE_MS)
            return
        }
        returnedWithoutResult = false
        pickerRequests.clear()
        authSession.lockIfProtected()
    }

    override fun onActivityStarted(activity: Activity) {
        started.add(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        started.remove(activity)
        if (started.isNotEmpty()) return
        if (activity.isChangingConfigurations) return
        // A finished non-root screen hands control back to the root below it — unless that root
        // was destroyed while the screen was open, which leaves the app with nothing on screen.
        if (!isRoot(activity) && activity.isFinishing && roots.isNotEmpty()) return
        if (pickerRequests.isNotEmpty()) {
            returnedWithoutResult = true
            return
        }
        if (authSession.lockIfProtected() && !isRoot(activity)) activity.finish()
    }

    override fun onActivityDestroyed(activity: Activity) {
        started.remove(activity)
        roots.remove(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (isRoot(activity)) roots.add(activity)
    }

    override fun onActivityResumed(activity: Activity) {}

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    private fun isRoot(activity: Activity): Boolean = rootActivity?.isInstance(activity) == true

    private companion object {
        /** Leeway for a result delivered right after a recreated activity resumes. */
        const val RESULT_GRACE_MS = 250L
    }
}
