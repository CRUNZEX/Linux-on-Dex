package com.crunzex.linuxondex.engine.runtime

import android.os.Process as AndroidProcess
import android.system.Os
import android.system.OsConstants
import com.crunzex.linuxondex.core.AppLog
import java.io.File

/**
 * Makes sure a stopped guest is really gone.
 *
 * PRoot supervises its guest by tracing it, not by owning it: killing the
 * PRoot process leaves the traced programs — the X server, the session bus,
 * the whole desktop — running as orphans reparented to init. They keep
 * burning CPU and keep their sockets bound, so the *next* start fails on a
 * port nothing visible is using, and the only cure left to the user is force
 * stopping the app. This class is why that is no longer true.
 *
 * Safety is the design, not a footnote. Three independent limits apply: the
 * kernel only lets a process signal its own user, so nothing outside this
 * app's sandbox is reachable at all; [GuestProcessSet] narrows candidates to
 * processes executing out of the app's own directories; and the app's own
 * process id is excluded explicitly.
 */
class GuestProcessReaper(private val paths: VmPaths) {

    /**
     * Kills every guest process still running and returns how many there
     * were.
     *
     * Safe to call when nothing is running — the normal result is zero. Used
     * both after a stop, to finish off traced programs that outlived their
     * supervisor, and before a start, to clear leftovers from a session that
     * outlived the app itself (which is what Android's own low-memory kill
     * leaves behind).
     */
    fun killAllGuestProcesses(): Int {
        val guestProcessIds = runCatching {
            GuestProcessSet.runningInsideGuestDirectories(
                snapshots = readProcessTable(),
                guestDirectoryPaths = guestDirectoryPaths(),
                ownProcessId = AndroidProcess.myPid(),
            )
        }.getOrElse { failure ->
            AppLog.warn(SCOPE, "could not scan for guest processes", failure)
            return 0
        }

        if (guestProcessIds.isEmpty()) return 0
        val killedCount = guestProcessIds.count(::killProcess)
        AppLog.info(
            SCOPE,
            "ended $killedCount guest process(es): ${guestProcessIds.joinToString(", ")}",
        )
        return killedCount
    }

    /**
     * Every directory a guest may execute from: the extracted rootfs images,
     * the bundled Alpine userland, and the native-library directory — the
     * only place this app is allowed to exec from, and therefore where PRoot
     * and QEMU themselves run from.
     */
    private fun guestDirectoryPaths(): List<String> = listOf(
        paths.prootImagesDir.absolutePath,
        paths.prootRootfsDir.absolutePath,
        paths.nativeLibraryDir.absolutePath,
    )

    /**
     * Sends SIGKILL, not SIGTERM: this runs after a graceful stop was already
     * offered, or on a leftover with no orderly shutdown left to perform.
     *
     * A process that has already exited (ESRCH) counts as gone rather than as
     * a failure — being gone is the entire goal.
     */
    private fun killProcess(processId: Int): Boolean = try {
        Os.kill(processId, OsConstants.SIGKILL)
        true
    } catch (alreadyGone: Exception) {
        AppLog.debug(SCOPE, "pid $processId was already gone ($alreadyGone)")
        false
    }

    /**
     * The process table, as far as this app is allowed to see it. Android
     * mounts /proc with `hidepid`, so an app lists its own processes and
     * nothing else — which is exactly the set it may signal.
     */
    private fun readProcessTable(): List<GuestProcessSet.ProcessSnapshot> =
        File(PROC_DIRECTORY).listFiles().orEmpty()
            .mapNotNull { entry -> entry.name.toIntOrNull() }
            .map(::readProcessSnapshot)

    private fun readProcessSnapshot(processId: Int): GuestProcessSet.ProcessSnapshot {
        val processDirectory = File(PROC_DIRECTORY, processId.toString())
        return GuestProcessSet.ProcessSnapshot(
            processId = processId,
            executablePath = readExecutablePath(processDirectory),
            commandLine = readCommandLine(processDirectory),
        )
    }

    private fun readExecutablePath(processDirectory: File): String? = runCatching {
        Os.readlink(File(processDirectory, "exe").absolutePath)
    }.getOrNull()

    /** /proc separates command-line arguments with NUL bytes, not spaces. */
    private fun readCommandLine(processDirectory: File): String = runCatching {
        File(processDirectory, "cmdline").readBytes()
            .toString(Charsets.UTF_8)
            .replace(ARGUMENT_SEPARATOR, ' ')
            .trim()
    }.getOrDefault("")

    companion object {
        private const val SCOPE = "GuestProcessReaper"
        private const val PROC_DIRECTORY = "/proc"

        /** /proc/<pid>/cmdline joins arguments with NUL. */
        private const val ARGUMENT_SEPARATOR = '\u0000'
    }
}
