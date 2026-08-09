package com.crunzex.linuxondex

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.crunzex.linuxondex.engine.proot.ProotEngine
import com.crunzex.linuxondex.engine.proot.AppManagedNativeX11Server
import com.crunzex.linuxondex.engine.runtime.PayloadInstaller
import com.crunzex.linuxondex.engine.runtime.VmPaths
import com.crunzex.linuxondex.vm.CpuConfig
import com.crunzex.linuxondex.vm.StorageConfig
import com.crunzex.linuxondex.vm.VmConfig
import com.crunzex.linuxondex.vm.VmState
import kotlin.time.Duration.Companion.seconds

/**
 * Approach 3 on-device proof: the PRoot engine extracts the bundled Alpine
 * userland and runs a real shell session in it via syscall translation.
 */
@RunWith(AndroidJUnit4::class)
class ProotEngineTest {

    private lateinit var engine: ProotEngine

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val paths = VmPaths(context)
        paths.createRuntimeDirectories()
        engine = ProotEngine(
            paths,
            PayloadInstaller(context, paths),
            AppManagedNativeX11Server(context, paths),
        )
    }

    @Test
    fun prootShellRunsAlpineUserland() = runBlocking {
        val config = VmConfig(
            id = "proottest",
            name = "PRoot Test",
            cpu = CpuConfig(coreCount = 1),
            memoryMb = 512,
            storage = StorageConfig(diskSizeGb = 4),
        )

        engine.start(config)
        try {
            assertTrue("engine should be running", engine.state.value is VmState.Running)

            val console = engine.openSerialConsole()
                ?: throw AssertionError("no console from running PRoot session")

            console.write("cat /etc/alpine-release && uname -m\n".toByteArray())
            val output = readUntil(console, marker = "aarch64", timeoutMs = 20_000)

            assertTrue(
                "expected Alpine release + aarch64, got: $output",
                output.contains(Regex("3\\.\\d+")) && output.contains("aarch64"),
            )
        } finally {
            engine.stop(gracePeriod = 5.seconds)
            assertEquals(
                "engine should stop cleanly",
                true,
                engine.state.value is VmState.Stopped,
            )
        }
    }

    private fun readUntil(
        console: com.crunzex.linuxondex.engine.SerialConsoleConnection,
        marker: String,
        timeoutMs: Long,
    ): String {
        val collected = StringBuilder()
        val buffer = ByteArray(4096)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val count = console.read(buffer)
            if (count < 0) break
            collected.append(String(buffer, 0, count))
            if (collected.contains(marker)) break
        }
        return collected.toString()
    }
}
