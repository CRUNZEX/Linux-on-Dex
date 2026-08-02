package com.crunzex.linuxondex.service

import com.crunzex.linuxondex.engine.EngineKind

/**
 * Bounded recovery budget for an unexpectedly terminated PRoot desktop.
 *
 * Full-system VMs are deliberately excluded: automatically restarting QEMU
 * after a crash could replay writes against a damaged disk. PRoot has no
 * guest block device, so restarting its runtime is safe and preserves the
 * extracted root filesystem.
 */
internal class ProotRecoveryPolicy(
    private val retryDelaysMillis: List<Long> = DEFAULT_RETRY_DELAYS_MILLIS,
) {
    private var consumedAttempts = 0

    /** Returns the next delay, or null when this runtime must stay stopped. */
    fun nextDelayMillis(engineKind: EngineKind?): Long? {
        if (engineKind != EngineKind.PROOT) return null
        return retryDelaysMillis.getOrNull(consumedAttempts++)
    }

    companion object {
        private val DEFAULT_RETRY_DELAYS_MILLIS = listOf(1_000L, 3_000L)
    }
}
