package com.crunzex.linuxondex.vm

import com.crunzex.linuxondex.core.LxdError
import com.crunzex.linuxondex.engine.EngineKind

/**
 * Lifecycle of a running VM as observed by the UI.
 *
 * Legal transitions:
 * ```
 * Idle → Preparing → Starting → Running → Stopping → Stopped → (Idle)
 *          ↘Failed     ↘Failed    ↘Failed(crash)
 * ```
 */
sealed class VmState {
    /** No VM process; nothing prepared yet. */
    data object Idle : VmState()

    /** Payload/disk/firmware being prepared. [stepDescription] is user-visible. */
    data class Preparing(val stepDescription: String) : VmState()

    /** Process spawned; waiting for the control channel to come up. */
    data class Starting(val engine: EngineKind) : VmState()

    data class Running(
        val engine: EngineKind,
        val vncPort: Int?,
        val startedAtMillis: Long,
        /** How the graphical session reaches Android; null for console-only guests. */
        val displayEndpoint: DisplayEndpoint? = vncPort?.let(DisplayEndpoint::Rfb),
    ) : VmState()

    data object Stopping : VmState()

    data class Stopped(val reason: StopReason) : VmState()

    data class Failed(val error: LxdError) : VmState()

    val isBusy: Boolean
        get() = this is Preparing || this is Starting || this is Stopping

    val isRunning: Boolean get() = this is Running
}

/**
 * Display transport is part of runtime state, not inferred from an engine.
 * This keeps QEMU on its RFB server while allowing new PRoot images to use
 * the embedded native X server. Older PRoot images may still report RFB.
 */
sealed interface DisplayEndpoint {
    data class Rfb(val port: Int) : DisplayEndpoint
    data class NativeX11(val displayNumber: Int) : DisplayEndpoint
}

enum class StopReason {
    /** User asked; guest powered down cleanly. */
    USER_REQUESTED,
    /** Guest initiated its own shutdown (e.g. `poweroff` inside Linux). */
    GUEST_SHUTDOWN,
    /** We gave up waiting and killed the process. */
    FORCED,
}
