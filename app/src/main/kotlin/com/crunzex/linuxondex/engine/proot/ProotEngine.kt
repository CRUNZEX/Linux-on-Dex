package com.crunzex.linuxondex.engine.proot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
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
import com.crunzex.linuxondex.vm.DisplayEndpoint
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
 * When the engine may hand the *whole desktop* to the Android GPU bridge.
 *
 * [HARDWARE_BACKED_ONLY] is the shipped behaviour: a bridge whose renderer
 * string reveals a software rasterizer (the emulator's ANGLE ends up on CPU
 * lavapipe, for example) is worse than local llvmpipe — every frame would
 * pay the vtest socket on top of CPU rendering — so it is rejected outright.
 * [ANY_WORKING_RENDERER] exists for pipeline verification on emulators,
 * where no hardware-backed EGL is available at all; it must never be the
 * default.
 */
enum class GraphicsBridgePolicy {
    HARDWARE_BACKED_ONLY,
    ANY_WORKING_RENDERER,
}

/**
 * Runs Linux through PRoot's syscall translation: no VM and no guest kernel,
 * which means **native CPU speed** — the property that makes a graphical
 * desktop usable on devices where /dev/kvm is denied and QEMU must emulate.
 *
 * Two modes, decided by the selected image:
 *
 *  - **Desktop rootfs image** (`*.rootfs.tar.gz`): the archive is extracted
 *    once, then its baked `dex-desktop` supervisor starts a desktop against
 *    the embedded native X server. Older images keep their Xvnc/RFB path.
 *    Terminal windows each get their own login shell into the rootfs.
 *
 *  - **Bundled Alpine fallback** (no image selected): the historical
 *    terminal-only shell session, unchanged.
 */
