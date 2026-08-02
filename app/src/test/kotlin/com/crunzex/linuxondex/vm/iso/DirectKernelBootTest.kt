package com.crunzex.linuxondex.vm.iso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectKernelBootTest {

    @Test
    fun `casper family argument comes first`() {
        val commandLine = DirectKernelBoot.buildCommandLine(
            familyArguments = "boot=casper",
            extraArguments = "",
            sendConsoleToSerialPort = false,
        )

        assertTrue("got: $commandLine", commandLine.startsWith("boot=casper"))
    }

    @Test
    fun `qualcomm only services are masked`() {
        // These restart in a loop on QEMU's virt board, flooding the console
        // and burning emulated CPU the rest of the boot needs.
        val commandLine = DirectKernelBoot.buildCommandLine(
            familyArguments = "boot=casper",
            extraArguments = "",
            sendConsoleToSerialPort = false,
        )

        assertTrue(commandLine.contains("systemd.mask=pd-mapper.service"))
        assertTrue(commandLine.contains("systemd.mask=qrtr-ns.service"))
    }

    @Test
    fun `serial console keeps the graphical console as well`() {
        val commandLine = DirectKernelBoot.buildCommandLine(
            familyArguments = "",
            extraArguments = "",
            sendConsoleToSerialPort = true,
        )

        // tty0 must stay so the VNC display keeps receiving output.
        assertTrue(commandLine.contains("console=tty0"))
        assertTrue(commandLine.contains("console=ttyAMA0,115200"))
        assertTrue(
            "the serial console must be listed last to become the primary console",
            commandLine.indexOf("console=ttyAMA0") > commandLine.indexOf("console=tty0"),
        )
    }

    @Test
    fun `serial console is omitted when not requested`() {
        val commandLine = DirectKernelBoot.buildCommandLine(
            familyArguments = "boot=casper",
            extraArguments = "",
            sendConsoleToSerialPort = false,
        )

        assertFalse(commandLine.contains("console="))
    }

    @Test
    fun `user arguments are appended last so they win`() {
        val commandLine = DirectKernelBoot.buildCommandLine(
            familyArguments = "boot=casper",
            extraArguments = "nomodeset quiet",
            sendConsoleToSerialPort = true,
        )

        assertTrue("got: $commandLine", commandLine.endsWith("nomodeset quiet"))
    }

    @Test
    fun `blank sections do not leave double spaces`() {
        val commandLine = DirectKernelBoot.buildCommandLine(
            familyArguments = "",
            extraArguments = "   ",
            sendConsoleToSerialPort = false,
        )

        assertFalse(commandLine.contains("  "))
        assertEquals(commandLine.trim(), commandLine)
    }
}
