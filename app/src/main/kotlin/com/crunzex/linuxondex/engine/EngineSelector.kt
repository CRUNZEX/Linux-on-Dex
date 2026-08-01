package com.crunzex.linuxondex.engine

import com.crunzex.linuxondex.capability.DeviceCapabilities
import com.crunzex.linuxondex.capability.KvmAccess

/**
 * The three supported ways to run Linux, ranked by how complete a system
 * each one gives — which is not the same as how fast it feels.
 *
 * 1. [QEMU_KVM]  — hardware-accelerated full VM. Needs /dev/kvm, which stock
 *                  Samsung firmware currently denies to apps; present for
 *                  rooted devices and future AVF-enabled firmware.
 * 2. [QEMU_TCG]  — software full VM (multi-threaded TCG). Works on any
 *                  Android 13-16 device without special permissions, boots
 *                  unmodified ARM64 Linux ISOs.
 * 3. [PROOT]     — syscall-translation container: no guest kernel and no ISO
 *                  boot, so it ranks last for generality. It is however the
 *                  only option that runs guest code at **native CPU speed**,
 *                  which is why the ready-made desktop images use it — a
 *                  container image selects it regardless of this ranking.
 */
enum class EngineKind(
    val displayName: String,
    val shortDescription: String,
    /**
     * How long to let the guest shut itself down before forcing it.
     *
     * Software emulation runs an order of magnitude slower than hardware, so
     * a guest that powers off in seconds natively needs minutes here.
     * Forcing too early risks corrupting the guest filesystem mid-flush.
     */
    val shutdownGraceSeconds: Int,
) {
    QEMU_KVM("Hardware VM", "QEMU with KVM acceleration", shutdownGraceSeconds = 45),
    QEMU_TCG("Software VM", "QEMU full-system emulation", shutdownGraceSeconds = 180),
    // A graphical session needs longer than a shell to wind down: the
    // supervisor stops GNOME, the X server and sshd before it exits.
    PROOT("Native container", "PRoot at full CPU speed — no emulation", shutdownGraceSeconds = 30),
}

/** Whether an engine can run here, and if not, exactly why. */
sealed class EngineAvailability {
    data object Available : EngineAvailability()
    data class Unavailable(val reason: String) : EngineAvailability()

    val isAvailable: Boolean get() = this is Available
}

data class EngineCandidate(
    val kind: EngineKind,
    val availability: EngineAvailability,
)

/**
 * Pure decision logic mapping [DeviceCapabilities] to a ranked engine list.
 * No Android dependencies — covered by JVM unit tests.
 */
object EngineSelector {

    /** Guests need ~2 GiB and Android needs to keep breathing. */
    const val MIN_TOTAL_RAM_MB_FOR_VM = 3 * 1024

    /** All engines in preference order with availability verdicts. */
    fun rank(capabilities: DeviceCapabilities): List<EngineCandidate> = listOf(
        EngineCandidate(EngineKind.QEMU_KVM, checkQemuKvm(capabilities)),
        EngineCandidate(EngineKind.QEMU_TCG, checkQemuTcg(capabilities)),
        EngineCandidate(EngineKind.PROOT, checkProot(capabilities)),
    )

    /** Best available engine, or null when nothing can run. */
    fun selectBest(capabilities: DeviceCapabilities): EngineCandidate? =
        rank(capabilities).firstOrNull { it.availability.isAvailable }

    private fun checkQemuKvm(caps: DeviceCapabilities): EngineAvailability {
        commonQemuRequirement(caps)?.let { return it }
        return when (caps.kvm) {
            KvmAccess.USABLE -> EngineAvailability.Available
            KvmAccess.DENIED -> EngineAvailability.Unavailable(
                "/dev/kvm exists but access is denied by the firmware " +
                    "(expected on stock Samsung Android 13-16)"
            )
            KvmAccess.ABSENT -> EngineAvailability.Unavailable(
                "Kernel does not expose /dev/kvm"
            )
        }
    }

    private fun checkQemuTcg(caps: DeviceCapabilities): EngineAvailability {
        commonQemuRequirement(caps)?.let { return it }
        if (caps.totalRamMb in 1 until MIN_TOTAL_RAM_MB_FOR_VM) {
            return EngineAvailability.Unavailable(
                "Device has ${caps.totalRamMb} MB RAM; a full VM needs at " +
                    "least $MIN_TOTAL_RAM_MB_FOR_VM MB total"
            )
        }
        return EngineAvailability.Available
    }

    private fun checkProot(caps: DeviceCapabilities): EngineAvailability = when {
        !caps.isArm64 -> EngineAvailability.Unavailable("Requires an ARM64 device")
        !caps.canForkExec -> EngineAvailability.Unavailable(
            "Process creation is blocked for this app"
        )
        !caps.prootPayloadPresent -> EngineAvailability.Unavailable(
            "PRoot runtime is not packaged in this build"
        )
        else -> EngineAvailability.Available
    }

    /** Requirements shared by both QEMU engines; null when satisfied. */
    private fun commonQemuRequirement(caps: DeviceCapabilities): EngineAvailability.Unavailable? = when {
        !caps.isArm64 -> EngineAvailability.Unavailable("Requires an ARM64 device")
        !caps.canForkExec -> EngineAvailability.Unavailable(
            "Process creation is blocked for this app"
        )
        !caps.qemuPayloadPresent -> EngineAvailability.Unavailable(
            "QEMU runtime is not packaged in this build " +
                "(run tools/fetch_qemu_payload.sh before building)"
        )
        else -> null
    }
}