class ProotEngine(
    private val paths: VmPaths,
    private val payloadInstaller: PayloadInstaller,
    private val nativeX11Server: NativeX11Server,
    private val rootfsInstaller: RootfsImageInstaller = RootfsImageInstaller(paths),
    private val reaper: GuestProcessReaper = GuestProcessReaper(paths),
    private val graphicsBridge: AndroidVirglBridge = AndroidVirglBridge(paths),
    /** The user's "GPU-accelerated desktop" setting, read fresh per boot. */
    private val desktopGpuPreference: () -> Boolean = { true },
    private val bridgePolicy: GraphicsBridgePolicy = GraphicsBridgePolicy.HARDWARE_BACKED_ONLY,
    /**
     * Invoked once the session /tmp exists, so app layers can drop their
     * markers into it before the supervisor starts — e.g. the viewer's
     * "already attached" marker that skips the GPU session's viewer wait.
     */
    private val onGuestTmpPrepared: () -> Unit = {},
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

    /** True only after the native renderer has published its Unix socket. */
    private var graphicsBridgeEnabled = false

    /** What the running desktop session actually renders with. */
    private var activeRendererStage: RendererStage? = null

    /** Terminal shells spawned for the desktop mode, killed on stop. */
    private val consoleShells = CopyOnWriteArrayList<ConsoleShellProcess>()

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
        // The previous session may have left a renderer verdict in its /tmp;
        // it must be read before the wipe below destroys it.
        consumeGpuDesktopFailureMarker(rootfsDir)
        prepareGuestTmpDir()
        onGuestTmpPrepared()
        if (rootfsDir.resolve(DESKTOP_SUPERVISOR_RELATIVE_PATH).exists()) {
            startDesktopSessionLocked(config, rootfsDir)
        } else {
            startConsoleSessionLocked(rootfsDir)
        }
    }

    /**
     * The supervisor abandons virpipe *inside* a session when GNOME cannot
     * hold a GPU-rendered desktop up, and marks that verdict in the session
     * /tmp. The session itself then continues on llvmpipe — often for hours
     * — so the app only learns about the downgrade here, at the next start,
     * and makes it sticky so later boots skip the doomed attempt.
     */
    private fun consumeGpuDesktopFailureMarker(rootfsDir: File) {
        val marker = paths.prootGuestTmpDir.resolve(GPU_DESKTOP_FAILURE_MARKER_NAME)
        if (!marker.exists()) return
        marker.delete()
        // The session /tmp is wiped a moment after this, taking the crash
        // context with it — capture the compositor's last words first.
        AppLog.info(
            SCOPE,
            "guest GNOME log from the abandoned GPU session: ${readGuestCompositorLogTail()}",
        )
        val next = rendererLadderFor(rootfsDir).stepDownFromGpuStage() ?: return
        AppLog.warn(
            SCOPE,
            "last session abandoned the GPU desktop mid-run; staying on ${next.describe()}",
        )
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
        val stickyStage = prepareRendererStage(rootfsDir)
        graphicsBridgeEnabled = startVerifiedGraphicsBridge(rootfsDir, stickyStage)
        val rendererStage = resolveBootRendererStage(stickyStage)
        activeRendererStage = rendererStage
        val displayBackend = displayBackendFor(rootfsDir)

        _state.value = VmState.Starting(kind)
        desktopLogFile().delete() // fresh log per boot, like the QEMU engine
        if (displayBackend == ProotDisplayBackend.NATIVE_X11) {
            startNativeXServer(rootfsDir)
        }
        val command = ProotCommandFactory.desktopSession(
            paths = paths,
            rootfsDir = rootfsDir,
            displayResolution = config.display.resolution.asGeometryText(),
            vncPort = config.vncPort,
            sharedFolderDir = paths.sharedFolderDir?.also { it.mkdirs() },
            graphicsBridgeEnabled = graphicsBridgeEnabled,
            displayBackend = displayBackend,
            rendererStage = rendererStage,
        )
        val process = command.start(redirectErrorStream = true)
        sessionProcess = process
        activeRootfsDir = rootfsDir
        pumpOutputToLog(process, desktopLogFile())
        watchSessionExit(process, isDesktopSession = true)

        val displayEndpoint = when (displayBackend) {
            ProotDisplayBackend.NATIVE_X11 -> {
                waitForNativeXServer(process, rootfsDir)
                DisplayEndpoint.NativeX11(ProotCommandFactory.NATIVE_X11_DISPLAY_NUMBER)
            }
            ProotDisplayBackend.LEGACY_VNC -> {
                waitForVncServer(process, config.vncPort)
                DisplayEndpoint.Rfb(config.vncPort)
            }
        }
        _state.value = VmState.Running(
            engine = kind,
            vncPort = (displayEndpoint as? DisplayEndpoint.Rfb)?.port,
            startedAtMillis = System.currentTimeMillis(),
            displayEndpoint = displayEndpoint,
        )
        AppLog.info(SCOPE, "desktop session ready via $displayBackend")
    }

    /**
     * Chooses the renderer for this boot and makes its prerequisites real:
     * the sticky ladder remembers a crash-driven downgrade per image, and
     * the portable-CPU stages need the curated cpuinfo on disk before PRoot
     * can bind it over /proc/cpuinfo.
     */
    private fun prepareRendererStage(rootfsDir: File): RendererStage {
        val stage = rendererLadderFor(rootfsDir).currentStage()
        if (stage.usesPortableCpuProfile) {
            writePortableCpuProfile()
        }
        if (stage != RendererStage.GPU_VIRGL) {
            AppLog.info(SCOPE, "desktop renderer: ${stage.describe()} (sticky after a crash)")
        }
        return stage
    }

    /**
     * The GPU rung is the only one with boot-time preconditions: the user
     * must want it and this boot's bridge must have passed verification.
     * Falling short skips the rung for this session without writing the
     * ladder — a bridge hiccup today says nothing about tomorrow, and the
     * setting can simply be turned back on.
     */
    private fun resolveBootRendererStage(stickyStage: RendererStage): RendererStage {
        if (!stickyStage.usesGpuBridgeRenderer) return stickyStage
        val wanted = desktopGpuPreference()
        if (wanted && graphicsBridgeEnabled) return stickyStage
        val reason = if (wanted) "the bridge failed verification" else "disabled in settings"
        AppLog.info(SCOPE, "GPU desktop skipped this boot ($reason); using llvmpipe")
        return RendererStage.NATIVE
    }

    private fun rendererLadderFor(rootfsDir: File): RendererFallbackLadder =
        RendererFallbackLadder(
            stateFile = paths.rendererStageFile,
            rootfsStamp = rootfsDir.resolve(RootfsImageInstaller.STAMP_FILE_NAME)
                .takeIf(File::isFile)?.readText()?.trim() ?: rootfsDir.name,
        )

    private fun writePortableCpuProfile() {
        val processorCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        paths.portableCpuinfoFile.writeText(PortableCpuProfile.cpuinfoText(processorCount))
    }

    private fun displayBackendFor(rootfsDir: File): ProotDisplayBackend {
        val marker = rootfsDir.resolve(DISPLAY_BACKEND_MARKER_RELATIVE_PATH)
        return if (marker.isFile && marker.readText().trim() == NATIVE_X11_MARKER) {
            ProotDisplayBackend.NATIVE_X11
        } else {
            ProotDisplayBackend.LEGACY_VNC
        }
    }

    private fun startNativeXServer(rootfsDir: File) {
        if (!paths.x11RendererLibrary.isFile) {
            throw LxdError.PayloadMissing(paths.x11RendererLibrary.name)
        }
        val xkbRoot = rootfsDir.resolve(XKB_CONFIG_ROOT_RELATIVE_PATH)
        if (!xkbRoot.isDirectory) {
            throw LxdError.BootFailed("the rootfs is missing XKB data at /usr/share/X11/xkb")
        }
        nativeX11Server.start(
            rootfsDir = rootfsDir,
            guestTmpDir = paths.prootGuestTmpDir,
            displayNumber = ProotCommandFactory.NATIVE_X11_DISPLAY_NUMBER,
        )
    }

    /**
     * A fresh /tmp for the session, world-writable-with-sticky like a real
     * one. The mode matters twice: the X server refuses a `.X11-unix` whose
     * mode it does not trust, and Java's File API cannot express the sticky
     * bit — so both directories are set through [Os.chmod].
     */
    private fun prepareGuestTmpDir() {
        val guestTmp = paths.prootGuestTmpDir
        if (guestTmp.exists() && !guestTmp.deleteRecursively()) {
            throw LxdError.StorageFailed("could not reset the guest /tmp directory")
        }
        val x11SocketDir = guestTmp.resolve(X11_SOCKET_DIR_NAME)
        if (!x11SocketDir.mkdirs()) {
            throw LxdError.StorageFailed("could not create the guest /tmp directory")
        }
        try {
            Os.chmod(guestTmp.absolutePath, WORLD_WRITABLE_STICKY_MODE)
            Os.chmod(x11SocketDir.absolutePath, WORLD_WRITABLE_STICKY_MODE)
        } catch (error: ErrnoException) {
            throw LxdError.StorageFailed("could not set guest /tmp permissions", error)
        }
    }

    private fun waitForNativeXServer(sessionProcess: Process, rootfsDir: File) {
        val socket = paths.prootGuestTmpDir
            .resolve(X11_SOCKET_DIR_NAME)
            .resolve("X${ProotCommandFactory.NATIVE_X11_DISPLAY_NUMBER}")
        val deadline = System.currentTimeMillis() + DESKTOP_STARTUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!sessionProcess.isAlive) {
                throw LxdError.BootFailed(
                    "the desktop session ended during startup " +
                        "(exit ${sessionProcess.waitFor()}): ${readLogTail(desktopLogFile())}"
                )
            }
            if (!nativeX11Server.isAlive()) {
                throw LxdError.BootFailed(
                    "the app-managed native X11 process stopped during startup: " +
                        nativeX11Server.diagnosticStatus()
                )
            }
            if (socket.exists()) return
            Thread.sleep(DISPLAY_POLL_INTERVAL_MS)
        }
        throw LxdError.BootFailed(
            "the native X server did not publish its display socket within " +
                "${DESKTOP_STARTUP_TIMEOUT_MS / 1000}s: ${nativeX11Server.diagnosticStatus()}"
        )
    }

    /**
     * A listening socket is not enough: some vendor EGL stacks initialise
     * lazily and fail only when the first guest context arrives. Probe the
     * complete path before exporting virpipe to the desktop, trying Samsung's
     * system EGL first and bundled ANGLE as the compatibility fallback.
     *
     * A backend that answers but reveals a software rasterizer behind the
     * bridge is only accepted under [GraphicsBridgePolicy.ANY_WORKING_RENDERER]
     * — paying the vtest socket on top of CPU rendering is strictly worse
     * than plain llvmpipe, so production rejects it.
     */
    private fun startVerifiedGraphicsBridge(
        rootfsDir: File,
        rendererStage: RendererStage,
    ): Boolean {
        for (backend in AndroidVirglBridge.Backend.entries) {
            if (!graphicsBridge.start(backend)) continue

            val probeResult = runCatching {
                ProotCommandFactory.graphicsProbe(paths, rootfsDir, rendererStage)
                    .runAndCaptureOutput(timeoutSeconds = GRAPHICS_PROBE_TIMEOUT_SECONDS)
            }
            if (probeResult.isFailure) {
                AppLog.warn(
                    SCOPE,
                    "${backend.displayName} guest probe failed",
                    probeResult.exceptionOrNull(),
                )
                graphicsBridge.stop()
                continue
            }
            val probe = probeResult.getOrThrow()
            val reachedVirgl = probe.isSuccess &&
                probe.output.contains("virgl", ignoreCase = true)
            val hardwareBacked = reachedVirgl &&
                SOFTWARE_RENDERER_MARKERS.none { probe.output.contains(it, ignoreCase = true) }
            val accepted = reachedVirgl &&
                (hardwareBacked || bridgePolicy == GraphicsBridgePolicy.ANY_WORKING_RENDERER)
            if (accepted && graphicsBridge.isRunning) {
                val quality = if (hardwareBacked) "device GPU" else "software-backed (verification)"
                AppLog.info(SCOPE, "guest Mesa verified through ${backend.displayName} — $quality")
                return true
            }

            AppLog.warn(
                SCOPE,
                "${backend.displayName} did not produce a usable renderer: " +
                    probe.output.takeLast(GRAPHICS_PROBE_LOG_CHARS),
            )
            graphicsBridge.stop()
        }
        AppLog.warn(SCOPE, "native guest GPU unavailable; continuing with safe llvmpipe")
        return false
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
            Thread.sleep(DISPLAY_POLL_INTERVAL_MS)
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
     * PRoot traces its guest rather than owning it, so its session bus and
     * desktop can survive as orphans and interfere with the next start. The
     * tree is enumerated and killed explicitly, then a final sweep catches
     * anything a previous run left behind.
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
                val ranStage = activeRendererStage
                recordRendererFallbackAfterCrash(exitCode, ranStage)
                _state.value = VmState.Failed(
                    LxdError.BootFailed(
                        "the desktop session ended unexpectedly (exit $exitCode). " +
                            diagnoseSessionDeath(exitCode, ranStage) +
                            readLogTail(desktopLogFile()) +
                            "\nGNOME log: " + readGuestCompositorLogTail()
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
        nativeX11Server.stop()
        graphicsBridge.stop()
        graphicsBridgeEnabled = false
        activeRendererStage = null
        sessionProcess = null
        activeRootfsDir = null
    }

    private fun closeConsoleShells() {
        consoleShells.forEach(::terminateConsoleShell)
        consoleShells.clear()
    }

    /**
     * Ends the complete PRoot -> script -> bash tree owned by one terminal.
     * Descendants must be reaped before their launcher or Android reparents
     * them and counts each orphan against the 32-process phantom limit.
     */
    private fun terminateConsoleShell(shell: ConsoleShellProcess) {
        runCatching { reaper.killGuestProcessTree(shell.processId) }
            .onFailure { error ->
                AppLog.warn(SCOPE, "could not reap a closed console process tree", error)
            }
        if (shell.process.isAlive) runCatching { shell.process.destroyForcibly() }
        runCatching {
            shell.process.waitFor(CONSOLE_FORCE_KILL_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        }
        consoleShells.remove(shell)
        shell.processIdFile.delete()
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
            val processIdFile = File.createTempFile(
                CONSOLE_PROCESS_ID_FILE_PREFIX,
                CONSOLE_PROCESS_ID_FILE_SUFFIX,
                paths.tmpDir,
            )
            val trackedProcess = ProotCommandFactory
                .interactiveShell(
                    paths = paths,
                    rootfsDir = rootfsDir,
                    sharedFolderDir = paths.sharedFolderDir,
                    graphicsBridgeEnabled = graphicsBridgeEnabled && graphicsBridge.isRunning,
                    // GUI programs launched from a terminal should render
                    // exactly like the desktop: same GPU selection, same
                    // safe CPU profile for their JITs.
                    rendererStage = activeRendererStage
                        ?: rendererLadderFor(rootfsDir).currentStage(),
                )
                .startTracked(processIdFile, redirectErrorStream = true)
            val shell = ConsoleShellProcess(
                process = trackedProcess.process,
                processId = trackedProcess.processId,
                processIdFile = processIdFile,
            )
            consoleShells += shell
            OwnedShellConsole(shell.process) { terminateConsoleShell(shell) }
        } catch (error: Exception) {
            AppLog.warn(SCOPE, "could not open a shell into the rootfs", error)
            null
        }
    }

    override fun openSerialConsole(): SerialConsoleConnection? = openConsole(index = 0)

    // ---- Plumbing ----------------------------------------------------------------

    private fun desktopLogFile(): File = paths.logsDir.resolve(DESKTOP_LOG_FILE_NAME)

    /** Supervisor stdout+stderr → log file; it is the boot log on failure. */
    private fun pumpOutputToLog(
        process: Process,
        logFile: File,
        source: String = "desktop",
    ) {
        engineScope.launch {
            try {
                logFile.outputStream().bufferedWriter().use { sink ->
                    process.inputStream.bufferedReader().forEachLine { line ->
                        sink.appendLine(line)
                        sink.flush()
                        AppLog.debug(SCOPE, "$source: $line")
                        reactToSupervisorSignal(line)
                    }
                }
            } catch (_: Exception) {
                // Stream closes when the session dies; nothing to handle.
            }
        }
    }

    /**
     * The supervisor restarts a crashed GNOME itself, so the session stays
     * Running and these log lines are the app's only notification. Two
     * matter: a compositor restart (the viewer may be showing the dead
     * compositor's last buffer — a black or frozen screen — until it
     * re-imports), and a GPU abandonment (whose crash context would be
     * wiped with the session /tmp before anyone could read it).
     */
    private fun reactToSupervisorSignal(line: String) {
        when (val signal = DesktopSessionSignal.fromSupervisorLine(line)) {
            is DesktopSessionSignal.CompositorRestarting ->
                refreshViewerAfterCompositorRestart(signal.exitCode)
            is DesktopSessionSignal.GpuDesktopAbandoned ->
                AppLog.warn(
                    SCOPE,
                    "GPU desktop abandoned in-session (${signal.reason}); " +
                        "guest GNOME log tail: ${readGuestCompositorLogTail()}",
                )
            null -> Unit
        }
    }

    private fun refreshViewerAfterCompositorRestart(exitCode: Int?) {
        AppLog.info(
            SCOPE,
            "compositor restarting (exit ${exitCode ?: "?"}); refreshing the viewer",
        )
        engineScope.launch {
            // Give the replacement compositor a moment to come up first; the
            // refresh then lands on a desktop that is already painting.
            delay(VIEWER_REFRESH_AFTER_RESTART_MILLIS)
            if (!shutdownInitiated.get() && _state.value.isRunning) {
                nativeX11Server.requestViewerRefresh()
            }
        }
    }

    /**
     * The compositor's own log, written by the supervisor into the session
     * /tmp — which is a host directory, so it stays readable after crashes
     * and even after the whole session died.
     */
    private fun readGuestCompositorLogTail(): String = runCatching {
        val logFile = paths.prootGuestTmpDir.resolve(GUEST_COMPOSITOR_LOG_NAME)
        if (!logFile.isFile) return@runCatching "(no gnome-shell.log)"
        val bytes = logFile.readBytes()
        String(bytes.copyOfRange((bytes.size - GUEST_LOG_TAIL_BYTES).coerceAtLeast(0), bytes.size))
            .trim()
            .ifEmpty { "(gnome-shell.log is empty)" }
    }.getOrElse { error -> "(gnome-shell.log unreadable: ${error.message})" }

    /**
     * Names the cause when the exit code is one Android produces itself.
     *
     * A killed process reports 128 + signal, and the desktop's usual way to
     * die on Android is not a crash: past `max_phantom_processes` (32 by
     * default) the system SIGKILLs an app's forked children as a group, so
     * the session disappears with no error of its own. Saying so beats
     * showing a log tail full of unrelated GNOME warnings.
     */
    private fun diagnoseSessionDeath(exitCode: Int, ranStage: RendererStage?): String = when {
        exitCode == EXIT_CODE_SIGKILL -> "Android stopped the session's processes — this happens " +
            "when the desktop runs more background programs than Android allows an " +
            "app to keep (its phantom-process limit). Close other apps and start " +
            "again, or use an image built for this limit. Log: "
        exitCode == EXIT_CODE_SIGTERM -> "Android or the system asked the session to stop. Log: "
        ranStage?.usesGpuBridgeRenderer == true ->
            "The GPU-accelerated desktop failed on this device. The next " +
                "start uses the standard renderer automatically — start " +
                "Linux again. Log: "
        exitCode == RendererFallbackLadder.EXIT_CODE_ILLEGAL_INSTRUCTION ->
            "A desktop program used a CPU instruction this device does not " +
                "support. The next start uses a more compatible renderer " +
                "automatically — start Linux again. Log: "
        else -> "Log: "
    }

    /**
     * A fatal desktop death may demand a different renderer next time: any
     * fatal exit abandons the GPU rung, SIGILL walks the CPU rungs. The
     * restart itself is the service's job — this only has to make the next
     * attempt different from the one that just died.
     */
    private fun recordRendererFallbackAfterCrash(exitCode: Int, ranStage: RendererStage?) {
        val rootfsDir = activeRootfsDir ?: return
        val ladder = rendererLadderFor(rootfsDir)
        val nextStage = ladder
            .advanceAfterExit(exitCode, ranStage ?: ladder.currentStage())
            ?: return
        AppLog.warn(
            SCOPE,
            "desktop died (exit $exitCode); next start uses ${nextStage.describe()}",
        )
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
        private val closed = AtomicBoolean(false)

        override fun read(buffer: ByteArray): Int = shell.inputStream.read(buffer)

        override fun write(data: ByteArray) {
            shell.outputStream.write(data)
            shell.outputStream.flush()
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) onClosed()
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
        private const val DISPLAY_BACKEND_MARKER_RELATIVE_PATH =
            "usr/local/share/linux-on-dex/display-backend"
        private const val NATIVE_X11_MARKER = "native-x11"
        private const val XKB_CONFIG_ROOT_RELATIVE_PATH = "usr/share/X11/xkb"
        private const val X11_SOCKET_DIR_NAME = ".X11-unix"

        /**
         * Written by dex-desktop (as /tmp/<name>) when it abandons virpipe
         * mid-session; the path is protocol shared with the rootfs builder.
         */
        private const val GPU_DESKTOP_FAILURE_MARKER_NAME = ".dex-gpu-desktop-failed"

        /** The supervisor redirects GNOME's output here (guest /tmp). */
        private const val GUEST_COMPOSITOR_LOG_NAME = "gnome-shell.log"
        private const val GUEST_LOG_TAIL_BYTES = 1_500

        /** Lets the replacement compositor come up before the viewer refresh. */
        private const val VIEWER_REFRESH_AFTER_RESTART_MILLIS = 3_000L

        /**
         * Renderer strings that reveal CPU rasterization behind the bridge:
         * Mesa's GL and Vulkan software drivers, and ANGLE's own fallback.
         */
        private val SOFTWARE_RENDERER_MARKERS =
            listOf("llvmpipe", "lavapipe", "softpipe", "swiftshader")

        /** 01777 — /tmp semantics (sticky + rwx for everyone). */
        private val WORLD_WRITABLE_STICKY_MODE =
            OsConstants.S_ISVTX or OsConstants.S_IRWXU or
                OsConstants.S_IRWXG or OsConstants.S_IRWXO

        /** Long enough for a broken container to fail, short enough not to stall. */
        private const val CONSOLE_STARTUP_GRACE_MILLIS = 1_500L
        private const val FORCE_KILL_WAIT_SECONDS = 3L
        private const val CONSOLE_FORCE_KILL_WAIT_MILLIS = 500L
        private const val CONSOLE_PROCESS_ID_FILE_PREFIX = "proot-console-"
        private const val CONSOLE_PROCESS_ID_FILE_SUFFIX = ".pid"

        /**
         * GNOME's very first start builds font and icon caches on top of
         * starting X; generous beats a spurious failure on a slow phone.
         */
        private const val DESKTOP_STARTUP_TIMEOUT_MS = 180_000L
        private const val DISPLAY_POLL_INTERVAL_MS = 500L
        private const val VNC_CONNECT_TIMEOUT_MS = 400
        private const val LOG_TAIL_BYTES = 16_000
        private const val GRAPHICS_PROBE_TIMEOUT_SECONDS = 15L
        private const val GRAPHICS_PROBE_LOG_CHARS = 1_000

        /** A killed process reports 128 + the signal number. */
        private const val EXIT_CODE_SIGKILL = 137
        private const val EXIT_CODE_SIGTERM = 143
    }

    private data class ConsoleShellProcess(
        val process: Process,
        val processId: Int,
        val processIdFile: File,
    )
}

/** "1280x800" — the geometry text X servers take. */
private fun ScreenResolution.asGeometryText(): String = "${widthPx}x$heightPx"
