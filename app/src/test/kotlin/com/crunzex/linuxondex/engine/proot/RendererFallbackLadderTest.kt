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
    fun `a fresh image starts on the GPU rung`() {
        assertEquals(RendererStage.GPU_VIRGL, ladder().currentStage())
    }

    @Test
    fun `any fatal exit abandons the GPU rung`() {
        assertEquals(RendererStage.NATIVE, ladder().advanceAfterExit(exitCode = 1))
        assertEquals(RendererStage.NATIVE, ladder().currentStage())
    }

    @Test
    fun `SIGILL on the GPU rung also lands on native first`() {
        val exitSigill = RendererFallbackLadder.EXIT_CODE_ILLEGAL_INSTRUCTION
        assertEquals(RendererStage.NATIVE, ladder().advanceAfterExit(exitSigill))
        assertEquals(RendererStage.NATIVE, ladder().currentStage())
    }

    @Test
    fun `external kills never blame the GPU renderer`() {
        listOf(137, 143, 0, -1).forEach { exitCode ->
            assertNull("exit $exitCode must not advance", ladder().advanceAfterExit(exitCode))
        }
        assertEquals(RendererStage.GPU_VIRGL, ladder().currentStage())
    }

    @Test
    fun `SIGILL walks the CPU rungs one at a time and then stops`() {
        val exitSigill = RendererFallbackLadder.EXIT_CODE_ILLEGAL_INSTRUCTION
        ladder().advanceAfterExit(exitSigill) // GPU_VIRGL -> NATIVE

        assertEquals(RendererStage.PORTABLE_CPU, ladder().advanceAfterExit(exitSigill))
        assertEquals(RendererStage.PORTABLE_CPU, ladder().currentStage())

        assertEquals(RendererStage.FAILSAFE_SOFTPIPE, ladder().advanceAfterExit(exitSigill))
        assertEquals(RendererStage.FAILSAFE_SOFTPIPE, ladder().currentStage())

        assertNull("the last rung has nowhere to go", ladder().advanceAfterExit(exitSigill))
        assertEquals(RendererStage.FAILSAFE_SOFTPIPE, ladder().currentStage())
    }

    @Test
    fun `on the CPU rungs only SIGILL changes the renderer`() {
        val exitSigill = RendererFallbackLadder.EXIT_CODE_ILLEGAL_INSTRUCTION
        ladder().advanceAfterExit(exitSigill) // GPU_VIRGL -> NATIVE

        listOf(1, 137, 139, 143, -1).forEach { exitCode ->
            assertNull("exit $exitCode must not advance", ladder().advanceAfterExit(exitCode))
        }
        assertEquals(RendererStage.NATIVE, ladder().currentStage())
    }

    @Test
    fun `a skipped GPU boot advances from the stage that actually ran`() {
        // Preference off or bridge failure runs NATIVE while the file still
        // says GPU; a SIGILL then must move to PORTABLE_CPU, not NATIVE.
        val exitSigill = RendererFallbackLadder.EXIT_CODE_ILLEGAL_INSTRUCTION
        assertEquals(
            RendererStage.PORTABLE_CPU,
            ladder().advanceAfterExit(exitSigill, ranStage = RendererStage.NATIVE),
        )
        assertEquals(RendererStage.PORTABLE_CPU, ladder().currentStage())
    }

    @Test
    fun `a mid-session GPU failure steps down without an exit code`() {
        assertEquals(RendererStage.NATIVE, ladder().stepDownFromGpuStage())
        assertEquals(RendererStage.NATIVE, ladder().currentStage())

        assertNull("already below GPU: nothing to record", ladder().stepDownFromGpuStage())
        assertEquals(RendererStage.NATIVE, ladder().currentStage())
    }

    @Test
    fun `forgetting the stored stage retries the GPU rung`() {
        ladder().advanceAfterExit(exitCode = 1)
        assertEquals(RendererStage.NATIVE, ladder().currentStage())

        RendererFallbackLadder.forgetStoredStage(stateFile)
        assertEquals(RendererStage.GPU_VIRGL, ladder().currentStage())
    }

    @Test
    fun `a new image forgets the downgrade`() {
        ladder(stamp = "image-1").advanceAfterExit(exitCode = 1)

        assertEquals(
            "different stamp must start over at the GPU rung",
            RendererStage.GPU_VIRGL,
            ladder(stamp = "image-2").currentStage(),
        )
    }

    @Test
    fun `a corrupt state file falls back to the first rung instead of crashing`() {
        stateFile.writeText("not-a-stage\ngarbage\n")
        assertEquals(RendererStage.GPU_VIRGL, ladder().currentStage())
    }

    @Test
    fun `stage profiles expose the right renderer traits`() {
        assertTrue(RendererStage.GPU_VIRGL.usesGpuBridgeRenderer)
        assertTrue(
            "GPU sessions must not bind the curated cpuinfo",
            !RendererStage.GPU_VIRGL.usesPortableCpuProfile,
        )
        assertTrue(!RendererStage.NATIVE.usesPortableCpuProfile)
        assertTrue(RendererStage.PORTABLE_CPU.usesPortableCpuProfile)
        assertTrue(RendererStage.FAILSAFE_SOFTPIPE.usesPortableCpuProfile)
        assertTrue(RendererStage.FAILSAFE_SOFTPIPE.usesSoftpipeRenderer)
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
