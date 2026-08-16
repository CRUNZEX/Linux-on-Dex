package com.crunzex.linuxondex.engine.proot

import java.io.File

/**
 * How the desktop is allowed to render on this device, from fastest to most
 * compatible.
 *
 * The top rung offloads the whole session to the device GPU through the
 * app's virgl bridge. The CPU rungs below exist because llvmpipe
 * JIT-compiles shaders for the *detected* CPU, and on some SoCs (Tab S9's
 * Snapdragon 8 Gen 2 was the report) that codegen uses an instruction the
 * cores do not implement — GNOME then dies with SIGILL (exit 132) a few
 * seconds into every start.
 */
enum class RendererStage {
    /**
     * The whole session renders on the device GPU: Mesa's virpipe driver
     * forwards GL to the app's Android-side virgl renderer. Fastest, and
     * the only rung that is also gated per boot — the app must verify the
     * bridge end-to-end before a session may select it.
     */
    GPU_VIRGL,

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

    val usesGpuBridgeRenderer: Boolean get() = this == GPU_VIRGL
    val usesPortableCpuProfile: Boolean get() = this == PORTABLE_CPU || this == FAILSAFE_SOFTPIPE
    val usesSoftpipeRenderer: Boolean get() = this == FAILSAFE_SOFTPIPE

    fun describe(): String = when (this) {
        GPU_VIRGL -> "Android GPU (virgl bridge)"
        NATIVE -> "native llvmpipe"
        PORTABLE_CPU -> "llvmpipe with the portable CPU profile"
        FAILSAFE_SOFTPIPE -> "failsafe softpipe"
    }
}

/**
 * Sticky renderer selection for one extracted image.
 *
 * Two different fallback rules, because the rungs fail differently:
 *
 *  - [RendererStage.GPU_VIRGL] is abandoned after *any* fatal desktop exit —
 *    a GPU-bridge failure shows up as whatever error the compositor happens
 *    to die with, so waiting specifically for SIGILL would retry a broken
 *    GPU forever. Exits caused from outside the session (SIGKILL's 137 from
 *    Android's phantom-process limit, SIGTERM's 143 from the system) say
 *    nothing about the renderer and never advance the ladder.
 *  - The CPU rungs advance only on SIGILL (exit 132), the crash signature
 *    of llvmpipe's JIT — any other failure is not the renderer's fault.
 *
 * The ladder advances at most one rung per session, and it is remembered
 * per rootfs stamp: a re-imported or updated image starts again at the
 * top, because the new image may not have the problem.
 */
class RendererFallbackLadder(
    private val stateFile: File,
    private val rootfsStamp: String,
) {

    fun currentStage(): RendererStage {
        val state = readState() ?: return firstStage()
        if (state.stamp != rootfsStamp) return firstStage()
        return state.stage
    }

    /**
     * Advances after a desktop death when the exit code demands it, from
     * the stage that was actually running (which can sit above the sticky
     * one when the GPU rung was skipped for a boot). Returns the stage the
     * *next* session should use, or null when this death does not change
     * the renderer choice.
     */
    fun advanceAfterExit(exitCode: Int, ranStage: RendererStage = currentStage()): RendererStage? {
        val next = when {
            exitCode == EXIT_CODE_KILLED || exitCode == EXIT_CODE_TERMINATED -> return null
            exitCode <= 0 -> return null
            ranStage == RendererStage.GPU_VIRGL -> RendererStage.NATIVE
            exitCode != EXIT_CODE_ILLEGAL_INSTRUCTION -> return null
            ranStage == RendererStage.NATIVE -> RendererStage.PORTABLE_CPU
            ranStage == RendererStage.PORTABLE_CPU -> RendererStage.FAILSAFE_SOFTPIPE
            else -> return null
        }
        writeState(next)
        return next
    }

    /**
     * Records that the GPU rung failed *inside* a session (the supervisor
     * abandoned virpipe and finished the session on llvmpipe, so no exit
     * code ever reported it). No-op once the ladder is already below GPU.
     */
    fun stepDownFromGpuStage(): RendererStage? {
        if (currentStage() != RendererStage.GPU_VIRGL) return null
        writeState(RendererStage.NATIVE)
        return RendererStage.NATIVE
    }

    private fun firstStage(): RendererStage = RendererStage.entries.first()

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
        /**
         * Forgets a remembered downgrade so the next start tries the top
         * rung again — the affordance behind re-enabling GPU rendering in
         * settings, which has the state file but no rootfs stamp at hand.
         */
        fun forgetStoredStage(stateFile: File) {
            stateFile.delete()
        }

        /** A killed process reports 128 + SIGILL(4). */
        const val EXIT_CODE_ILLEGAL_INSTRUCTION = 132

        /** 128 + SIGKILL(9): Android's phantom-process enforcement. */
        private const val EXIT_CODE_KILLED = 137

        /** 128 + SIGTERM(15): an orderly stop requested from outside. */
        private const val EXIT_CODE_TERMINATED = 143
    }
}
