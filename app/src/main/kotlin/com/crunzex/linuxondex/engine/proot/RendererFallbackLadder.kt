package com.crunzex.linuxondex.engine.proot

import java.io.File

/**
 * How the desktop is allowed to render on this device, from fastest to most
 * compatible. The ladder exists because llvmpipe JIT-compiles shaders for
 * the *detected* CPU, and on some SoCs (Tab S9's Snapdragon 8 Gen 2 was the
 * report) that codegen uses an instruction the cores do not implement —
 * GNOME then dies with SIGILL (exit 132) a few seconds into every start.
 */
enum class RendererStage {
    /** llvmpipe against the real CPU — full speed, works on most devices. */
    NATIVE,

    /**
     * llvmpipe, but the session sees a curated /proc/cpuinfo describing a
     * conservative ARMv8.0 core (Cortex-A72). LLVM derives its JIT target
     * from that file, so generated code sticks to baseline instructions
     * every modern SoC implements — near-full llvmpipe speed, no SIGILL.
     */
    PORTABLE_CPU,

    /** softpipe: plain C rasterizer, no JIT at all. Slow but unbreakable. */
    FAILSAFE_SOFTPIPE;

    val usesPortableCpuProfile: Boolean get() = this != NATIVE
    val usesSoftpipeRenderer: Boolean get() = this == FAILSAFE_SOFTPIPE

    fun describe(): String = when (this) {
        NATIVE -> "native llvmpipe"
        PORTABLE_CPU -> "llvmpipe with the portable CPU profile"
        FAILSAFE_SOFTPIPE -> "failsafe softpipe"
    }
}

/**
 * Sticky renderer selection for one extracted image.
 *
 * The stage only ever advances on SIGILL (the crash this ladder exists
 * for), and it advances at most one step per session so an unrelated later
 * crash cannot skip straight to the slow failsafe. It is remembered per
 * rootfs stamp: a re-imported or updated image starts again at [NATIVE],
 * because the new image may not have the problem.
 */
class RendererFallbackLadder(
    private val stateFile: File,
    private val rootfsStamp: String,
) {

    fun currentStage(): RendererStage {
        val state = readState() ?: return RendererStage.NATIVE
        if (state.stamp != rootfsStamp) return RendererStage.NATIVE
        return state.stage
    }

    /**
     * Advances after a desktop death when the exit code demands it.
     * Returns the stage the *next* session should use, or null when the
     * ladder has no answer (not a SIGILL, or already at the last stage).
     */
    fun advanceAfterExit(exitCode: Int): RendererStage? {
        if (exitCode != EXIT_CODE_ILLEGAL_INSTRUCTION) return null
        val next = when (currentStage()) {
            RendererStage.NATIVE -> RendererStage.PORTABLE_CPU
            RendererStage.PORTABLE_CPU -> RendererStage.FAILSAFE_SOFTPIPE
            RendererStage.FAILSAFE_SOFTPIPE -> return null
        }
        writeState(next)
        return next
    }

    private data class State(val stage: RendererStage, val stamp: String)

    private fun readState(): State? = runCatching {
        val lines = stateFile.takeIf(File::isFile)?.readLines() ?: return null
        val stage = lines.getOrNull(0)
            ?.let { name -> RendererStage.entries.firstOrNull { it.name == name } }
            ?: return null
        val stamp = lines.getOrNull(1) ?: return null
        State(stage, stamp)
    }.getOrNull()

    private fun writeState(stage: RendererStage) {
        runCatching {
            stateFile.parentFile?.mkdirs()
            stateFile.writeText("${stage.name}\n$rootfsStamp\n")
        }
    }

    companion object {
        /** A killed process reports 128 + SIGILL(4). */
        const val EXIT_CODE_ILLEGAL_INSTRUCTION = 132
    }
}
