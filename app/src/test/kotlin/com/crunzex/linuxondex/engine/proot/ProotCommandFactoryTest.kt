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
        assertEquals("legacy_vnc", environment["DEX_DISPLAY_BACKEND"])
    }

    @Test
    fun `native X11 desktop publishes only the Unix display contract`() {
        val desktop = ProotCommandFactory.desktopSession(
            paths = paths,
            rootfsDir = rootfsDir,
            displayResolution = "1280x800",
            vncPort = 5901,
            sharedFolderDir = null,
            displayBackend = ProotDisplayBackend.NATIVE_X11,
        )

        assertEquals(":1", desktop.environment["DISPLAY"])
        assertEquals("native_x11", desktop.environment["DEX_DISPLAY_BACKEND"])
        assertNull(desktop.environment["DEX_VNC_PORT"])
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
    fun `renderer stages change exactly the bind and env they must`() {
        val cpuinfoBind = "${paths.portableCpuinfoFile}:${ProotCommandFactory.CPUINFO_GUEST_PATH}"
        fun sessionAt(stage: RendererStage) = ProotCommandFactory.desktopSession(
            paths = paths,
            rootfsDir = rootfsDir,
            displayResolution = "1280x800",
            vncPort = 5901,
            sharedFolderDir = null,
            displayBackend = ProotDisplayBackend.NATIVE_X11,
            rendererStage = stage,
        )

        val gpu = sessionAt(RendererStage.GPU_VIRGL)
        assertFalse(
            "the GPU stage runs no JIT rasterizer; faking cpuinfo would only " +
                "slow other JITs down",
            gpu.arguments.contains(cpuinfoBind),
        )
        assertEquals("virpipe", gpu.environment["DEX_RENDERER"])
        assertNull(
            "the supervisor owns the session's GL variables",
            gpu.environment["GALLIUM_DRIVER"],
        )

        val native = sessionAt(RendererStage.NATIVE)
        assertFalse(native.arguments.contains(cpuinfoBind))
        assertNull(native.environment["DEX_RENDERER"])

        val portable = sessionAt(RendererStage.PORTABLE_CPU)
        assertTrue(
            "portable stage must fake /proc/cpuinfo for the JIT",
            portable.arguments.zipWithNext().any { (flag, bind) ->
                flag == "-b" && bind == cpuinfoBind
            },
        )
        assertNull("portable stage keeps llvmpipe", portable.environment["DEX_RENDERER"])

        val failsafe = sessionAt(RendererStage.FAILSAFE_SOFTPIPE)
        assertTrue(
            failsafe.arguments.zipWithNext().any { (flag, bind) ->
                flag == "-b" && bind == cpuinfoBind
            },
        )
        assertEquals("softpipe", failsafe.environment["DEX_RENDERER"])
    }

    @Test
    fun `every session shares one short host directory as its tmp`() {
        val guestTmpBind = "${paths.prootGuestTmpDir}:${ProotCommandFactory.GUEST_TMP_GUEST_PATH}"
        val sessions = listOf(
            desktopCommand(),
            ProotCommandFactory.consoleSession(paths, rootfsDir, sharedFolderDir = null),
            ProotCommandFactory.interactiveShell(paths, rootfsDir, sharedFolderDir = null),
            ProotCommandFactory.graphicsProbe(paths, rootfsDir),
        )

        sessions.forEach { command ->
            assertTrue(
                "session must see the X11 socket in /tmp: ${command.arguments}",
                command.arguments.zipWithNext().any { (flag, bind) ->
                    flag == "-b" && bind == guestTmpBind
                },
            )
        }
    }

    @Test
    fun `virgl socket bind comes after the tmp bind it nests inside`() {
        val arguments = ProotCommandFactory.desktopSession(
            paths = paths,
            rootfsDir = rootfsDir,
            displayResolution = "1280x800",
            vncPort = 5901,
            sharedFolderDir = null,
            graphicsBridgeEnabled = true,
        ).arguments

        val tmpBindIndex = arguments.indexOfFirst { it.endsWith(":/tmp") }
        val virglBindIndex = arguments.indexOfFirst {
            it.endsWith(":${ProotCommandFactory.VIRGL_SOCKET_GUEST_PATH}")
        }
        assertTrue("tmp bind missing", tmpBindIndex >= 0)
        assertTrue("virgl bind missing", virglBindIndex >= 0)
        assertTrue(
            "PRoot applies binds in order; the nested path must come second",
            tmpBindIndex < virglBindIndex,
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
    fun `desktop sees native bridge without forcing the compositor onto virpipe`() {
        val command = ProotCommandFactory.desktopSession(
            paths = paths,
            rootfsDir = rootfsDir,
            displayResolution = "1280x800",
            vncPort = 5901,
            sharedFolderDir = null,
            graphicsBridgeEnabled = true,
        )

        assertTrue(
            command.arguments.zipWithNext().any { (flag, bind) ->
                flag == "-b" && bind ==
                    "${paths.virglSocket}:${ProotCommandFactory.VIRGL_SOCKET_GUEST_PATH}"
            },
        )
        assertEquals("1", command.environment["DEX_GPU_BRIDGE"])
        assertNull(command.environment["GALLIUM_DRIVER"])
        assertEquals(
            ProotCommandFactory.VIRGL_SOCKET_GUEST_PATH,
            command.environment["VTEST_SOCKET_NAME"],
        )
    }

    @Test
    fun `graphics probe explicitly selects virpipe`() {
        val command = ProotCommandFactory.graphicsProbe(paths, rootfsDir)

        assertEquals("1", command.environment["DEX_GPU_BRIDGE"])
        assertEquals("virpipe", command.environment["GALLIUM_DRIVER"])
        assertEquals("1", command.environment["LIBGL_ALWAYS_SOFTWARE"])
        assertNull(
            "no forced GL version: lying about a GLES bridge's capabilities " +
                "crashes compositors",
            command.environment["MESA_GL_VERSION_OVERRIDE"],
        )
    }

    @Test
    fun `software fallback does not force virpipe`() {
        val environment = desktopCommand().environment

        assertNull(environment["DEX_GPU_BRIDGE"])
        assertNull(environment["GALLIUM_DRIVER"])
        assertNull(environment["VTEST_SOCKET_NAME"])
    }

    @Test
    fun `terminals render like the desktop at the GPU stage`() {
        val gpuShell = ProotCommandFactory.interactiveShell(
            paths = paths,
            rootfsDir = rootfsDir,
            sharedFolderDir = null,
            graphicsBridgeEnabled = true,
            rendererStage = RendererStage.GPU_VIRGL,
        )
        assertEquals("virpipe", gpuShell.environment["GALLIUM_DRIVER"])
        assertEquals("1", gpuShell.environment["LIBGL_ALWAYS_SOFTWARE"])

        val cpuShell = ProotCommandFactory.interactiveShell(
            paths = paths,
            rootfsDir = rootfsDir,
            sharedFolderDir = null,
            graphicsBridgeEnabled = true,
            rendererStage = RendererStage.NATIVE,
        )
        assertNull("CPU sessions leave GL selection alone", cpuShell.environment["GALLIUM_DRIVER"])

        val gpuStageWithoutBridge = ProotCommandFactory.interactiveShell(
            paths = paths,
            rootfsDir = rootfsDir,
            sharedFolderDir = null,
            graphicsBridgeEnabled = false,
            rendererStage = RendererStage.GPU_VIRGL,
        )
        assertNull(
            "no bridge socket means nothing to select",
            gpuStageWithoutBridge.environment["GALLIUM_DRIVER"],
        )
    }

    @Test
    fun `interactive shell enters the same rootfs under a pseudo-terminal`() {
        val command = ProotCommandFactory.interactiveShell(paths, rootfsDir, null)

        assertEquals(
            "without a pty there is no prompt, no echo and no stty",
            listOf(
                "/usr/bin/script",
                "-q",
                "-c",
                ProotCommandFactory.INTERACTIVE_LOGIN_COMMAND,
                "/dev/null",
            ),
            command.arguments.takeLast(5),
        )
        assertTrue(
            "Android group IDs must be named before bash displays the prompt",
            ProotCommandFactory.INTERACTIVE_LOGIN_COMMAND.indexOf("dex-name-groups") <
                ProotCommandFactory.INTERACTIVE_LOGIN_COMMAND.indexOf("/bin/bash"),
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
