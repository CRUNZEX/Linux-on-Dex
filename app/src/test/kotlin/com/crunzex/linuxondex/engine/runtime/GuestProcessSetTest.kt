package com.crunzex.linuxondex.engine.runtime

import com.crunzex.linuxondex.engine.runtime.GuestProcessSet.ProcessSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These rules decide what gets SIGKILLed, so the important assertions are
 * the negative ones: nothing outside the app's own guest directories may
 * ever be selected, and the app must never select itself.
 */
class GuestProcessSetTest {

    private val imagesDirectory = "/data/user/0/com.crunzex.linuxondex/files/vm/proot-images"
    private val nativeLibraryDirectory = "/data/app/~~abc==/com.crunzex.linuxondex-xyz==/lib/arm64"
    private val guestDirectories = listOf(imagesDirectory, nativeLibraryDirectory)

    private val ownProcessId = 1000

    private fun process(
        processId: Int,
        executablePath: String? = null,
        commandLine: String = "",
        parentProcessId: Int? = null,
    ) = ProcessSnapshot(processId, executablePath, commandLine, parentProcessId)

    private fun select(vararg snapshots: ProcessSnapshot): List<Int> =
        GuestProcessSet.runningInsideGuestDirectories(
            snapshots = snapshots.toList(),
            guestDirectoryPaths = guestDirectories,
            ownProcessId = ownProcessId,
        )

    @Test
    fun `a program executing inside an extracted image is a guest`() {
        val gnomeShell = process(
            processId = 2001,
            executablePath = "$imagesDirectory/ubuntu-proot-gnome/usr/bin/gnome-shell",
        )

        assertEquals(listOf(2001), select(gnomeShell))
    }

    @Test
    fun `the launcher is a guest through its arguments`() {
        // PRoot's own executable is the app's bundled library, so only its
        // arguments reveal which rootfs it opened.
        val prootLauncher = process(
            processId = 2002,
            executablePath = "$nativeLibraryDirectory/libproot.so",
            commandLine = "libproot.so -r $imagesDirectory/ubuntu-proot-gnome /usr/local/bin/dex-desktop",
        )

        assertEquals(listOf(2002), select(prootLauncher))
    }

    @Test
    fun `a stale QEMU is a guest — it holds the display port too`() {
        val qemu = process(
            processId = 2003,
            executablePath = "$nativeLibraryDirectory/libqemu-system-aarch64.so",
        )

        assertEquals(listOf(2003), select(qemu))
    }

    @Test
    fun `the app's own process is never selected`() {
        // Even if it somehow matched, killing it would kill the killer.
        val ownProcess = process(
            processId = ownProcessId,
            executablePath = "$nativeLibraryDirectory/libproot.so",
            commandLine = "com.crunzex.linuxondex $imagesDirectory",
        )

        assertTrue(select(ownProcess).isEmpty())
    }

    @Test
    fun `system and unrelated app processes are never selected`() {
        val systemServer = process(4001, executablePath = "/system/bin/app_process64")
        val launcher = process(4002, executablePath = "/system/bin/app_process64", commandLine = "com.sec.android.app.launcher")
        val ourOwnUiProcess = process(4003, executablePath = "/system/bin/app_process64", commandLine = "com.crunzex.linuxondex")
        val anotherAppsProot = process(
            processId = 4004,
            executablePath = "/data/user/0/com.example.other/files/proot-images/rootfs/usr/bin/gnome-shell",
        )

        assertTrue(select(systemServer, launcher, ourOwnUiProcess, anotherAppsProot).isEmpty())
    }

    @Test
    fun `a guest-looking command line outside our directories is not enough`() {
        // PRoot rewrites paths, so a guest program's own idea of its path
        // says nothing about where it really lives.
        val impostor = process(
            processId = 5001,
            executablePath = "/usr/bin/gnome-shell",
            commandLine = "/usr/bin/gnome-shell --x11",
        )

        assertTrue(select(impostor).isEmpty())
    }

    @Test
    fun `an unreadable executable path falls back to the command line`() {
        val unreadable = process(
            processId = 5002,
            executablePath = null,
            commandLine = "sh $imagesDirectory/ubuntu/usr/local/bin/dex-desktop",
        )

        assertEquals(listOf(5002), select(unreadable))
    }

    @Test
    fun `nothing is selected when there are no guest directories`() {
        val anything = process(6001, executablePath = "$imagesDirectory/usr/bin/gnome-shell")

        val selected = GuestProcessSet.runningInsideGuestDirectories(
            snapshots = listOf(anything),
            guestDirectoryPaths = emptyList(),
            ownProcessId = ownProcessId,
        )

        assertTrue(selected.isEmpty())
    }

    @Test
    fun `a blank directory path cannot match everything`() {
        // A misconfigured path must never become a prefix that matches all.
        val unrelated = process(6002, executablePath = "/system/bin/app_process64")

        val selected = GuestProcessSet.runningInsideGuestDirectories(
            snapshots = listOf(unrelated),
            guestDirectoryPaths = listOf("", "   "),
            ownProcessId = ownProcessId,
        )

        assertTrue(selected.isEmpty())
    }

    @Test
    fun `a sibling directory sharing a name prefix is not selected`() {
        // "proot-images-backup" must not match "proot-images".
        val sibling = process(
            processId = 6003,
            executablePath = "${imagesDirectory}-backup/rootfs/usr/bin/gnome-shell",
        )

        assertTrue(select(sibling).isEmpty())
    }

    @Test
    fun `owned process descendants include nested children but not siblings`() {
        val launcher = process(processId = 7000, parentProcessId = ownProcessId)
        val script = process(processId = 7001, parentProcessId = launcher.processId)
        val bash = process(processId = 7002, parentProcessId = script.processId)
        val unrelatedSibling = process(processId = 7003, parentProcessId = ownProcessId)

        val descendants = GuestProcessSet.descendantProcessIds(
            snapshots = listOf(launcher, script, bash, unrelatedSibling),
            rootProcessId = launcher.processId,
        )

        assertEquals(listOf(script.processId, bash.processId), descendants)
    }

    @Test
    fun `a corrupt parent cycle cannot return a process twice`() {
        val first = process(processId = 7101, parentProcessId = 7102)
        val second = process(processId = 7102, parentProcessId = 7101)

        val descendants = GuestProcessSet.descendantProcessIds(
            snapshots = listOf(first, second),
            rootProcessId = first.processId,
        )

        assertEquals(listOf(second.processId), descendants)
    }
}
