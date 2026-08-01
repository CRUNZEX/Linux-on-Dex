package com.crunzex.linuxondex.engine.proot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.core.LxdError
import com.crunzex.linuxondex.engine.EngineKind
import com.crunzex.linuxondex.engine.SerialConsoleConnection
import com.crunzex.linuxondex.engine.VirtualizationEngine
import com.crunzex.linuxondex.engine.runtime.GuestProcessReaper
import com.crunzex.linuxondex.engine.runtime.PayloadInstaller
import com.crunzex.linuxondex.engine.runtime.VmPaths
import com.crunzex.linuxondex.vm.PreparedImageConfig
import com.crunzex.linuxondex.vm.PreparedImageFormat
import com.crunzex.linuxondex.vm.ScreenResolution
import com.crunzex.linuxondex.vm.StopReason
import com.crunzex.linuxondex.vm.VmConfig
import com.crunzex.linuxondex.vm.VmState
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * Runs Linux through PRoot's syscall translation: no VM and no guest kernel,
 * which means **native CPU speed** — the property that makes a graphical
 * desktop usable on devices where /dev/kvm is denied and QEMU must emulate.
 *
 * Two modes, decided by the selected image:
 *
 *  - **Desktop rootfs image** (`*.rootfs.tar.gz`): the archive is extracted
 *    once, then its baked `dex-desktop` supervisor starts an Xvnc + GNOME
 *    session. The engine reports the VNC port, so the same Display screen
 *    (and its FPS counter) used for QEMU guests just works. Terminal windows
 *    each get their own login shell into the rootfs.
 *
 *  - **Bundled Alpine fallback** (no image selected): the historical
 *    terminal-only shell session, unchanged.
 */
