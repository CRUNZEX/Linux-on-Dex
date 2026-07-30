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
import com.crunzex.linuxondex.engine.runtime.NativeCommand
import com.crunzex.linuxondex.engine.runtime.PayloadInstaller
import com.crunzex.linuxondex.engine.runtime.VmPaths
import com.crunzex.linuxondex.vm.StopReason
import com.crunzex.linuxondex.vm.VmConfig
import com.crunzex.linuxondex.vm.VmState
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * Approach 3 — compatibility fallback. Runs an Alpine userland through
 * PRoot's syscall translation: no VM, no ISO boot, no kernel of its own,
 * but it works even where QEMU cannot (very low RAM, future policy changes).
 *
 * The shell session is exposed through the same [SerialConsoleConnection]
 * interface the QEMU serial console uses, so the terminal UI is shared.
 */
class ProotEngine(
    private val paths: VmPaths,
    private val payloadInstaller: PayloadInstaller,
) : VirtualizationEngine {

    override val kind: EngineKind = EngineKind.PROOT

    private val _state = MutableStateFlow<VmState>(VmState.Idle)
    override val state: StateFlow<VmState> = _state.asStateFlow()

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val shutdownInitiated = AtomicBoolean(false)
    private var shellProcess: Process? = null

    override suspend fun start(config: VmConfig) = lifecycleMutex.withLock {
        check(!_state.value.isBusy && !_state.value.isRunning) {
            "start() called while ${_state.value}"
        }
        withContext(Dispatchers.IO) {
            try {
                startLocked()
            } catch (error: Exception) {
                val lxdError = error as? LxdError
                    ?: LxdError.BootFailed("PRoot startup failed: ${error.message}", error)
                _state.value = VmState.Failed(lxdError)
                throw lxdError
            }
        }
    }

    private fun startLocked() {
        shutdownInitiated.set(false)

        _state.value = VmState.Preparing("Checking runtime")
        paths.createRuntimeDirectories()
        payloadInstaller.ensureInstalled()

        _state.value = VmState.Preparing("Extracting Linux userland")
        ensureRootfsExtracted()

        _state.value = VmState.Starting(kind)
        val process = buildShellCommand().start(redirectErrorStream = false)
        shellProcess = process
        watchProcessExit(process)

        _state.value = VmState.Running(
            engine = kind,
            vncPort = null, // terminal-only engine
            startedAtMillis = System.currentTimeMillis(),
        )
        AppLog.info(SCOPE, "PRoot shell session started")
    }

    /**
     * The rootfs tar ships in assets; extraction happens once and is stamped
     * by the presence of /bin/busybox inside the target directory.
     */
    private fun ensureRootfsExtracted() {
        val rootfsDir = paths.prootRootfsDir
        val busybox = rootfsDir.resolve("bin/busybox")
        if (busybox.exists()) return

        val installedArchive = paths.vmRootDir.resolve(ROOTFS_ARCHIVE_RELATIVE_PATH)
        if (!installedArchive.exists()) {
            throw LxdError.PayloadMissing(installedArchive.name)
        }
        installedArchive.inputStream().buffered().use { archive ->
            TarExtractor.extract(archive, rootfsDir)
        }
        if (!busybox.exists()) {
            throw LxdError.PayloadCorrupted("rootfs extracted but bin/busybox is missing")
        }
        writeDefaultDnsConfig()
    }

    /** PRoot guests have no DHCP; give the resolver a sane default. */
    private fun writeDefaultDnsConfig() {
        runCatching {
            val etcDir = paths.prootRootfsDir.resolve("etc").apply { mkdirs() }
            etcDir.resolve("resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
    }

    private fun buildShellCommand(): NativeCommand {
        val rootfs = paths.prootRootfsDir.absolutePath
        return NativeCommand(
            program = paths.prootBinary,
            arguments = listOf(
                "--kill-on-exit",
                "-r", rootfs,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-w", "/root",
                "/bin/sh", "-l",
            ),
            environment = paths.processEnvironment() + mapOf(
                "PROOT_LOADER" to paths.prootLoaderBinary.absolutePath,
                "PROOT_TMP_DIR" to paths.tmpDir.absolutePath,
                "PATH" to GUEST_PATH,
                "TERM" to "xterm-256color",
            ),
            workingDirectory = paths.vmRootDir,
        )
    }

    override suspend fun stop(gracePeriod: Duration): Unit = lifecycleMutex.withLock {
        withContext(Dispatchers.IO) {
            val process = shellProcess
            if (process == null || !process.isAlive) {
                _state.value = VmState.Stopped(StopReason.USER_REQUESTED)
                return@withContext
            }
            _state.value = VmState.Stopping
            shutdownInitiated.set(true)
            process.destroy() // SIGTERM; proot forwards to the shell
            val exited = process.waitFor(gracePeriod.inWholeSeconds, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                process.waitFor(FORCE_KILL_WAIT_SECONDS, TimeUnit.SECONDS)
            }
            shellProcess = null
            _state.value = VmState.Stopped(
                if (exited) StopReason.USER_REQUESTED else StopReason.FORCED
            )
        }
    }

    override suspend fun forceStop(): Unit = stop(gracePeriod = Duration.ZERO)

    override fun openSerialConsole(): SerialConsoleConnection? {
        val process = shellProcess ?: return null
        if (!process.isAlive) return null
        return PipeConsole(process)
    }

    private fun watchProcessExit(process: Process) {
        engineScope.launch {
            val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
            if (shutdownInitiated.get()) return@launch
            if (_state.value.isRunning) {
                // `exit` inside the shell is a normal way to leave the session.
                AppLog.info(SCOPE, "shell exited with code $exitCode")
                _state.value = VmState.Stopped(StopReason.GUEST_SHUTDOWN)
            }
        }
    }

    /** Console over the process pipes (no pty: line-buffered but usable). */
    private class PipeConsole(private val process: Process) : SerialConsoleConnection {
        override fun read(buffer: ByteArray): Int = process.inputStream.read(buffer)

        override fun write(data: ByteArray) {
            process.outputStream.write(data)
            process.outputStream.flush()
        }

        override fun close() {
            // The console detaching must not kill the session; nothing to do.
        }
    }

    companion object {
        private const val SCOPE = "ProotEngine"
        private const val ROOTFS_ARCHIVE_RELATIVE_PATH = "proot/alpine-minirootfs-aarch64.tar"
        private const val FORCE_KILL_WAIT_SECONDS = 3L
        private const val GUEST_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    }
}
