package com.crunzex.linuxondex.engine.runtime

import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * The slice of process control the VM engine actually uses.
 *
 * A virtual machine is started one of two ways: by [ProcessBuilder] for an
 * ordinary boot, or by [NativeLauncher] when a USB device has to be handed
 * to the guest (only the native launcher can keep that descriptor open
 * across exec). Both look the same from here, so the engine's lifecycle
 * code stays in one place.
 */
interface LaunchedProcess {
    /** stdout and stderr, merged, for the VM log. */
    val output: InputStream

    val isAlive: Boolean

    /** Blocks until the process exits, returning its status. */
    fun waitFor(): Int

    /** Waits up to the timeout; true when the process has exited. */
    fun waitFor(timeout: Long, unit: TimeUnit): Boolean

    fun destroyForcibly()
}

/** An ordinary [Process] from [ProcessBuilder]. */
class JavaLaunchedProcess(private val process: Process) : LaunchedProcess {
    override val output: InputStream get() = process.inputStream
    override val isAlive: Boolean get() = process.isAlive
    override fun waitFor(): Int = process.waitFor()
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = process.waitFor(timeout, unit)
    override fun destroyForcibly() {
        process.destroyForcibly()
    }
}
