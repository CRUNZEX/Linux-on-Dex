package com.crunzex.linuxondex.engine.proot

import com.crunzex.linuxondex.engine.runtime.VmPaths
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsImageInstallerTest {

    @Test
    fun `a verified app-owned archive is reclaimed without changing its image identity`() {
        withTemporaryPaths { paths, directory ->
            val archive = paths.vmImagesDir!!.resolve("ubuntu.rootfs.tar.gz")
            archive.parentFile?.mkdirs()
            archive.writeBytes(ByteArray(8 * 1024) { it.toByte() })
            val originalSize = archive.length()
            val originalStamp = RootfsImageInstaller.stampValueFor(archive)
            writeInstalledStamp(paths, archive, originalStamp)

            val rootfs = RootfsImageInstaller(paths).ensureExtracted(archive)

            assertEquals(paths.prootRootfsDirFor(archive.name), rootfs)
            assertTrue(RootfsImageInstaller.isReclaimedArchive(archive))
            assertEquals(originalStamp, RootfsImageInstaller.stampValueFor(archive))
            assertEquals(originalSize, RootfsImageInstaller.sourceSizeBytes(archive))
            assertFalse(
                directory.resolve("external/vm-images/.${archive.name}.compressed-reclaim").exists()
            )
        }
    }

    @Test
    fun `an archive outside app-managed image storage is never reclaimed`() {
        withTemporaryPaths { paths, directory ->
            val archive = directory.resolve("user-document/ubuntu.rootfs.tar.gz")
            archive.parentFile?.mkdirs()
            archive.writeBytes(ByteArray(8 * 1024) { it.toByte() })
            val originalStamp = RootfsImageInstaller.stampValueFor(archive)
            writeInstalledStamp(paths, archive, originalStamp)

            RootfsImageInstaller(paths).ensureExtracted(archive)

            assertFalse(RootfsImageInstaller.isReclaimedArchive(archive))
            assertEquals(8 * 1024L, archive.length())
        }
    }

    private fun writeInstalledStamp(paths: VmPaths, archive: File, stamp: String) {
        paths.prootRootfsDirFor(archive.name).apply { mkdirs() }
            .resolve(".linux-on-dex-rootfs-stamp")
            .writeText(stamp)
    }

    private fun withTemporaryPaths(block: (VmPaths, File) -> Unit) {
        val directory = Files.createTempDirectory("rootfs-installer-test").toFile()
        val paths = VmPaths(
            nativeLibraryDir = directory.resolve("lib"),
            filesDir = directory.resolve("files"),
            cacheDir = directory.resolve("cache"),
            externalFilesDir = { kind -> directory.resolve("external/$kind") },
        )
        try {
            block(paths, directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
