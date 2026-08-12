package com.crunzex.linuxondex.display.settings

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.PowerManager
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.ui.DisplayActivity

/**
 * Applies the "Consistent performance mode" setting to every window that
 * shows the Linux screen.
 *
 * Sustained-performance mode is a window-level request, and the native X11
 * window belongs to a library activity whose code the app does not own — so
 * the request is applied from lifecycle callbacks instead of from inside
 * each activity. Re-reading the preference on every resume also makes the
 * toggle take effect the next time the user returns to the display, without
 * a restart.
 */
class DisplayWindowPerformance private constructor(
    private val preferences: DisplaySessionPreferences,
) : Application.ActivityLifecycleCallbacks {

    override fun onActivityResumed(activity: Activity) {
        if (!activity.isLinuxDisplayWindow()) return
        val wanted = preferences.sustainedPerformance &&
            activity.supportsSustainedPerformance()
        runCatching {
            activity.window.setSustainedPerformanceMode(wanted)
        }.onFailure { error ->
            AppLog.warn(SCOPE, "could not apply sustained performance mode", error)
        }
    }

    private fun Activity.isLinuxDisplayWindow(): Boolean =
        this is DisplayActivity || this is com.termux.x11.MainActivity

    private fun Activity.supportsSustainedPerformance(): Boolean = runCatching {
        getSystemService(PowerManager::class.java)
            ?.isSustainedPerformanceModeSupported == true
    }.getOrDefault(false)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        private const val SCOPE = "DisplayWindowPerformance"

        fun install(application: Application) {
            application.registerActivityLifecycleCallbacks(
                DisplayWindowPerformance(DisplaySessionPreferences(application))
            )
        }
    }
}