class ProotEngine(
    private val paths: VmPaths,
    private val payloadInstaller: PayloadInstaller,
    private val rootfsInstaller: RootfsImageInstaller = RootfsImageInstaller(paths),
    private val reaper: GuestProcessReaper = GuestProcessReaper(paths),
) : VirtualizationEngine {

    override val kind: EngineKind = EngineKind.PROOT

    private val _state = MutableStateFlow<VmState>(VmState.Idle)
    override val state: StateFlow<VmState> = _state.asStateFlow()

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val shutdownInitiated = AtomicBoolean(false)

    /** The session leader: the desktop supervisor, or the legacy shell. */
    private var sessionProcess: Process? = null

    /** Extracted rootfs of the active desktop session; null in legacy mode. */
    private var activeRootfsDir: File? = null

    /** Terminal shells spawned for the desktop mode, killed on stop. */
    private val consoleShells = CopyOnWriteArrayList<Process>()

    override suspend fun start(config: VmConfig) = lifecycleMutex.withLock {
        check(!_state.value.isBusy && !_state.value.isRunning) {
            "start() called while ${_state.value}"
        }
        withContext(Dispatchers.IO) {
            try {
                startLocked(config)
            } catch (error: Exception) {
                // A session that failed to become reachable may still be
                // alive; it must not keep burning CPU behind a Failed state.
                shutdownInitiated.set(true)
                sessionProcess?.let { failedSession ->
                    runCatching { endEveryGuestProcess(failedSession) }
                }
                cleanupProcesses()
                val lxdError = error as? LxdError
                    ?: LxdError.BootFailed("PRoot startup failed: ${error.message}", error)
                _state.value = VmState.Failed(lxdError)
                throw lxdError
            }
        }
    }

    private fun startLocked(config: VmConfig) {
        shutdownInitiated.set(false)

        _state.value = VmState.Preparing("Checking runtime")
        paths.createRuntimeDirectories()
        payloadInstaller.ensureInstalled()

        val rootfsImage = config.preparedImage
            ?.takeIf { it.format == PreparedImageFormat.PROOT_ROOTFS }
        if (rootfsImage != null) {
            startRootfsImageLocked(config, rootfsImage)
        } else {
            startLegacyShellLocked()
        }
    }

    // ---- Rootfs image modes ---------------------------------------------------

    /**
     * Starts a rootfs image, as whichever kind of container it turns out to
     * be.
     *
     * The image itself decides: one that bakes a desktop supervisor gets a
     * graphical session and a display port, one that does not is console
     * only. Asking the extracted tree is better than trusting a flag in the
     * configuration, because the tree is what will actually be run — a
     * console image cannot be made graphical by labelling it.
     */
    private fun startRootfsImageLocked(config: VmConfig, image: PreparedImageConfig) {
        val rootfsDir = extractImageReportingProgress(File(image.diskImagePath))
        if (rootfsDir.resolve(DESKTOP_SUPERVISOR_RELATIVE_PATH).exists()) {
            startDesktopSessionLocked(config, rootfsDir)
        } else {
            startConsoleSessionLocked(rootfsDir)
        }
    }

    /**
     * A console container: no X server and no display port, just a session
     * leader holding the container open so terminal windows can open shells
     * into it.
     *
     * It is "running" as soon as its leader is alive — there is no display to
     * wait for, which is why these images reach a usable shell in seconds.
     */
    private fun startConsoleSessionLocked(rootfsDir: File) {
        _state.value = VmState.Starting(kind)
        desktopLogFile().delete() // fresh log per start, as the QEMU engine does
        val command = ProotCommandFactory.consoleSession(
            paths = paths,
            rootfsDir = rootfsDir,
            sharedFolderDir = paths.sharedFolderDir?.also { it.mkdirs() },
        )
        val process = command.start(redirectErrorStream = true)
        sessionProcess = process
        activeRootfsDir = rootfsDir
        pumpOutputToLog(process, desktopLogFile())
        watchSessionExit(process, isDesktopSession = false)

        requireLeaderSurvivedStartup(process)
        _state.value = VmState.Running(
            engine = kind,
            vncPort = null, // console only: nothing to display
            startedAtMillis = System.currentTimeMillis(),
        )
        AppLog.info(SCOPE, "console container ready (${rootfsDir.name})")
    }

    /**
     * Gives the session leader a moment to fail, so an image that cannot run
     * at all is reported as a failed start rather than as a running container
     * with no shell in it.
     */
    private fun requireLeaderSurvivedStartup(process: Process) {
        if (process.waitFor(CONSOLE_STARTUP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
            throw LxdError.BootFailed(
                "the container stopped immediately (exit ${process.exitValue()}): " +
                    readLogTail(desktopLogFile())
            )
        }
    }

    private fun startDesktopSessionLocked(config: VmConfig, rootfsDir: File) {
        prepareSharedMemoryDir()

        _state.value = VmState.Starting(kind)
        desktopLogFile().delete() // fresh log per boot, like the QEMU engine
        val command = ProotCommandFactory.desktopSession(
            paths = paths,
            rootfsDir = rootfsDir,
            displayResolution = config.display.resolution.asGeometryText(),
            vncPort = config.vncPort,
            sharedFolderDir = paths.sharedFolderDir?.also { it.mkdirs() },
        )
        val process = command.start(redirectErrorStream = true)
        sessionProcess = process
        activeRootfsDir = rootfsDir
        pumpOutputToLog(process, desktopLogFile())
        watchSessionExit(process, isDesktopSession = true)

        waitForVncServer(process, config.vncPort)
        _state.value = VmState.Running(
            engine = kind,
            vncPort = config.vncPort,
            startedAtMillis = System.currentTimeMillis(),
        )
        AppLog.info(SCOPE, "desktop session up on VNC port ${config.vncPort}")
    }

    /**
     * One-time extraction with user-visible progress; instant when stamped.
     * Runs under the lifecycle lock, so a Stop pressed mid-extraction takes
     * effect right after — extraction is not cancellable, only waited out.
     */
    private fun extractImageReportingProgress(archiveFile: File): File {
        _state.value = VmState.Preparing("Preparing the Linux desktop")
        return rootfsInstaller.ensureExtracted(archiveFile) { percent ->
            _state.value = VmState.Preparing(
                "Extracting the Linux desktop (one-time)… $percent%"
            )
        }
    }

    /** /dev/shm contents are session-scoped; start each boot empty. */
    private fun prepareSharedMemoryDir() {
        paths.prootSharedMemoryDir.deleteRecursively()
        paths.prootSharedMemoryDir.mkdirs()
    }

    /**
     * The session is "running" when its VNC server accepts a connection.
     * Until then the supervisor is still bringing up X and GNOME — and if
     * the process dies first, the log tail says why.
     */
    private fun waitForVncServer(process: Process, vncPort: Int) {
        val deadline = System.currentTimeMillis() + DESKTOP_STARTUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                throw LxdError.BootFailed(
                    "the desktop session ended during startup " +
                        "(exit ${process.waitFor()}): ${readLogTail(desktopLogFile())}"
                )
            }
            if (canConnectTo(vncPort)) return
            Thread.sleep(VNC_POLL_INTERVAL_MS)
        }
        throw LxdError.BootFailed(
            "the desktop did not publish its display within " +
                "${DESKTOP_STARTUP_TIMEOUT_MS / 1000}s: ${readLogTail(desktopLogFile())}"
        )
    }

    private fun canConnectTo(port: Int): Boolean = try {
        Socket().use { probe ->
            probe.connect(InetSocketAddress("127.0.0.1", port), VNC_CONNECT_TIMEOUT_MS)
            true
        }
    } catch (_: Exception) {
        false
    }

    // ---- Legacy bundled-Alpine mode -------------------------------------------

    private fun startLegacyShellLocked() {
        _state.value = VmState.Preparing("Extracting Linux userland")
        ensureLegacyAlpineExtracted()

        _state.value = VmState.Starting(kind)
        val process = ProotCommandFactory.legacyAlpineShell(paths)
            .start(redirectErrorStream = false)
        sessionProcess = process
        activeRootfsDir = null
        watchSessionExit(process, isDesktopSession = false)

        _state.value = VmState.Running(
            engine = kind,
            vncPort = null, // terminal-only session
            startedAtMillis = System.currentTimeMillis(),
        )
        AppLog.info(SCOPE, "PRoot shell session started")
    }

    /**
     * The Alpine rootfs tar ships in assets; extraction happens once and is
     * stamped by the presence of /bin/busybox inside the target directory.
     */
    private fun ensureLegacyAlpineExtracted() {
        val rootfsDir = paths.prootRootfsDir
        val busybox = rootfsDir.resolve("bin/busybox")
        if (busybox.exists()) return

        val installedArchive = paths.vmRootDir.resolve(ALPINE_ARCHIVE_RELATIVE_PATH)
        if (!installedArchive.exists()) {
            throw LxdError.PayloadMissing(installedArchive.name)
        }
        installedArchive.inputStream().buffered().use { archive ->
            TarExtractor.extract(archive, rootfsDir)
        }
        if (!busybox.exists()) {
            throw LxdError.PayloadCorrupted("rootfs extracted but bin/busybox is missing")
        }
        writeLegacyDnsConfig(rootfsDir)
    }

    /** PRoot guests have no DHCP; give the resolver a sane default. */
    private fun writeLegacyDnsConfig(rootfsDir: File) {
        runCatching {
            val etcDir = rootfsDir.resolve("etc").apply { mkdirs() }
            etcDir.resolve("resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
    }

    // ---- Lifecycle -------------------------------------------------------------

    override suspend fun stop(gracePeriod: Duration): Unit = lifecycleMutex.withLock {
        withContext(Dispatchers.IO) {
            val process = sessionProcess
            if (process == null || !process.isAlive) {
                // Even with no process of our own to stop, a previous session
                // may have left one running; the next start must find the
                // display port free.
                reaper.killAllGuestProcesses()
                cleanupProcesses()
                _state.value = VmState.Stopped(StopReason.USER_REQUESTED)
                return@withContext
            }
            _state.value = VmState.Stopping
            shutdownInitiated.set(true)

            closeConsoleShells()
            val exitedGracefully = requestGracefulShutdown(process, gracePeriod)
            endEveryGuestProcess(process)

            cleanupProcesses()
            _state.value = VmState.Stopped(
                if (exitedGracefully) StopReason.USER_REQUESTED else StopReason.FORCED
            )
            AppLog.info(SCOPE, "session stopped (graceful=$exitedGracefully)")
        }
    }

    /** SIGTERM and a wait, so the desktop can save state and log out. */
    private fun requestGracefulShutdown(process: Process, gracePeriod: Duration): Boolean {
        process.destroy()
        return process.waitFor(gracePeriod.inWholeSeconds, TimeUnit.SECONDS)
    }

    /**
     * Ends the whole guest, not just the process the app started.
     *
     * PRoot traces its guest rather than owning it, so its X server, session
     * bus and desktop survive its death as orphans — still holding the VNC
     * port, which made the next start fail on an address already in use with
     * nothing visibly running. The tree is enumerated and killed explicitly,
     * then a final sweep catches anything a previous run left behind.
     */
    private fun endEveryGuestProcess(process: Process) {
        process.destroyForcibly()
        process.waitFor(FORCE_KILL_WAIT_SECONDS, TimeUnit.SECONDS)
        reaper.killAllGuestProcesses()
    }

    override suspend fun forceStop(): Unit = stop(gracePeriod = Duration.ZERO)

    private fun watchSessionExit(process: Process, isDesktopSession: Boolean) {
        engineScope.launch {
            val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
            if (shutdownInitiated.get()) return@launch // stop() owns the state
            if (!_state.value.isRunning) return@launch // startup failures report themselves
            if (isDesktopSession && exitCode != 0) {
                _state.value = VmState.Failed(
                    LxdError.BootFailed(
                        "the desktop session ended unexpectedly (exit $exitCode). " +
                            diagnoseSessionDeath(exitCode) +
                            readLogTail(desktopLogFile())
                    )
                )
            } else {
                // Logging out of the desktop is exit 0. The legacy shell's
                // exit code is whatever the user's last command returned —
                // never a failure of the session itself.
                AppLog.info(SCOPE, "session ended by the guest (exit $exitCode)")
                _state.value = VmState.Stopped(StopReason.GUEST_SHUTDOWN)
            }
            // However the session ended, its traced programs may still be
            // alive. Leaving them would block the next start on a port that
            // nothing visible is using.
            reaper.killAllGuestProcesses()
            cleanupProcesses()
        }
    }

    private fun cleanupProcesses() {
        closeConsoleShells()
        sessionProcess = null
        activeRootfsDir = null
    }

    private fun closeConsoleShells() {
        consoleShells.forEach { shell -> runCatching { shell.destroyForcibly() } }
        consoleShells.clear()
    }

    // ---- Consoles ---------------------------------------------------------------

    /**
     * Desktop mode: every terminal window gets its own login shell into the
     * rootfs, independent of the graphical session. Legacy mode keeps the
     * historical behaviour — the single shell IS the session.
     */
    override fun openConsole(index: Int): SerialConsoleConnection? {
        val session = sessionProcess ?: return null
        if (!session.isAlive) return null

        val rootfsDir = activeRootfsDir
            ?: return if (index <= 0) SessionPipeConsole(session) else null

        return try {
            val shell = ProotCommandFactory
                .interactiveShell(paths, rootfsDir, paths.sharedFolderDir)
                .start(redirectErrorStream = true)
            consoleShells += shell
            OwnedShellConsole(shell) { consoleShells.remove(shell) }
        } catch (error: Exception) {
            AppLog.warn(SCOPE, "could not open a shell into the rootfs", error)
            null
        }
    }

    override fun openSerialConsole(): SerialConsoleConnection? = openConsole(index = 0)

    // ---- Plumbing ----------------------------------------------------------------

    private fun desktopLogFile(): File = paths.logsDir.resolve(DESKTOP_LOG_FILE_NAME)

    /** Supervisor stdout+stderr → log file; it is the boot log on failure. */
    private fun pumpOutputToLog(process: Process, logFile: File) {
        engineScope.launch {
            try {
                logFile.outputStream().bufferedWriter().use { sink ->
                    process.inputStream.bufferedReader().forEachLine { line ->
                        sink.appendLine(line)
                        sink.flush()
                        AppLog.debug(SCOPE, "desktop: $line")
                    }
                }
            } catch (_: Exception) {
                // Stream closes when the session dies; nothing to handle.
            }
        }
    }

    /**
     * Names the cause when the exit code is one Android produces itself.
     *
     * A killed process reports 128 + signal, and the desktop's usual way to
     * die on Android is not a crash: past `max_phantom_processes` (32 by
     * default) the system SIGKILLs an app's forked children as a group, so
     * the session disappears with no error of its own. Saying so beats
     * showing a log tail full of unrelated GNOME warnings.
     */
    private fun diagnoseSessionDeath(exitCode: Int): String = when (exitCode) {
        EXIT_CODE_SIGKILL -> "Android stopped the session's processes — this happens " +
            "when the desktop runs more background programs than Android allows an " +
            "app to keep (its phantom-process limit). Close other apps and start " +
            "again, or use an image built for this limit. Log: "
        EXIT_CODE_SIGTERM -> "Android or the system asked the session to stop. Log: "
        else -> "Log: "
    }

    /**
     * The lines of the session log that explain a failure.
     *
     * A plain tail is misleading here: an X server answers one bad option by
     * printing its whole help text, so the end of the log is a parameter list
     * and the actual reason has scrolled out of view.
     * [DesktopLogSummary] picks the failure lines out instead.
     */
    private fun readLogTail(logFile: File): String {
        if (!logFile.exists()) return "(no log)"
        return try {
            val text = logFile.readBytes().let { bytes ->
                String(bytes.copyOfRange((bytes.size - LOG_TAIL_BYTES).coerceAtLeast(0), bytes.size))
            }
            DesktopLogSummary.summarise(text).ifEmpty { "(log empty)" }
        } catch (error: Exception) {
            "(log unreadable: ${error.message})"
        }
    }

    /** Console over the session's own pipes (no pty: line-buffered but usable). */
    private class SessionPipeConsole(private val process: Process) : SerialConsoleConnection {
        override fun read(buffer: ByteArray): Int = process.inputStream.read(buffer)

        override fun write(data: ByteArray) {
            process.outputStream.write(PipeLineEndings.forShellPipe(data))
            process.outputStream.flush()
        }

        override fun close() {
            // The console detaching must not kill the session; nothing to do.
        }
    }

    /**
     * Console that owns its shell process: closing the window ends the shell.
     *
     * This shell runs under a pseudo-terminal (see
     * [ProotCommandFactory.interactiveShell]), so keystrokes are passed
     * through untouched — the pty's line discipline handles Enter exactly as
     * a serial console would.
     */
    private class OwnedShellConsole(
        private val shell: Process,
        private val onClosed: () -> Unit,
    ) : SerialConsoleConnection {
        override fun read(buffer: ByteArray): Int = shell.inputStream.read(buffer)

        override fun write(data: ByteArray) {
            shell.outputStream.write(data)
            shell.outputStream.flush()
        }

        override fun close() {
            runCatching { shell.destroyForcibly() }
            onClosed()
        }
    }

    companion object {
        private const val SCOPE = "ProotEngine"
        private const val ALPINE_ARCHIVE_RELATIVE_PATH = "proot/alpine-minirootfs-aarch64.tar"
        private const val DESKTOP_LOG_FILE_NAME = "proot-desktop.log"

        /**
         * Present only in images that carry a graphical session; its absence
         * is what marks an image as console only.
         */
        private const val DESKTOP_SUPERVISOR_RELATIVE_PATH = "usr/local/bin/dex-desktop"

        /** Long enough for a broken container to fail, short enough not to stall. */
        private const val CONSOLE_STARTUP_GRACE_MILLIS = 1_500L
        private const val FORCE_KILL_WAIT_SECONDS = 3L

        /**
         * GNOME's very first start builds font and icon caches on top of
         * starting X; generous beats a spurious failure on a slow phone.
         */
        private const val DESKTOP_STARTUP_TIMEOUT_MS = 180_000L
        private const val VNC_POLL_INTERVAL_MS = 500L
        private const val VNC_CONNECT_TIMEOUT_MS = 400
        private const val LOG_TAIL_BYTES = 16_000

        /** A killed process reports 128 + the signal number. */
        private const val EXIT_CODE_SIGKILL = 137
        private const val EXIT_CODE_SIGTERM = 143
    }
}

/** "1280x800" — the geometry text X servers take. */
private fun ScreenResolution.asGeometryText(): String = "${widthPx}x$heightPx"
