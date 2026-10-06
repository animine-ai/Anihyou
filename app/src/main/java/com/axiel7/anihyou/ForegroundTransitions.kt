package com.axiel7.anihyou

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Reports the moment the app really comes to the foreground: the first started activity after the app was
 * in the background. A configuration change (rotation) stops and restarts an activity within a moment;
 * that is debounced and does not count, so it never triggers a refresh.
 */
internal class ForegroundTransitions(
    private val debounceMillis: Long = 700L,
    private val postDelayed: (Runnable, Long) -> Unit = MAIN_POST,
    private val cancel: (Runnable) -> Unit = MAIN_CANCEL,
    private val onForeground: () -> Unit,
) {
    private var started = 0
    private var inBackground = true
    private val markBackground = Runnable { if (started == 0) inBackground = true }

    fun activityStarted() {
        started++
        cancel(markBackground)
        if (inBackground) {
            inBackground = false
            onForeground()
        }
    }

    fun activityStopped() {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) postDelayed(markBackground, debounceMillis)
    }

    /** The application hook; the logic above has no Android types and is tested directly. */
    fun callbacks(): Application.ActivityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) = activityStarted()
        override fun onActivityStopped(activity: Activity) = activityStopped()
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private companion object {
        val MAIN_POST: (Runnable, Long) -> Unit = { runnable, delay -> Handler(Looper.getMainLooper()).postDelayed(runnable, delay) }
        val MAIN_CANCEL: (Runnable) -> Unit = { runnable -> Handler(Looper.getMainLooper()).removeCallbacks(runnable) }
    }
}
