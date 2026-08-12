package com.crunzex.linuxondex.engine.proot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.crunzex.linuxondex.core.LxdError
import com.crunzex.linuxondex.display.settings.DisplaySessionPreferences
import com.crunzex.linuxondex.engine.runtime.VmPaths
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Owns the embedded X server independently of the PRoot guest processes. */
interface NativeX11Server {
    /**
     * [guestTmpDir] is the short host directory the session binds over the
     * guest's /tmp; the X server publishes its display socket inside it.
     * [rootfsDir] is only read for keyboard (XKB) data.
     */
    fun start(rootfsDir: File, guestTmpDir: File, displayNumber: Int)
    fun isAlive(): Boolean
    fun diagnosticStatus(): String
    fun stop()
}

/**
 * Keeps X11 in an Android-managed process bound to the foreground VM runtime.
 *
 * Directly executing `app_process` creates a phantom child. Android 12 and
 * newer may SIGKILL those children under process or CPU pressure, which was
 * observed as exit 137 during PRoot boot. A bound `:x11` service receives the
 * foreground client's process importance while retaining the separate process
 * required by Termux:X11.
 */
class AppManagedNativeX11Server(
    context: Context,
    private val paths: VmPaths,
) : NativeX11Server {

    private val appContext = context.applicationContext
    private val connectionLock = Any()

    @Volatile
    private var activeConnection: ServiceConnection? = null

    @Volatile
    private var serviceBinder: IBinder? = null

    override fun start(rootfsDir: File, guestTmpDir: File, displayNumber: Int) {
        check(activeConnection == null) { "native X11 is already bound" }
        statusFile.delete()

        val connected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (activeConnection === this) serviceBinder = binder
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (activeConnection === this) serviceBinder = null
                connected.countDown()
            }

            override fun onNullBinding(name: ComponentName) {
                connected.countDown()
            }

            override fun onBindingDied(name: ComponentName) {
                if (activeConnection === this) serviceBinder = null
                connected.countDown()
            }
        }

        synchronized(connectionLock) {
            check(activeConnection == null) { "native X11 is already bound" }
            activeConnection = connection
            serviceBinder = null
        }

        // Presentation mode is process-wide native state, so it can only be
        // chosen here, before the server process starts.
        val flipPresentation = DisplaySessionPreferences(appContext).preventTearing
        val bound = runCatching {
            appContext.bindService(
                NativeX11Service.bindingIntent(
                    context = appContext,
                    rootfsDir = rootfsDir,
                    guestTmpDir = guestTmpDir,
                    displayNumber = displayNumber,
                    flipPresentation = flipPresentation,
                ),
                connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
            )
        }.getOrElse { error ->
            clearConnection(connection)
            throw LxdError.BootFailed("Android could not create the native X11 service", error)
        }
        if (!bound) {
            clearConnection(connection)
            throw LxdError.BootFailed("Android rejected the native X11 service binding")
        }

        val connectedInTime = try {
            connected.await(SERVICE_CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            stopConnection(connection)
            throw LxdError.BootFailed(
                "native X11 service startup was interrupted",
                interrupted,
            )
        }
        if (!connectedInTime || !isAlive()) {
            val status = diagnosticStatus()
            stopConnection(connection)
            throw LxdError.BootFailed(
                "the app-managed native X11 process did not start: $status"
            )
        }
    }

    override fun isAlive(): Boolean =
        activeConnection != null && serviceBinder?.isBinderAlive == true

    override fun diagnosticStatus(): String {
        val lastStatus = runCatching {
            statusFile.takeIf(File::isFile)?.readText()?.trim()
        }.getOrNull().takeUnless { it.isNullOrEmpty() } ?: "no X11 service status"
        // The status file survives the process; a stale "running" line must
        // not read as if the server were still alive.
        return if (isAlive()) lastStatus else "$lastStatus (X11 process has exited)"
    }

    override fun stop() {
        activeConnection?.let(::stopConnection)
    }

    private fun stopConnection(connection: ServiceConnection) {
        val shouldUnbind = synchronized(connectionLock) {
            if (activeConnection !== connection) {
                false
            } else {
                activeConnection = null
                serviceBinder = null
                true
            }
        }
        if (shouldUnbind) runCatching { appContext.unbindService(connection) }
    }

    private fun clearConnection(connection: ServiceConnection) {
        synchronized(connectionLock) {
            if (activeConnection === connection) {
                activeConnection = null
                serviceBinder = null
            }
        }
    }

    private val statusFile: File
        get() = paths.logsDir.resolve(NativeX11Service.STATUS_FILE_NAME)

    private companion object {
        const val SERVICE_CONNECTION_TIMEOUT_SECONDS = 10L
    }
}
