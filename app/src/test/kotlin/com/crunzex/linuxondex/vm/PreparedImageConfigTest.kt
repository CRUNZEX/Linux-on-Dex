package com.crunzex.linuxondex.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedImageConfigTest {

    private fun preparedConfig(
        firstBootCompleted: Boolean = false,
        seedIsoPath: String? = "/data/vm-images/seed.iso",
    ) = VmConfig(
        id = "primary",
        name = "Linux VM",
        preparedImage = PreparedImageConfig(
            displayName = "Ubuntu 24.04 arm64",
            diskImagePath = "/data/vm-images/ubuntu.qcow2",
            seedIsoPath = seedIsoPath,
            firstBootCompleted = firstBootCompleted,
        ),
    )

    @Test
    fun `default credentials are dex over dex`() {
        val prepared = PreparedImageConfig(
            displayName = "Ubuntu",
            diskImagePath = "/data/ubuntu.qcow2",
        )

        assertEquals("dex", prepared.username)
        assertEquals("dex", prepared.password)
    }

    @Test
    fun `a prepared image never boots an installer`() {
        val config = preparedConfig().copy(
            installerIsoPath = "/data/isos/ubuntu.iso",
            bootOrder = BootOrder.INSTALLER_FIRST,
        )

        assertTrue(config.usesPreparedImage)
        assertFalse("prepared images are already installed", config.bootsFromInstaller)
    }

    @Test
    fun `a prepared image needs no iso to pass validation`() {
        val problems = preparedConfig()
            .copy(bootOrder = BootOrder.INSTALLER_FIRST)
            .validationProblems(deviceTotalRamMb = 8192)

        assertTrue("expected no problems but got $problems", problems.isEmpty())
    }

    @Test
    fun `an iso install still requires an iso`() {
        val problems = VmConfig(id = "primary", name = "Linux VM")
            .copy(bootOrder = BootOrder.INSTALLER_FIRST)
            .validationProblems(deviceTotalRamMb = 8192)

        assertTrue(problems.any { it.contains("no ISO is selected") })
    }

    @Test
    fun `first boot completion is recorded on the image`() {
        val configured = preparedConfig(firstBootCompleted = false)

        val afterFirstBoot = configured.copy(
            preparedImage = configured.preparedImage!!.copy(firstBootCompleted = true)
        )

        assertTrue(afterFirstBoot.preparedImage!!.firstBootCompleted)
    }

    @Test
    fun `an image without a seed is still usable`() {
        val config = preparedConfig(seedIsoPath = null)

        assertTrue(config.usesPreparedImage)
        assertTrue(config.validationProblems(deviceTotalRamMb = 8192).isEmpty())
    }
}
