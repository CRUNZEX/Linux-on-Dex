package com.crunzex.linuxondex.display

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.engine.runtime.VmPaths
import com.crunzex.linuxondex.ui.DisplayActivity

/**
 * Tells GPU desktop sessions when the native X11 viewer is on screen.
 *
 * The viewer's arrival resizes the X screen to its surface, and resizing
 * beneath a live compositor is the fragile step on GPU sessions: a Tab S9
 * report showed GNOME dying with SIGSEGV right around it, and the app-side
 * renderer can keep showing a stale frame afterwards. The in-image
 * supervisor therefore waits for a marker file before the *first* GNOME
 * start of a GPU session, so the resize lands on a bare root window and
 * the compositor starts at the final geometry.
 *
 * The marker is written twice over: on every viewer resume (covers the
 * user opening the display while the supervisor waits), and via
 * [markAttachedIfViewerOpen] when a session prepares its /tmp while the
 * display is already open (covers restarts behind an open viewer, which
 * must not sit out the grace period).
 */
class DisplayViewerPresence private constructor(
    private val appContext: Context,
) : Application.ActivityLifecycleCallbacks {

    override fun onActivityResumed(activity: Activity) {
        if (!activity.isLinuxDisplayWindow()) return
        viewerOnScreen = true
        writeAttachedMarker(appContext)
    }

    override fun onActivityPaused(activity: Activity) {
        if (!activity.isLinuxDisplayWindow()) return
        viewerOnScreen = false
    }

    /**
     * Both windows that can show the Linux screen count: the app's own
     * display activity (the in-app Display button) and the library viewer
     * activity. Writing the marker for a VNC session is harmless — only
     * the PRoot supervisor reads it.
     */
    private fun Activity.isLinuxDisplayWindow(): Boolean =
        this is DisplayActivity || this is com.termux.x11.MainActivity

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        private const val SCOPE = "DisplayViewerPresence"

        /** Name is protocol, shared with the rootfs builder's supervisor. */
        const val VIEWER_ATTACHED_MARKER_NAME = ".dex-viewer-attached"

        @Volatile
        private var viewerOnScreen = false

        fun install(application: Application) {
            application.registerActivityLifecycleCallbacks(
                DisplayViewerPresence(application.applicationContext)
            )
        }

        /** Pre-writes the marker for sessions starting behind an open viewer. */
        fun markAttachedIfViewerOpen(context: Context) {
            if (viewerOnScreen) writeAttachedMarker(context.applicationContext)
        }

        private fun writeAttachedMarker(context: Context) {
            runCatching {
                val guestTmpDir = VmPaths(context).prootGuestTmpDir
                if (guestTmpDir.isDirectory) {
                    guestTmpDir.resolve(VIEWER_ATTACHED_MARKER_NAME).writeText("")
                }
            }.onFailure { error ->
                AppLog.warn(SCOPE, "could not write the viewer-attached marker", error)
            }
        }
    }
}
