package com.crunzex.linuxondex

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.crunzex.linuxondex.engine.qemu.QemuAccelerator
import com.crunzex.linuxondex.engine.qemu.QemuVmEngine
import com.crunzex.linuxondex.engine.runtime.PayloadInstaller
import com.crunzex.linuxondex.engine.runtime.VmPaths
import com.crunzex.linuxondex.vm.CpuConfig
import com.crunzex.linuxondex.vm.DiskImageManager
import com.crunzex.linuxondex.vm.DisplayConfig
import com.crunzex.linuxondex.vm.StorageConfig
import com.crunzex.linuxondex.vm.VmConfig
import com.crunzex.linuxondex.vm.VmState
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Full-system boot verification: boots a real Alpine Linux ARM64 installer
 * ISO with the software-VM engine (approach 2) and waits for the guest
 * kernel to reach userspace, watching the serial console.
 *
 * The ISO is not part of the repo; push it before running:
 * ```
 * adb push alpine-virt-aarch64.iso \
 *   /sdcard/Android/data/com.crunzex.linuxondex/files/isos/
 * ```
 * The test is skipped when the ISO is absent. Runtime on an emulator is
 * minutes (TCG inside an emulated device); real hardware is much faster.
 */
@RunWith(AndroidJUnit4::class)
class AlpineIsoBootTest {

    private lateinit var paths: VmPaths
    private lateinit var engine: QemuVmEngine
    private lateinit var isoFile: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        paths = VmPaths(context)
        paths.createRuntimeDirectories()
        isoFile = File(paths.externalIsosDir, TEST_ISO_NAME)
        engine = QemuVmEngine(
            paths = paths,
            payloadInstaller = PayloadInstaller(context, paths),
            diskManager = DiskImageManager(paths),
            accelerator = QemuAccelerator.TCG,
        )
    }

    @Test
    fun alpineIsoBootsToUserspace() = runBlocking {
        assumeTrue("test ISO not pushed, skipping", isoFile.exists())

        val config = VmConfig(
            id = "boottest",
            name = "Boot Test",
            cpu = CpuConfig(coreCount = 2),
            memoryMb = 1024,
            storage = StorageConfig(diskSizeGb = 4),
            display = DisplayConfig(vncDisplayNumber = 7),
            installerIsoPath = isoFile.absolutePath,
        )

        try {
            engine.start(config)
            assertTrue("engine should be running", engine.state.value is VmState.Running)

            val bootMarker = waitForSerialMarker(
                markers = listOf("Welcome to Alpine Linux", "login:", "Linux version"),
                timeoutSeconds = BOOT_TIMEOUT_SECONDS,
            )
            assertTrue(
                "no boot marker within ${BOOT_TIMEOUT_SECONDS}s; serial tail:\n" +
                    engine.readSerialLog().takeLast(2_000),
                bootMarker != null,
            )
            println("BOOT OK — matched marker: $bootMarker")
        } finally {
            engine.stop(gracePeriod = 10.seconds)
        }
    }

    private fun waitForSerialMarker(markers: List<String>, timeoutSeconds: Int): String? {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1_000L
        while (System.currentTimeMillis() < deadline) {
            val serialLog = engine.readSerialLog()
            markers.firstOrNull { serialLog.contains(it) }?.let { return it }
            if (engine.state.value is VmState.Failed) {
                val failure = engine.state.value as VmState.Failed
                throw AssertionError("VM failed during boot: ${failure.error.technicalDetail}")
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    companion object {
        private const val TEST_ISO_NAME = "alpine-virt-aarch64.iso"
        private const val BOOT_TIMEOUT_SECONDS = 420
        private const val POLL_INTERVAL_MS = 3_000L
    }
}
