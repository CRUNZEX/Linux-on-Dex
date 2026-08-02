package com.crunzex.linuxondex.vm

import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A backup's name has to survive a round trip: the user imports it again, and
 * the app decides what kind of image it is from the name alone. Losing the
 * suffix would offer a container archive as a bootable disk.
 */
class VmBackupNamingTest {

    private val backupMoment = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        .parse("20260801-221023")!!

    private fun nameFor(sourceFileName: String) =
        VmBackupManager.backupFileName(sourceFileName, backupMoment)

    @Test
    fun `a disk image keeps its qcow2 extension`() {
        assertEquals(
            "linux-on-dex-alpine-3.22.0-arm64-20260801-221023.qcow2",
            nameFor("linux-on-dex-alpine-3.22.0-arm64.qcow2"),
        )
    }

    @Test
    fun `a container archive keeps its whole rootfs suffix`() {
        assertEquals(
            "linux-on-dex-debian-13-proot-arm64-20260801-221023.rootfs.tar.gz",
            nameFor("linux-on-dex-debian-13-proot-arm64.rootfs.tar.gz"),
        )
    }

    @Test
    fun `the timestamp goes before the suffix, never after it`() {
        // ".rootfs.tar-20260801-221023.gz" would no longer read as a
        // container archive, and the app would refuse to run it.
        val name = nameFor("ubuntu.rootfs.tar.gz")

        assertTrue(name.endsWith(".rootfs.tar.gz"))
        assertEquals(
            PreparedImageFormat.PROOT_ROOTFS,
            PreparedImageFormat.fromFileName(name),
        )
    }

    @Test
    fun `a backup of a backup does not accumulate suffixes`() {
        val once = nameFor("linux-on-dex-kali-2026.2-proot-arm64.rootfs.tar.gz")
        val twice = nameFor(once)

        assertTrue(twice.endsWith(".rootfs.tar.gz"))
        assertEquals(
            "only one suffix, however many times it is backed up",
            1,
            Regex("\\.rootfs\\.tar\\.gz").findAll(twice).count(),
        )
    }

    @Test
    fun `a raw img disk keeps its own extension`() {
        assertEquals("fedora-42-20260801-221023.img", nameFor("fedora-42.img"))
    }

    @Test
    fun `both kinds of image are recognised as backups, nothing else is`() {
        assertTrue(VmBackupManager.isBackupFile("ubuntu-20260801-221023.qcow2"))
        assertTrue(VmBackupManager.isBackupFile("debian-20260801-221023.rootfs.tar.gz"))
        assertFalse(VmBackupManager.isBackupFile("notes.txt"))
        assertFalse(VmBackupManager.isBackupFile("seed.iso"))
        // A plain tarball is not a container image; only the marker suffix is.
        assertFalse(VmBackupManager.isBackupFile("archive.tar.gz"))
    }
}
