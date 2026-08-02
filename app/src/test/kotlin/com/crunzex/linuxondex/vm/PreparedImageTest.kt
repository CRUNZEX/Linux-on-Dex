package com.crunzex.linuxondex.vm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PreparedImageTest {

    private fun image(fileName: String) = PreparedImage(
        diskFile = File("/data/vm-images/$fileName"),
        seedFile = null,
    )

    @Test
    fun `the tool's own file name becomes a readable title`() {
        val name = image("linux-on-dex-ubuntu-24.04-arm64.qcow2").displayName

        assertEquals("Ubuntu 24.04 Arm64", name)
    }

    @Test
    fun `a plain image name is still readable`() {
        assertEquals("Debian 13", image("debian-13.qcow2").displayName)
    }

    @Test
    fun `raw img images are handled like qcow2`() {
        assertEquals("Fedora 42", image("fedora-42.img").displayName)
    }

    @Test
    fun `size is reported in megabytes`() {
        // A path that does not exist reports zero length, which is the
        // honest answer rather than a crash.
        assertEquals(0L, image("missing.qcow2").sizeMb)
    }

    @Test
    fun `a rootfs archive gets a readable title without its suffix`() {
        val name = image(
            "linux-on-dex-ubuntu-24.04-proot-gnome-arm64.rootfs.tar.gz"
        ).displayName

        assertEquals("Ubuntu 24.04 Proot Gnome Arm64", name)
    }

    @Test
    fun `the file name decides the format`() {
        assertEquals(
            PreparedImageFormat.PROOT_ROOTFS,
            image("ubuntu.rootfs.tar.gz").format,
        )
        assertEquals(
            PreparedImageFormat.QCOW2_DISK,
            image("ubuntu.qcow2").format,
        )
    }
}
