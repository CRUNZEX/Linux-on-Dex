package com.crunzex.linuxondex.engine.proot

import com.crunzex.linuxondex.engine.runtime.NativeCommand
import com.crunzex.linuxondex.engine.runtime.VmPaths
import java.io.File

/**
 * Builds the exact PRoot command lines the engine runs. Pure construction —
 * no process is spawned here — so every argument choice is unit-testable.
 *
 * Three shapes exist:
 *  - the desktop session: the long-lived supervisor inside a rootfs image
 *    that publishes VNC and owns the graphical session,
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
    ): NativeCommand = NativeCommand(
        program = paths.prootBinary,
        arguments = rootfsArguments(paths, rootfsDir, sharedFolderDir) +
            listOf(DESKTOP_SUPERVISOR_GUEST_PATH),
        environment = guestEnvironment(paths) + mapOf(
            "DEX_RESOLUTION" to displayResolution,
            "DEX_VNC_PORT" to vncPort.toString(),
        ),
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
    ): NativeCommand = NativeCommand(
        program = paths.prootBinary,
        arguments = rootfsArguments(paths, rootfsDir, sharedFolderDir) +
            listOf(CONSOLE_SESSION_GUEST_PATH),
        environment = guestEnvironment(paths),
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
    ): NativeCommand = NativeCommand(
        program = paths.prootBinary,
        arguments = rootfsArguments(paths, rootfsDir, sharedFolderDir) +
            listOf("/usr/bin/script", "-q", "-c", "/bin/bash -l", "/dev/null"),
        environment = guestEnvironment(paths),
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
        arguments += listOf("-w", "/root")
        return arguments
    }

    /**
     * Environment for guest processes. PROOT_* steer PRoot itself; the rest
     * is what a login on this rootfs should see. HOME must be the guest's
     * /root, overriding the host-side HOME used when spawning QEMU.
     */
    private fun guestEnvironment(paths: VmPaths): Map<String, String> =
        paths.processEnvironment() + mapOf(
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

    const val DESKTOP_SUPERVISOR_GUEST_PATH = "/usr/local/bin/dex-desktop"
    const val CONSOLE_SESSION_GUEST_PATH = "/usr/local/bin/dex-session"
    const val SHARED_FOLDER_GUEST_PATH = "/root/shared"

    /**
     * Debian and Ubuntu put some packaged programs in `games` directories,
     * so leaving those out means an `apt install` can appear to do nothing:
     * the program installs fine and then "command not found".
     */
    private const val GUEST_PATH =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:" +
            "/usr/local/games:/usr/games"
}
