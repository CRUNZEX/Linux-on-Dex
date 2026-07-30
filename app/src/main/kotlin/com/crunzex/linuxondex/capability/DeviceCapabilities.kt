package com.crunzex.linuxondex.capability

/**
 * Snapshot of everything about the device that decides which virtualization
 * approach can work. Produced by [CapabilityProbe]; consumed by
 * [com.crunzex.linuxondex.engine.EngineSelector].
 *
 * Pure data so engine-selection logic stays unit-testable off-device.
 */
data class DeviceCapabilities(
    /** Android API level (33..36 is the supported window). */
    val apiLevel: Int,
    /** True when the primary ABI is arm64-v8a. */
    val isArm64: Boolean,
    /** Result of actually trying to open /dev/kvm read-write. */
    val kvm: KvmAccess,
    /** Android Virtualization Framework present (pKVM devices, API 34+). */
    val hasVirtualizationFramework: Boolean,
    /** True when fork/exec of a trivial binary works in this process. */
    val canForkExec: Boolean,
    /** True when the QEMU payload is packaged inside this APK. */
    val qemuPayloadPresent: Boolean,
    /** True when the PRoot payload is packaged inside this APK. */
    val prootPayloadPresent: Boolean,
    /** Physical RAM in MiB — decides how much we can give the guest. */
    val totalRamMb: Int,
    /** Samsung DeX desktop mode active right now (affects UI only). */
    val isDexModeActive: Boolean,
    /** Device model, for logs and support hints (e.g. "SM-S938B"). */
    val deviceModel: String,
) {
    companion object {
        const val MIN_SUPPORTED_API = 33 // Android 13
        const val MAX_TESTED_API = 36    // Android 16
    }
}

/** Outcome of probing /dev/kvm. */
enum class KvmAccess {
    /** Node exists and this app can open it read-write. */
    USABLE,
    /** Node exists but SELinux/permissions deny us — common on stock firmware. */
    DENIED,
    /** Node does not exist at all. */
    ABSENT,
}
