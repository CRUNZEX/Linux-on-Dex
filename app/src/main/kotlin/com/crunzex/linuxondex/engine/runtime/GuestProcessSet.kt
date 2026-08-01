package com.crunzex.linuxondex.engine.runtime

/**
 * Decides which operating-system processes belong to a guest and may be
 * killed. Pure logic, separated from /proc so every rule is unit-testable —
 * a mistake here would signal a process that is not ours.
 *
 * Processes are recognised by **where they execute from**, not by descent.
 * Descent looks tempting but cannot be relied on: PRoot traces its guest
 * instead of owning it, so when PRoot dies its programs are reparented to
 * init and every trace of who started them is gone. Their executable path,
 * however, still points inside the app's own private directories, and that is
 * a fact no reparenting can change.
 */
object GuestProcessSet {

    /** One process as read from /proc, reduced to what the rules need. */
    data class ProcessSnapshot(
        val processId: Int,
        /** Target of /proc/<pid>/exe, or null when it cannot be read. */
        val executablePath: String?,
        /** /proc/<pid>/cmdline with its NUL separators turned into spaces. */
        val commandLine: String,
    )

    /**
     * Processes running out of [guestDirectoryPaths] — the app's own guest
     * trees and its native-library directory. Anything else is left alone,
     * however much it looks like a guest: `/usr/bin/gnome-shell` in a command
     * line proves nothing, because PRoot rewrites paths and only the real
     * location on the host counts.
     *
     * [ownProcessId] is always excluded. Killing the process doing the
     * killing would take the app down with it, which is the very outcome this
     * reaping exists to prevent.
     */
    fun runningInsideGuestDirectories(
        snapshots: List<ProcessSnapshot>,
        guestDirectoryPaths: List<String>,
        ownProcessId: Int,
    ): List<Int> {
        val directoryPrefixes = guestDirectoryPaths
            .filter(String::isNotBlank)
            .map { path -> path.trimEnd('/') + "/" }
        if (directoryPrefixes.isEmpty()) return emptyList()

        return snapshots
            .filter { snapshot -> snapshot.processId != ownProcessId }
            .filter { snapshot -> runsFromGuestDirectory(snapshot, directoryPrefixes) }
            .map(ProcessSnapshot::processId)
    }

    /**
     * True when this process executes a file inside one of the guest trees.
     *
     * The executable path is the reliable signal. The command line is only
     * consulted as a second chance, for the guest's launcher: its executable
     * is the app's own PRoot binary while the rootfs it opened appears in its
     * arguments.
     */
    private fun runsFromGuestDirectory(
        snapshot: ProcessSnapshot,
        directoryPrefixes: List<String>,
    ): Boolean {
        val executablePath = snapshot.executablePath
        val executesInsideGuest = executablePath != null &&
            directoryPrefixes.any { prefix -> executablePath.startsWith(prefix) }
        if (executesInsideGuest) return true

        return directoryPrefixes.any { prefix -> snapshot.commandLine.contains(prefix) }
    }
}
