package com.crunzex.linuxondex.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VmConfigTest {

    private fun config(
        memoryMb: Int = 2048,
        cpu: CpuConfig = CpuConfig(),
        network: NetworkConfig = NetworkConfig(),
        isoPath: String? = "/data/isos/ubuntu.iso",
        bootOrder: BootOrder = BootOrder.INSTALLER_FIRST,
        engineOverride: EngineOverride = EngineOverride.AUTO,
    ) = VmConfig(
        id = "primary",
        name = "Linux VM",
        cpu = cpu,
        memoryMb = memoryMb,
        network = network,
        installerIsoPath = isoPath,
        bootOrder = bootOrder,
        engineOverride = engineOverride,
    )

    @Test
    fun `a new VM starts with four processors and four gigabytes`() {
        val created = VmConfig.createDefault(totalDeviceRamMb = 12288, availableCpuCores = 8)

        assertEquals(4, created.cpu.coreCount)
        assertEquals(4096, created.memoryMb)
    }

    @Test
    fun `the default never asks for more processors than the device has`() {
        assertEquals(4, VmConfig.defaultCoreCount(availableCpuCores = 8))
        assertEquals(4, VmConfig.defaultCoreCount(availableCpuCores = 4))
        assertEquals(2, VmConfig.defaultCoreCount(availableCpuCores = 2))
        assertEquals(1, VmConfig.defaultCoreCount(availableCpuCores = 1))
    }

    @Test
    fun `default config on a small device still produces a usable minimum`() {
        val created = VmConfig.createDefault(totalDeviceRamMb = 3000, availableCpuCores = 4)

        assertTrue("memory must stay at or above the floor", created.memoryMb >= VmConfig.MIN_MEMORY_MB)
        assertTrue("memory must stay within the safe ceiling",
            created.memoryMb <= VmConfig.maxSafeGuestMemoryMb(3000))
    }

    @Test
    fun `the memory ceiling is the fitted size less three gigabytes for DeX`() {
        // A 12 GB phone reports about 11122 MB, the rest reserved before
        // Linux ever sees it. The user knows they bought 12 GB, so the
        // ceiling is 12288 - 3072.
        assertEquals(9216, VmConfig.maxSafeGuestMemoryMb(deviceTotalRamMb = 11122))
        assertEquals(9216, VmConfig.maxSafeGuestMemoryMb(deviceTotalRamMb = 12288))
        assertEquals(5120, VmConfig.maxSafeGuestMemoryMb(deviceTotalRamMb = 7680))
        assertEquals(13312, VmConfig.maxSafeGuestMemoryMb(deviceTotalRamMb = 15600))
        // A 10 GB device must not be rounded to 12: that would offer the
        // guest more memory than the phone is fitted with.
        assertEquals(7168, VmConfig.maxSafeGuestMemoryMb(deviceTotalRamMb = 9938))
    }

    @Test
    fun `the ceiling never exceeds what the device is fitted with`() {
        // With the larger reserve, rounding to a fitted capacity must never
        // offer the guest more RAM than Android reports as physically usable.
        listOf(3800, 5700, 7680, 9938, 11122, 15600, 23000).forEach { reported ->
            val ceiling = VmConfig.maxSafeGuestMemoryMb(reported)
            assertTrue(
                "ceiling $ceiling should leave headroom on a device reporting $reported",
                ceiling <= reported,
            )
        }
    }

    @Test
    fun `a tiny device never reports a ceiling below the floor`() {
        assertTrue(VmConfig.maxSafeGuestMemoryMb(1024) >= VmConfig.MIN_MEMORY_MB)
        assertTrue(VmConfig.maxSafeGuestMemoryMb(0) >= VmConfig.MIN_MEMORY_MB)
    }

    @Test
    fun `memory beyond the safe ceiling is reported as a problem`() {
        val problems = config(memoryMb = 7168).validationProblems(deviceTotalRamMb = 8192)

        assertTrue(problems.any { it.contains("Memory") })
    }

    @Test
    fun `memory within the safe ceiling passes validation`() {
        val problems = config(memoryMb = 4096).validationProblems(deviceTotalRamMb = 8192)

        assertTrue("expected no problems but got $problems", problems.isEmpty())
    }

    @Test
    fun `installer boot without an iso is reported`() {
        val problems = config(isoPath = null, bootOrder = BootOrder.INSTALLER_FIRST)
            .validationProblems(deviceTotalRamMb = 8192)

        assertTrue(problems.any { it.contains("no ISO is selected") })
    }

    @Test
    fun `disk first boot without an iso is fine`() {
        val problems = config(isoPath = null, bootOrder = BootOrder.DISK_FIRST)
            .validationProblems(deviceTotalRamMb = 8192)

        assertTrue(problems.isEmpty())
    }

    @Test
    fun `host cpu model with forced software vm is reported`() {
        val problems = config(
            cpu = CpuConfig(model = CpuModel.HOST),
            engineOverride = EngineOverride.FORCE_TCG,
        ).validationProblems(deviceTotalRamMb = 8192)

        assertTrue(problems.any { it.contains("host") })
    }

    @Test
    fun `port forward without networking is reported`() {
        val problems = config(
            network = NetworkConfig(mode = NetworkMode.DISABLED, sshPortForward = 2222),
        ).validationProblems(deviceTotalRamMb = 8192)

        assertTrue(problems.any { it.contains("Port forwarding") })
    }

    @Test
    fun `unknown device memory does not block startup`() {
        val problems = config(memoryMb = 8192).validationProblems(deviceTotalRamMb = 0)

        assertTrue(problems.isEmpty())
    }

    @Test
    fun `vnc port derives from the display number`() {
        val created = config().copy(display = DisplayConfig(vncDisplayNumber = 5))

        assertEquals(5905, created.vncPort)
    }

    @Test
    fun `screen resolutions are offered smallest first`() {
        // The picker renders enum order directly, so the list must read as a
        // performance ramp: the cheapest, smoothest option first.
        val widths = ScreenResolution.entries.map { it.widthPx }

        assertEquals(widths.sorted(), widths)
        assertEquals(1024, ScreenResolution.HD_1024_600.widthPx)
        assertEquals(600, ScreenResolution.HD_1024_600.heightPx)
    }

    @Test
    fun `invalid vm id is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            VmConfig(id = "../escape", name = "bad")
        }
    }

    @Test
    fun `out of range core count is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { CpuConfig(coreCount = 0) }
        assertThrows(IllegalArgumentException::class.java) { CpuConfig(coreCount = 99) }
    }

    @Test
    fun `privileged port forward is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            NetworkConfig(sshPortForward = 22)
        }
    }

    @Test
    fun `unsafe mount tag is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            SharedFolderConfig(enabled = true, mountTag = "../etc")
        }
    }

    @Test
    fun `a config saved by an older version with a usb section still loads`() {
        // 2.x releases persisted a "usb" block. The feature is gone, but a
        // user upgrading must never lose their VM to an unknown key.
        val legacyJson = """
            {
              "id": "primary",
              "name": "Linux VM",
              "memoryMb": 3312,
              "usb": {
                "mountedDevices": [
                  {
                    "device": {"vendorId": 2316, "productId": 4096, "label": "USB drive"},
                    "route": "STORAGE_FOLDER",
                    "storagePath": "/storage/1A2B-3C4D/vm-share"
                  }
                ]
              }
            }
        """.trimIndent()

        // The same parser settings VmRepository uses.
        val parser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val loaded = parser.decodeFromString(VmConfig.serializer(), legacyJson)

        assertEquals("primary", loaded.id)
        assertEquals(3312, loaded.memoryMb)
    }
}
