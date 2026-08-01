package com.crunzex.linuxondex.capability

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import com.crunzex.linuxondex.core.AppLog
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Detects, at runtime, which virtualization building blocks this device
 * actually allows. Every probe is defensive: a probe may fail, but probing
 * itself never throws — an unknown always degrades to "not available".
 *
 * This replaces the old Samsung system-API path (`ACCESS_NST` + Knox
 * container daemon) which no longer exists on Android 13-16 firmware.
 */
class CapabilityProbe(private val context: Context) {

    /**
     * The last snapshot and when it was taken. Probing forks a process and
     * opens device nodes, so it is far too expensive to repeat per UI read —
     * and the screens ask for it often. Guarded by [cacheLock] because the
     * UI, the view model and the VM controller all probe from their own
     * threads.
     */
    private val cacheLock = Any()
    private var cachedCapabilities: DeviceCapabilities? = null
    private var cachedAtElapsedMillis = 0L
    private var lastLoggedCapabilities: DeviceCapabilities? = null

    /**
     * Device capabilities, re-measured at most once per
     * [CACHE_LIFETIME_MILLIS]. The window is short enough that docking into
     * DeX or a payload repair shows up almost immediately, and long enough
     * that a burst of reads costs a single measurement.
     */
    fun probe(): DeviceCapabilities {
        synchronized(cacheLock) {
            val cached = cachedCapabilities
            val ageMillis = SystemClock.elapsedRealtime() - cachedAtElapsedMillis
            if (cached != null && ageMillis < CACHE_LIFETIME_MILLIS) return cached
        }

        val measured = measureCapabilities()
        synchronized(cacheLock) {
            cachedCapabilities = measured
            cachedAtElapsedMillis = SystemClock.elapsedRealtime()
            // Log only when something actually changed. The diagnostics log
            // is a bounded ring buffer, and an unchanged snapshot repeated
            // hundreds of times pushes out the lines that explain a failure.
            if (measured != lastLoggedCapabilities) {
                lastLoggedCapabilities = measured
                AppLog.info(SCOPE, "Probe result: $measured")
            }
        }
        return measured
    }

    /** Forces the next [probe] to measure again — e.g. after a payload repair. */
    fun invalidateCache() {
        synchronized(cacheLock) { cachedCapabilities = null }
    }

    private fun measureCapabilities() = DeviceCapabilities(
        apiLevel = Build.VERSION.SDK_INT,
        isArm64 = Build.SUPPORTED_64_BIT_ABIS.contains(ARM64_ABI),
        kvm = probeKvmAccess(),
        hasVirtualizationFramework = probeVirtualizationFramework(),
        canForkExec = probeForkExec(),
        qemuPayloadPresent = nativeLibraryExists(QEMU_SYSTEM_LIB),
        prootPayloadPresent = nativeLibraryExists(PROOT_LIB),
        totalRamMb = probeTotalRamMb(),
        isDexModeActive = probeDexMode(),
        deviceModel = Build.MODEL ?: "unknown",
    )

    /**
     * The only trustworthy KVM check is opening the node: stock Samsung
     * firmware may ship the node but SELinux denies untrusted apps.
     */
    private fun probeKvmAccess(): KvmAccess {
        val kvmNode = File(KVM_DEVICE_PATH)
        if (!kvmNode.exists()) return KvmAccess.ABSENT
        return try {
            RandomAccessFile(kvmNode, "rw").use { /* open+close is the test */ }
            KvmAccess.USABLE
        } catch (denied: SecurityException) {
            KvmAccess.DENIED
        } catch (denied: Exception) {
            // EACCES/EPERM surface as FileNotFoundException("Permission denied")
            KvmAccess.DENIED
        }
    }

    /**
     * AVF (pKVM) presence. Its APIs are not open to third-party apps as of
     * Android 16, but we record it: when Samsung/Google open it up, the KVM
     * engine can switch to it, and the diagnostics screen can explain the
     * device's real potential.
     */
    private fun probeVirtualizationFramework(): Boolean {
        val hasFeature = runCatching {
            context.packageManager.hasSystemFeature(FEATURE_VIRTUALIZATION_FRAMEWORK)
        }.getOrDefault(false)
        val hasVirtApex = File(VIRT_APEX_PATH).isDirectory
        return hasFeature || hasVirtApex
    }

    /**
     * Verifies this process may fork/exec at all. Uses a system binary that
     * exists on every Android version we support; the QEMU payload itself is
     * verified separately during runtime installation.
     */
    private fun probeForkExec(): Boolean = try {
        val process = ProcessBuilder(SYSTEM_TRUE_COMMAND)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(EXEC_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        finished && process.exitValue() == 0
    } catch (error: Exception) {
        AppLog.warn(SCOPE, "fork/exec probe failed", error)
        false
    }

    private fun nativeLibraryExists(libraryName: String): Boolean {
        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir ?: return false
        return File(nativeLibraryDir, libraryName).exists()
    }

    private fun probeTotalRamMb(): Int = try {
        val memoryInfo = ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(ActivityManager::class.java)
        activityManager.getMemoryInfo(memoryInfo)
        (memoryInfo.totalMem / (1024L * 1024L)).toInt()
    } catch (error: Exception) {
        AppLog.warn(SCOPE, "RAM probe failed", error)
        0
    }

    /**
     * DeX detection without Samsung SDK: DeX desktop sessions report the
     * DESK ui-mode type in the activity configuration. This is the documented
     * signal that survives current One UI versions.
     */
    private fun probeDexMode(): Boolean = try {
        // Best effort only: this runs on the application context, which does
        // not track the DeX display. The UI asks [DexEnvironment] again per
        // window, which is the answer users actually see.
        DexEnvironment.isDesktopMode(context.resources.configuration, context)
    } catch (error: Exception) {
        false
    }

    companion object {
        private const val SCOPE = "CapabilityProbe"
        private const val ARM64_ABI = "arm64-v8a"
        private const val KVM_DEVICE_PATH = "/dev/kvm"
        private const val VIRT_APEX_PATH = "/apex/com.android.virt"
        private const val FEATURE_VIRTUALIZATION_FRAMEWORK =
            "android.software.virtualization_framework"
        private const val EXEC_PROBE_TIMEOUT_SECONDS = 5L

        /** How long a snapshot stays usable before it is measured again. */
        private const val CACHE_LIFETIME_MILLIS = 2_000L
        private val SYSTEM_TRUE_COMMAND = listOf("/system/bin/toybox", "true")

        /** Native-lib file names the payload pipeline produces. */
        const val QEMU_SYSTEM_LIB = "libqemu-system-aarch64.so"
        const val PROOT_LIB = "libproot.so"
    }
}
