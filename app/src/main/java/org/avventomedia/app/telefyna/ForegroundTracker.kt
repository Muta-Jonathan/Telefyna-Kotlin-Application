package org.avventomedia.app.telefyna

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

object ForegroundTracker : Application.ActivityLifecycleCallbacks {
    private var started = 0
    private var stopped = 0
    private var topActivityRef: WeakReference<Activity>? = null

    val isInForeground: Boolean
        get() = started > stopped

    val topActivity: Activity?
        get() = topActivityRef?.get()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {
        started++
        topActivityRef = WeakReference(activity)
    }
    override fun onActivityResumed(activity: Activity) {
        topActivityRef = WeakReference(activity)
    }
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) { stopped++ }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        if (topActivityRef?.get() === activity) {
            topActivityRef = null
        }
    }
}
