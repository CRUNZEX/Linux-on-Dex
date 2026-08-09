package com.crunzex.linuxondex.engine.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The stamp decides between a 30-second extraction and an instant start, so
 * it has to change when — and only when — the archive changed.
 */
class RootfsStampTest {

    @Test
    fun `the same archive produces the same stamp`() {
        assertEquals(
            RootfsImageInstaller.stampValue("ubuntu.rootfs.tar.gz", 1_206_317_407, 1_754_000_000),
            RootfsImageInstaller.stampValue("ubuntu.rootfs.tar.gz", 1_206_317_407, 1_754_000_000),
        )
    }

    @Test
    fun `a re-exported archive of the same size still re-extracts`() {
        val before = RootfsImageInstaller.stampValue("ubuntu.rootfs.tar.gz", 1_000, 1_754_000_000)
        val after = RootfsImageInstaller.stampValue("ubuntu.rootfs.tar.gz", 1_000, 1_754_009_999)

        assertNotEquals("a rebuilt image must not reuse the old tree", before, after)
    }

    @Test
    fun `a different size re-extracts`() {
        assertNotEquals(
            RootfsImageInstaller.stampValue("ubuntu.rootfs.tar.gz", 1_000, 1_754_000_000),
            RootfsImageInstaller.stampValue("ubuntu.rootfs.tar.gz", 2_000, 1_754_000_000),
        )
    }

    @Test
    fun `two images never share a stamp`() {
        assertNotEquals(
            RootfsImageInstaller.stampValue("ubuntu.rootfs.tar.gz", 1_000, 1_754_000_000),
            RootfsImageInstaller.stampValue("debian.rootfs.tar.gz", 1_000, 1_754_000_000),
        )
    }

    @Test
    fun `reclaimed archive descriptor preserves its stamp and original size`() {
        val directory = Files.createTempDirectory("rootfs-stamp-test").toFile()
        val archive = directory.resolve("ubuntu.rootfs.tar.gz")
        val stamp = RootfsImageInstaller.stampValue(archive.name, 1_206_317_407, 1_754_000_000)
        try {
            archive.writeText(RootfsImageInstaller.reclaimedArchiveText(stamp, 1_206_317_407))

            assertTrue(RootfsImageInstaller.isReclaimedArchive(archive))
            assertEquals(stamp, RootfsImageInstaller.stampValueFor(archive))
            assertEquals(1_206_317_407L, RootfsImageInstaller.sourceSizeBytes(archive))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a small regular file is not mistaken for a reclaimed archive`() {
        val archive = Files.createTempFile("ordinary-rootfs", ".rootfs.tar.gz").toFile()
        try {
            archive.writeText("not a Linux on Dex descriptor")

            assertFalse(RootfsImageInstaller.isReclaimedArchive(archive))
            assertEquals(archive.length(), RootfsImageInstaller.sourceSizeBytes(archive))
        } finally {
            archive.delete()
        }
    }
}
