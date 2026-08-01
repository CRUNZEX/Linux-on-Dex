package com.crunzex.linuxondex.engine.proot

import com.crunzex.linuxondex.engine.runtime.VmPaths
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotCommandFactoryTest {

    private val paths = VmPaths(
        nativeLibraryDir = File("/app/lib/arm64"),
        filesDir = File("/data/user/0/app/files"),
        cacheDir = File("/data/user/0/app/cache"),
        externalFilesDir = { kind -> File("/sdcard/Android/data/app/files/$kind") },
    )

    private val rootfsDir = File("/data/user/0/app/files/vm/proot-images/ubuntu")

    private fun desktopCommand() = ProotCommandFactory.desktopSession(
        paths = paths,
        rootfsDir = rootfsDir,
        displayResolution = "1280x800",
        vncPort = 5901,
        sharedFolderDir = File("/sdcard/Android/data/app/files/shared"),
    )

    @Test
    fun `desktop session runs the baked supervisor as fake root`() {
        val command = desktopCommand()

        assertEquals("libproot.so", command.program.name)
        assertTrue("apt and sshd check for uid 0", "-0" in command.arguments)
        assertEquals(
            "the supervisor is the guest program",
            ProotCommandFactory.DESKTOP_SUPERVISOR_GUEST_PATH,
            command.arguments.last(),
        )
    }

    @Test
    fun `desktop session emulates what Android denies`() {
        val arguments = desktopCommand().arguments

        assertTrue("Android denies link(): dpkg needs it", "--link2symlink" in arguments)
        assertTrue("Android has no SysV IPC: X11 clients ask for it", "--sysvipc" in arguments)
        assertTrue(
            "Android has no /dev/shm: one must be bound in",
            arguments.zipWithNext().any { (flag, bind) ->
                flag == "-b" && bind.endsWith(":/dev/shm")
            },
        )
    }

    @Test
    fun `desktop session tells the supervisor its display settings`() {
        val environment = desktopCommand().environment

        assertEquals("1280x800", environment["DEX_RESOLUTION"])
        assertEquals("5901", environment["DEX_VNC_PORT"])
    }

    @Test
    fun `guest sees a root home, not the host-side one`() {
        val environment = desktopCommand().environment

        assertEquals("/root", environment["HOME"])
        assertEquals("root", environment["USER"])
    }

    @Test
    fun `guest programs get a temp directory that exists inside the rootfs`() {
        val environment = desktopCommand().environment

        assertEquals(
            "guest apps read TMPDIR, and Android's path is invisible to them",
            "/tmp",
            environment["TMPDIR"],
        )
        assertTrue(
            "PRoot itself still needs the host path",
            environment["PROOT_TMP_DIR"]!!.startsWith("/data/"),
        )
    }

    @Test
    fun `guest PATH includes the games directories Ubuntu installs into`() {
        // Without these an `apt install` can look like it did nothing: the
        // program lands in /usr/games and the shell reports "not found".
        val path = desktopCommand().environment["PATH"]!!

        assertTrue("PATH was $path", path.split(':').contains("/usr/games"))
        assertTrue("PATH was $path", path.split(':').contains("/usr/local/games"))
    }

    @Test
    fun `seccomp acceleration stays off — it corrupts desktop sessions`() {
        assertEquals("1", desktopCommand().environment["PROOT_NO_SECCOMP"])
        assertEquals(
            "1",
            ProotCommandFactory.interactiveShell(paths, rootfsDir, null)
                .environment["PROOT_NO_SECCOMP"],
        )
    }

    @Test
    fun `shared folder is bound when available and skipped when not`() {
        val withFolder = desktopCommand().arguments
        val without = ProotCommandFactory.desktopSession(
            paths = paths,
            rootfsDir = rootfsDir,
            displayResolution = "1280x800",
            vncPort = 5901,
            sharedFolderDir = null,
        ).arguments

        assertTrue(withFolder.any { it.endsWith(ProotCommandFactory.SHARED_FOLDER_GUEST_PATH) })
        assertFalse(without.any { it.endsWith(ProotCommandFactory.SHARED_FOLDER_GUEST_PATH) })
    }

    @Test
    fun `interactive shell enters the same rootfs under a pseudo-terminal`() {
        val command = ProotCommandFactory.interactiveShell(paths, rootfsDir, null)

        assertEquals(
            "without a pty there is no prompt, no echo and no stty",
            listOf("/usr/bin/script", "-q", "-c", "/bin/bash -l", "/dev/null"),
            command.arguments.takeLast(5),
        )
        assertTrue(command.arguments.contains(rootfsDir.absolutePath))
        assertEquals("xterm-256color", command.environment["TERM"])
        assertEquals("truecolor", command.environment["COLORTERM"])
    }

    @Test
    fun `legacy alpine shell keeps its historical shape`() {
        val command = ProotCommandFactory.legacyAlpineShell(paths)

        assertEquals(listOf("/bin/sh", "-l"), command.arguments.takeLast(2))
        assertFalse("the bundled Alpine never needed fake root", "-0" in command.arguments)
        assertNull(
            "no desktop variables in a terminal-only session",
            command.environment["DEX_VNC_PORT"],
        )
    }
}
