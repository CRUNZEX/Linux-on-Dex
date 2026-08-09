package com.crunzex.linuxondex.engine.proot

import com.crunzex.linuxondex.engine.runtime.NativeCommand
import com.crunzex.linuxondex.engine.runtime.VmPaths
import java.io.File

enum class ProotDisplayBackend {
    NATIVE_X11,
    LEGACY_VNC,
}

/**
 * Builds the exact PRoot command lines the engine runs. Pure construction —
 * no process is spawned here — so every argument choice is unit-testable.
 *
 * Three shapes exist:
 *  - the desktop session: the long-lived supervisor inside a rootfs image
 *    that owns either a native X11 or legacy VNC graphical session,
 *  - an interactive shell into that same rootfs, one per terminal window,
 *  - the legacy bundled-Alpine shell (the no-image fallback).
 */
object ProotCommandFactory {

    /** Starts `/usr/local/bin/dex-desktop` (baked into the image) as fake root. */
    fun desktopSession(
        paths: VmPaths,
        rootfsDir: File,
        displayResolution: String,
        vncPort: Int,
        sharedFolderDir: File?,
        graphicsBridgeEnabled: Boolean = false,
        displayBackend: ProotDisplayBackend = ProotDisplayBackend.LEGACY_VNC,
    ): NativeCommand = NativeCommand(
        program = paths.prootBinary,
        arguments = rootfsArguments(paths, rootfsDir, sharedFolderDir, graphicsBridgeEnabled) +
            listOf(DESKTOP_SUPERVISOR_GUEST_PATH),
        environment = guestEnvironment(
            paths = paths,
            graphicsBridgeEnabled = graphicsBridgeEnabled,
            selectVirglDriver = false,
        ) + mapOf(
            "DEX_RESOLUTION" to displayResolution,
            "DEX_DISPLAY_BACKEND" to displayBackend.name.lowercase(),
        ) + if (displayBackend == ProotDisplayBackend.LEGACY_VNC) {
            mapOf("DEX_VNC_PORT" to vncPort.toString())
        } else {
            mapOf("DISPLAY" to NATIVE_X11_DISPLAY)
        },
        workingDirectory = paths.vmRootDir,
    )

    /**
     * Starts `/usr/local/bin/dex-session` — the console container's leader.
     *
     * A console image has no display to bring up, so its leader only prepares
     * the container and then stays alive: PRoot takes the whole container
     * down when its first process exits, so something has to hold it open
     * while the user's terminal windows come and go.
     */
    fun consoleSession(
        paths: VmPaths,
        rootfsDir: File,
        sharedFolderDir: File?,
        graphicsBridgeEnabled: Boolean = false,
    ): NativeCommand = NativeCommand(
        program = paths.prootBinary,
        arguments = rootfsArguments(paths, rootfsDir, sharedFolderDir, graphicsBridgeEnabled) +
            listOf(CONSOLE_SESSION_GUEST_PATH),
        environment = guestEnvironment(
            paths = paths,
            graphicsBridgeEnabled = graphicsBridgeEnabled,
            selectVirglDriver = false,
        ),
        workingDirectory = paths.vmRootDir,
    )

    /**
     * A login shell in the rootfs image — one per terminal window.
     *
     * The shell runs under `script`, which allocates a pseudo-terminal for
     * it. Without one, bash reads its commands from a plain pipe: no prompt,
     * no echo of what the user types, no job control, and `stty` (which the
     * terminal uses to match the window size) cannot work at all.
     */
    fun interactiveShell(
        paths: VmPaths,
        rootfsDir: File,
        sharedFolderDir: File?,
        graphicsBridgeEnabled: Boolean = false,
    ): NativeCommand = NativeCommand(
        program = paths.prootBinary,
        arguments = rootfsArguments(paths, rootfsDir, sharedFolderDir, graphicsBridgeEnabled) +
            listOf("/usr/bin/script", "-q", "-c", INTERACTIVE_LOGIN_COMMAND, "/dev/null"),
        environment = guestEnvironment(
            paths = paths,
            graphicsBridgeEnabled = graphicsBridgeEnabled,
            selectVirglDriver = false,
        ),
        workingDirectory = paths.vmRootDir,
    )

    /** Headless end-to-end probe: guest Mesa -> virpipe -> Android renderer. */
    fun graphicsProbe(
        paths: VmPaths,
        rootfsDir: File,
    ): NativeCommand = NativeCommand(
        program = paths.prootBinary,
        arguments = rootfsArguments(
            paths = paths,
            rootfsDir = rootfsDir,
            sharedFolderDir = null,
            graphicsBridgeEnabled = true,
        ) + listOf("/usr/bin/eglinfo", "-B", "-p", "surfaceless"),
        environment = guestEnvironment(
            paths = paths,
            graphicsBridgeEnabled = true,
            selectVirglDriver = true,
        ),
        workingDirectory = paths.vmRootDir,
    )

    /** The pre-image behaviour: a plain shell in the bundled Alpine userland. */
    fun legacyAlpineShell(paths: VmPaths): NativeCommand = NativeCommand(
        program = paths.prootBinary,
        arguments = listOf(
            "--kill-on-exit",
            "-r", paths.prootRootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-w", "/root",
            "/bin/sh", "-l",
        ),
        environment = paths.processEnvironment() + mapOf(
            "PROOT_LOADER" to paths.prootLoaderBinary.absolutePath,
            "PROOT_TMP_DIR" to paths.tmpDir.absolutePath,
            "PATH" to GUEST_PATH,
            "TERM" to "xterm-256color",
        ),
        workingDirectory = paths.vmRootDir,
    )

