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

    /**
     * Refills the budget once a recovered session proves healthy. Without
     * this a service process that lives for weeks would spend its attempts
     * one unrelated crash at a time and eventually refuse to recover at
     * all. Deliberately NOT called on every Running edge: the renderer
     * ladder needs consecutive quick crashes to share one budget, so the
     * caller only reports health after a sustained run.
     */
    fun noteSessionStayedHealthy() {
        consumedAttempts = 0
    }

    companion object {
        /**
         * Three attempts: the SIGILL renderer ladder consumes up to two
         * (native → portable CPU → failsafe), leaving one for an ordinary
         * transient crash on top.
         */
        private val DEFAULT_RETRY_DELAYS_MILLIS = listOf(1_000L, 3_000L, 5_000L)
    }
}
