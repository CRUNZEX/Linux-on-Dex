package com.crunzex.linuxondex.engine

import kotlinx.coroutines.flow.StateFlow
import com.crunzex.linuxondex.vm.VmConfig
import com.crunzex.linuxondex.vm.VmState
import kotlin.time.Duration

/**
 * A way to run Linux. Implementations: QEMU (KVM or TCG accel) and PRoot.
 *
 * Engines are single-VM: one instance manages one guest process at a time.
 * All methods are safe to call from any coroutine context.
 */
interface VirtualizationEngine {
    val kind: EngineKind

    val state: StateFlow<VmState>

    /**
     * Bring the VM up. Moves state through Preparing → Starting → Running.
     * On failure the state is [VmState.Failed] and this function throws the
     * same [com.crunzex.linuxondex.core.LxdError].
     */
    suspend fun start(config: VmConfig)

    /**
     * Graceful shutdown: asks the guest to power down, escalating to a hard
     * kill after [gracePeriod]. Always ends in Stopped.
     */
    suspend fun stop(gracePeriod: Duration)

    /** Immediate hard kill. */
    suspend fun forceStop()

    /**
     * OS process id of the running VM, for host-side resource monitoring.
     * Null when the engine has no measurable process (not running).
     */
    fun vmProcessId(): Int? = null

    /**
     * Opens console [index]: 0 is the primary console, higher indices are
     * additional independent consoles when the engine provides them.
     * Defaults to the primary console so engines without extras still work.
     */
    fun openConsole(index: Int): SerialConsoleConnection? =
        if (index <= 0) openSerialConsole() else null

    /**
     * Byte-level access to the guest's serial console once Running.
     * Null when the engine has no console (or the VM is not up yet).
     */
    fun openSerialConsole(): SerialConsoleConnection?
}

/** Bidirectional raw console stream (VT100 bytes in both directions). */
interface SerialConsoleConnection : AutoCloseable {
    /** Blocking read into [buffer]; returns byte count or -1 on EOF. */
    fun read(buffer: ByteArray): Int

    /** Sends user keystrokes to the guest. */
    fun write(data: ByteArray)
}
