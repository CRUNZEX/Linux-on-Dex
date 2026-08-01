package com.crunzex.linuxondex.engine.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

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
}
