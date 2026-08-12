package com.crunzex.linuxondex.engine.proot

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererFallbackLadderTest {

    private val workDir = Files.createTempDirectory("ladder-test").toFile()
    private val stateFile = workDir.resolve("renderer-stage")

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    private fun ladder(stamp: String = "image-1") =
        RendererFallbackLadder(stateFile, rootfsStamp = stamp)

    @Test
    fun `a fresh image starts on the native renderer`() {
        assertEquals(RendererStage.NATIVE, ladder().currentStage())
    }

    @Test
    fun `SIGILL walks the ladder one rung at a time and then stops`() {
        val exitSigill = RendererFallbackLadder.EXIT_CODE_ILLEGAL_INSTRUCTION

        assertEquals(RendererStage.PORTABLE_CPU, ladder().advanceAfterExit(exitSigill))
        assertEquals(RendererStage.PORTABLE_CPU, ladder().currentStage())

        assertEquals(RendererStage.FAILSAFE_SOFTPIPE, ladder().advanceAfterExit(exitSigill))
        assertEquals(RendererStage.FAILSAFE_SOFTPIPE, ladder().currentStage())

        assertNull("the last rung has nowhere to go", ladder().advanceAfterExit(exitSigill))
        assertEquals(RendererStage.FAILSAFE_SOFTPIPE, ladder().currentStage())
    }

    @Test
    fun `other exit codes never change the renderer`() {
        listOf(1, 137, 139, 143, -1).forEach { exitCode ->
            assertNull("exit $exitCode must not advance", ladder().advanceAfterExit(exitCode))
        }
        assertEquals(RendererStage.NATIVE, ladder().currentStage())
    }

    @Test
    fun `a new image forgets the downgrade`() {
        ladder(stamp = "image-1")
            .advanceAfterExit(RendererFallbackLadder.EXIT_CODE_ILLEGAL_INSTRUCTION)

        assertEquals(
            "different stamp must start over at NATIVE",
            RendererStage.NATIVE,
            ladder(stamp = "image-2").currentStage(),
        )
    }

    @Test
    fun `a corrupt state file falls back to native instead of crashing`() {
        stateFile.writeText("not-a-stage\ngarbage\n")
        assertEquals(RendererStage.NATIVE, ladder().currentStage())
    }

    @Test
    fun `portable cpuinfo lists every core with baseline features`() {
        val text = PortableCpuProfile.cpuinfoText(processorCount = 3)

        assertEquals(3, Regex("^processor\\t: ", RegexOption.MULTILINE).findAll(text).count())
        assertTrue("must identify as Cortex-A72", text.contains("CPU part\t: 0xd08"))
        assertTrue(
            "features must stay ARMv8.0 baseline",
            text.lines().filter { it.startsWith("Features") }
                .all { it == "Features\t: fp asimd evtstrm crc32 cpuid" },
        )
    }
}