    /**
     * The shared PRoot argument set for rootfs images.
     *
     * `-0` fakes root: apt, dpkg and sshd all check they are uid 0.
     * `--link2symlink` turns hardlinks into symlinks — Android denies apps
     * the link() syscall, and dpkg uses it when unpacking.
     * `--sysvipc` emulates System V shared memory, which Android kernels
     * compile out and X11 clients ask for (MIT-SHM).
     * /dev/shm does not exist on Android, so a private directory is bound
     * there for POSIX shared memory.
     */
    private fun rootfsArguments(
        paths: VmPaths,
        rootfsDir: File,
        sharedFolderDir: File?,
        graphicsBridgeEnabled: Boolean,
    ): List<String> {
        val arguments = mutableListOf(
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
            "-0",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${paths.prootSharedMemoryDir.absolutePath}:/dev/shm",
        )
        if (sharedFolderDir != null) {
            // The same folder the QEMU guests see over 9p, reachable from
            // Android's Files app — the natural way to move files in and out.
            arguments += listOf("-b", "${sharedFolderDir.absolutePath}:$SHARED_FOLDER_GUEST_PATH")
        }
        if (graphicsBridgeEnabled) {
            // Bind only the renderer socket, not the app's entire runtime
            // directory. Mesa's vtest client sees its conventional path.
            arguments += listOf(
                "-b",
                "${paths.virglSocket.absolutePath}:$VIRGL_SOCKET_GUEST_PATH",
            )
        }
        arguments += listOf("-w", "/root")
        return arguments
    }

    /**
     * Environment for guest processes. PROOT_* steer PRoot itself; the rest
     * is what a login on this rootfs should see. HOME must be the guest's
     * /root, overriding the host-side HOME used when spawning QEMU.
     */
    private fun guestEnvironment(
        paths: VmPaths,
        graphicsBridgeEnabled: Boolean = false,
        selectVirglDriver: Boolean = false,
    ): Map<String, String> {
        val environment = paths.processEnvironment() + mapOf(
            // PROOT_* are read by PRoot itself, so they stay host paths.
            "PROOT_LOADER" to paths.prootLoaderBinary.absolutePath,
            "PROOT_TMP_DIR" to paths.tmpDir.absolutePath,
            // TMPDIR is read by guest programs, so it must be a path that
            // exists INSIDE the rootfs. Inheriting Android's temp directory
            // left it pointing at a path the guest cannot see, and Chromium
            // (VS Code) then failed to create its shared memory at all.
            "TMPDIR" to "/tmp",
            // PRoot's seccomp fast path is unreliable on modern Android
            // kernels: under a multi-process desktop, random syscalls start
            // failing with ENOSYS (pthread_create, unlink…) until the X
            // server's clients die. Plain ptrace translation is slower per
            // syscall but correct — the same choice proot-distro ships.
            "PROOT_NO_SECCOMP" to "1",
            "PATH" to GUEST_PATH,
            "HOME" to "/root",
            "USER" to "root",
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "LANG" to "C.UTF-8",
        )
        if (!graphicsBridgeEnabled) return environment
        val bridgeEnvironment = environment + mapOf(
            "DEX_GPU_BRIDGE" to "1",
            "VTEST_SOCKET_NAME" to VIRGL_SOCKET_GUEST_PATH,
        )
        if (!selectVirglDriver) return bridgeEnvironment
        return bridgeEnvironment + mapOf(
            "GALLIUM_DRIVER" to "virpipe",
            // Mesa categorises virpipe as a software winsys even though the
            // server forwards its rendering to Android's hardware driver.
            "LIBGL_ALWAYS_SOFTWARE" to "1",
            // Negotiate a baseline the Android emulator and every target
            // Galaxy support; asking for 3.2 crashes older EGL shims before
            // virgl can report a capability set.
            "MESA_GL_VERSION_OVERRIDE" to "3.3",
            "MESA_GLES_VERSION_OVERRIDE" to "3.1",
        )
    }

    const val DESKTOP_SUPERVISOR_GUEST_PATH = "/usr/local/bin/dex-desktop"
    const val CONSOLE_SESSION_GUEST_PATH = "/usr/local/bin/dex-session"
    const val SHARED_FOLDER_GUEST_PATH = "/root/shared"
    const val VIRGL_SOCKET_GUEST_PATH = "/tmp/.virgl_test"
    const val NATIVE_X11_DISPLAY_NUMBER = 1
    const val NATIVE_X11_DISPLAY = ":$NATIVE_X11_DISPLAY_NUMBER"

    /** Names Android supplementary groups before bash or `groups` can warn. */
    const val INTERACTIVE_LOGIN_COMMAND =
        "if [ -x /usr/local/bin/dex-name-groups ]; then " +
            "/usr/local/bin/dex-name-groups 2>/dev/null || true; fi; exec /bin/bash -l"

    /**
     * Debian and Ubuntu put some packaged programs in `games` directories,
     * so leaving those out means an `apt install` can appear to do nothing:
     * the program installs fine and then "command not found".
     */
    private const val GUEST_PATH =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:" +
            "/usr/local/games:/usr/games"
}
