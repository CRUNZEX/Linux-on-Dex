package com.crunzex.linuxondex.engine.runtime

import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.core.LxdError
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One command line to exec, with its environment. Pure data + a runner, so
 * engines can build commands in unit-testable code and only the runner
 * touches the OS.
 */
data class NativeCommand(
    val program: File,
    val arguments: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: File? = null,
) {
    /** Human-readable single line for logs. */
    fun describe(): String =
        (listOf(program.name) + arguments).joinToString(" ")

    fun start(redirectErrorStream: Boolean = true): Process {
        if (!program.exists()) {
            throw LxdError.PayloadMissing(program.name)
        }
        val builder = ProcessBuilder(listOf(program.absolutePath) + arguments)
            .redirectErrorStream(redirectErrorStream)
        workingDirectory?.let { builder.directory(it) }
        builder.environment().putAll(environment)
        AppLog.info(SCOPE, "exec: ${describe()}")
        return try {
            builder.start()
        } catch (error: Exception) {
            throw LxdError.ProcessSpawnFailed(describe(), error)
        }
    }

    /**
     * Run to completion for short-lived commands (`--version`, `qemu-img
     * create`). Long-lived VM processes use [start] and manage their own
     * lifecycle.
     */
    fun runAndCaptureOutput(timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): CommandResult {
        val process = start(redirectErrorStream = true)
        val output = StringBuilder()
        val reader = process.inputStream.bufferedReader()
        val pump = Thread {
            reader.forEachLine { line ->
                if (output.length < MAX_CAPTURED_OUTPUT_CHARS) {
                    output.appendLine(line)
                }
            }
        }.apply { isDaemon = true; start() }

        val finishedInTime = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finishedInTime) {
            process.destroyForcibly()
            throw LxdError.ProcessSpawnFailed(
                "${describe()} (timed out after ${timeoutSeconds}s)"
            )
        }
        pump.join(OUTPUT_JOIN_MILLIS)
        return CommandResult(process.exitValue(), output.toString().trim())
    }

    companion object {
        private const val SCOPE = "NativeCommand"
        private const val DEFAULT_TIMEOUT_SECONDS = 30L
        private const val MAX_CAPTURED_OUTPUT_CHARS = 64_000
        private const val OUTPUT_JOIN_MILLIS = 2_000L
    }
}

data class CommandResult(val exitCode: Int, val output: String) {
    val isSuccess: Boolean get() = exitCode == 0

    fun requireSuccess(context: String): CommandResult {
        if (!isSuccess) {
            throw LxdError.ProcessSpawnFailed(
                "$context failed (exit $exitCode): ${output.take(500)}"
            )
        }
        return this
    }
}
