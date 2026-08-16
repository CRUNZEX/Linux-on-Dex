package com.crunzex.linuxondex.engine.proot

import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.engine.runtime.NativeCommand
import com.crunzex.linuxondex.engine.runtime.VmPaths
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Host half of PRoot 3D acceleration.
 *
 * The server is an Android/NDK executable, so ANGLE can call Samsung's public
 * EGL/GLES stack using Bionic. Linux Mesa stays inside PRoot and talks to it
 * through virpipe's Unix socket. That boundary avoids both blocked GPU device
 * nodes and the impossible attempt to load an Android driver into glibc.
 *
 * Failure is deliberately non-fatal: a desktop is still useful with
 * llvmpipe, while a graphics bridge must never take DeX down with it.
 */
class AndroidVirglBridge(private val paths: VmPaths) {

    /**
     * Probe order matters: ANGLE over the device's Vulkan driver comes
     * first. Real-device vendor EGL stacks accepted the tiny verification
     * probe but corrupted large frames afterwards — a Mali phone rendered
     * exactly 481 rows of every desktop frame through system EGL, and a
     * Tab S9 lost GNOME to SIGSEGV on the same path — while ANGLE is the
     * compatibility layer built to normalise exactly these differences.
     * On hosts whose Vulkan is software (the emulator's lavapipe), ANGLE
     * is rejected by the hardware policy and the ladder continues.
     */
    enum class Backend(
        val displayName: String,
        val serverArguments: List<String>,
    ) {
        ANGLE_VULKAN("bundled ANGLE Vulkan", listOf("--angle-vulkan")),
        SYSTEM_EGL("Android EGL", emptyList()),
        ANGLE_GL("bundled ANGLE OpenGL", listOf("--angle-gl")),
    }

    private var rendererProcess: Process? = null

    val isRunning: Boolean
        get() = rendererProcess?.isAlive == true && paths.virglSocket.exists()

    /** Start one backend and prove that it published its socket. */
    fun start(backend: Backend = Backend.SYSTEM_EGL): Boolean {
        stop()
        if (!paths.virglRendererBinary.exists()) {
            AppLog.warn(SCOPE, "native virgl renderer is not present in the payload")
            return false
        }

        paths.socketsDir.mkdirs()
        paths.virglSocket.delete()
        bridgeLogFile().delete()

        return runCatching {
            val process = NativeCommand(
                program = paths.virglRendererBinary,
                arguments = backend.serverArguments + listOf(
                    "--no-fork",
                    "--multi-clients",
                    "--socket-path", paths.virglSocket.absolutePath,
                ),
                environment = paths.processEnvironment(),
                workingDirectory = paths.vmRootDir,
            ).start(redirectErrorStream = true)
            rendererProcess = process
            pumpOutput(process, bridgeLogFile())

            if (!waitForSocket(process)) {
                val reason = readLogTail(bridgeLogFile())
                stop()
                AppLog.warn(SCOPE, "native virgl renderer did not become ready: $reason")
                false
            } else {
                AppLog.info(
                    SCOPE,
                    "native virgl renderer (${backend.displayName}) ready at ${paths.virglSocket}",
                )
                true
            }
        }.getOrElse { error ->
            stop()
            AppLog.warn(SCOPE, "native virgl renderer could not start", error)
            false
        }
    }

    /** Idempotent teardown for normal stop, failed boot, and memory pressure. */
    fun stop() {
        rendererProcess?.let { process ->
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(STOP_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                }
            }
        }
        rendererProcess = null
        paths.virglSocket.delete()
    }

    private fun waitForSocket(process: Process): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(START_TIMEOUT_MILLIS)
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) return false
            if (paths.virglSocket.exists()) return true
            Thread.sleep(READY_POLL_MILLIS)
        }
        return false
    }

    private fun pumpOutput(process: Process, logFile: File) {
        Thread({
            runCatching {
                logFile.outputStream().bufferedWriter().use { output ->
                    process.inputStream.bufferedReader().forEachLine { line ->
                        output.appendLine(line)
                        output.flush()
                    }
                }
            }
        }, "linux-on-dex-virgl-log").apply {
            isDaemon = true
            start()
        }
    }

    private fun bridgeLogFile(): File = paths.logsDir.resolve(BRIDGE_LOG_FILE_NAME)

    private fun readLogTail(logFile: File): String = runCatching {
        if (!logFile.exists()) return@runCatching "no renderer log"
        val text = logFile.readText()
        text.takeLast(MAX_LOG_TAIL_CHARS).ifBlank { "renderer log is empty" }
    }.getOrElse { "renderer log is unreadable: ${it.message}" }

    companion object {
        private const val SCOPE = "AndroidVirglBridge"
        private const val BRIDGE_LOG_FILE_NAME = "virgl-renderer.log"
        private const val START_TIMEOUT_MILLIS = 5_000L
        private const val READY_POLL_MILLIS = 50L
        private const val STOP_WAIT_MILLIS = 500L
        private const val MAX_LOG_TAIL_CHARS = 2_000
    }
}
