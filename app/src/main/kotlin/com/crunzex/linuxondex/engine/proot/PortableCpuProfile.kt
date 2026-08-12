package com.crunzex.linuxondex.engine.proot

/**
 * A curated /proc/cpuinfo bound over the desktop session's real one.
 *
 * LLVM's AArch64 host detection — which llvmpipe (and every other JIT in
 * the guest) uses to pick its code-generation target — parses this file:
 * the MIDR fields select the CPU model and the `Features` line selects the
 * ISA extensions. Presenting a Cortex-A72 with baseline ARMv8.0 features
 * makes every JIT emit instructions that exist on any modern SoC, which is
 * the fix for llvmpipe's SIGILL on cores LLVM misidentifies.
 *
 * The processor count still matches the device so `nproc`-style tools keep
 * reporting sensible parallelism (schedulers use sched_getaffinity, not
 * this file, so performance is unaffected).
 */
object PortableCpuProfile {

    /** Cortex-A72: old enough to be universal, new enough to have NEON+CRC. */
    private const val CPU_IMPLEMENTER_ARM = "0x41"
    private const val CPU_PART_CORTEX_A72 = "0xd08"
    private const val BASELINE_FEATURES = "fp asimd evtstrm crc32 cpuid"

    fun cpuinfoText(processorCount: Int): String {
        require(processorCount > 0) { "processorCount must be positive" }
        return buildString {
            repeat(processorCount) { index ->
                appendLine("processor\t: $index")
                appendLine("BogoMIPS\t: 108.00")
                appendLine("Features\t: $BASELINE_FEATURES")
                appendLine("CPU implementer\t: $CPU_IMPLEMENTER_ARM")
                appendLine("CPU architecture: 8")
                appendLine("CPU variant\t: 0x0")
                appendLine("CPU part\t: $CPU_PART_CORTEX_A72")
                appendLine("CPU revision\t: 3")
                appendLine()
            }
        }
    }
}
