package com.crunzex.linuxondex.engine.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VmPathsRootfsTest {

    private val paths = VmPaths(
        nativeLibraryDir = File("/app/lib/arm64"),
        filesDir = File("/data/user/0/app/files"),
        cacheDir = File("/data/user/0/app/cache"),
        externalFilesDir = { kind -> File("/sdcard/Android/data/app/files/$kind") },
    )

    @Test
    fun `an archive name becomes its own extraction directory`() {
        val directory = paths.prootRootfsDirFor(
            "linux-on-dex-ubuntu-24.04-proot-gnome-arm64.rootfs.tar.gz"
        )

        assertEquals("linux-on-dex-ubuntu-24.04-proot-gnome-arm64", directory.name)
        assertEquals(paths.prootImagesDir, directory.parentFile)
    }

    @Test
    fun `two images extract side by side`() {
        assertTrue(
            paths.prootRootfsDirFor("a.rootfs.tar.gz") !=
                paths.prootRootfsDirFor("b.rootfs.tar.gz")
        )
    }

    @Test
    fun `a hostile name cannot escape the images directory`() {
        val directory = paths.prootRootfsDirFor("../../etc/passwd.rootfs.tar.gz")

        assertEquals(paths.prootImagesDir, directory.parentFile)
        assertEquals(
            "separators must not survive as path structure",
            paths.prootImagesDir.resolve(directory.name).canonicalPath,
            directory.canonicalPath,
        )
    }

    @Test
    fun `a name of only dots never resolves to the images directory itself`() {
        // That directory is deleted and recreated per image; resolving to it
        // would wipe every other extracted image.
        listOf("..rootfs.tar.gz", ".rootfs.tar.gz", "...rootfs.tar.gz").forEach { archiveName ->
            val directory = paths.prootRootfsDirFor(archiveName)

            assertEquals(
                "$archiveName must extract into its own child directory",
                paths.prootImagesDir,
                directory.parentFile,
            )
            assertTrue(
                "$archiveName resolved to the images directory itself",
                directory.canonicalPath != paths.prootImagesDir.canonicalPath,
            )
        }
    }

    @Test
    fun `extraction lives on internal storage, never on FUSE`() {
        // External app storage cannot hold the symlinks a Linux rootfs needs.
        assertTrue(
            paths.prootImagesDir.absolutePath.startsWith(paths.vmRootDir.absolutePath)
        )
    }

    @Test
    fun `resetting transient runtime storage removes stale cache files`() {
        val directory = Files.createTempDirectory("vm-paths-test").toFile()
        val localPaths = VmPaths(
            nativeLibraryDir = directory.resolve("lib"),
            filesDir = directory.resolve("files"),
            cacheDir = directory.resolve("cache"),
            externalFilesDir = { null },
        )
        try {
            val staleFile = localPaths.tmpDir.resolve("stale/process.pid")
            staleFile.parentFile?.mkdirs()
            staleFile.writeText("123")

            assertTrue(localPaths.resetTransientRuntimeDirectory())
            assertTrue(localPaths.tmpDir.isDirectory)
            assertTrue(localPaths.tmpDir.listFiles().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}
