package com.crunzex.linuxondex.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedImageFormatTest {

    @Test
    fun `rootfs archives are recognised by their full suffix`() {
        assertEquals(
            PreparedImageFormat.PROOT_ROOTFS,
            PreparedImageFormat.fromFileName(
                "linux-on-dex-ubuntu-24.04-proot-gnome-arm64.rootfs.tar.gz"
            ),
        )
    }

    @Test
    fun `disk images keep their existing detection`() {
        assertEquals(
            PreparedImageFormat.QCOW2_DISK,
            PreparedImageFormat.fromFileName("linux-on-dex-debian-13-arm64.qcow2"),
        )
        assertEquals(
            PreparedImageFormat.QCOW2_DISK,
            PreparedImageFormat.fromFileName("fedora-42.img"),
        )
    }

    @Test
    fun `a plain tarball is not an image`() {
        assertNull(
            "only the .rootfs.tar.gz marker means a runnable rootfs",
            PreparedImageFormat.fromFileName("backup.tar.gz"),
        )
        assertNull(PreparedImageFormat.fromFileName("notes.txt"))
        assertNull(PreparedImageFormat.fromFileName("image-seed.iso"))
    }

    @Test
    fun `detection ignores case`() {
        assertEquals(
            PreparedImageFormat.PROOT_ROOTFS,
            PreparedImageFormat.fromFileName("Ubuntu.ROOTFS.TAR.GZ"),
        )
    }

    @Test
    fun `a rootfs image config routes the boot to proot`() {
        val config = VmConfig(
            id = "primary",
            name = "Linux VM",
            preparedImage = PreparedImageConfig(
                displayName = "Ubuntu GNOME (PRoot)",
                diskImagePath = "/sdcard/x.rootfs.tar.gz",
                format = PreparedImageFormat.PROOT_ROOTFS,
            ),
        )

        assertTrue(config.runsInProotContainer)
    }

    @Test
    fun `configs saved before the format field existed stay disks`() {
        val legacy = PreparedImageConfig(
            displayName = "Ubuntu 24.04",
            diskImagePath = "/sdcard/ubuntu.qcow2",
        )

        assertEquals(PreparedImageFormat.QCOW2_DISK, legacy.format)
    }
}
