package com.crunzex.linuxondex

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.crunzex.linuxondex.engine.SerialConsoleConnection
import com.crunzex.linuxondex.engine.proot.ProotEngine
import com.crunzex.linuxondex.engine.proot.AppManagedNativeX11Server
import com.crunzex.linuxondex.engine.runtime.PayloadInstaller
import com.crunzex.linuxondex.engine.runtime.VmPaths
import com.crunzex.linuxondex.vm.PreparedImageConfig
import com.crunzex.linuxondex.vm.PreparedImageFormat
import com.crunzex.linuxondex.vm.DisplayEndpoint
import com.crunzex.linuxondex.vm.ScreenResolution
import com.crunzex.linuxondex.vm.VmConfig
import com.crunzex.linuxondex.vm.VmState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Imports and starts the release GNOME-like XFCE rootfs, then verifies the
 * desktop, accelerated graphics, and shared-display paths users receive.
 *
 * Push the artifact before running:
 * ```
 * adb push dist/v1.3.0-beta2/linux-on-dex-ubuntu-24.04-proot-gnome-like-xfce-arm64.rootfs.tar.gz \
 *   /sdcard/Android/data/com.crunzex.linuxondex/files/vm-images/
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ProotXfceArtifactTest {

    private lateinit var paths: VmPaths
    private lateinit var engine: ProotEngine
    private var archive: File? = null

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        paths = VmPaths(context)
        paths.createRuntimeDirectories()
        archive = paths.vmImagesDir?.listFiles()?.firstOrNull {
            it.name == ROOTFS_ARCHIVE_NAME
        }
        engine = ProotEngine(
            paths,
            PayloadInstaller(context, paths),
            AppManagedNativeX11Server(context, paths),
        )
    }

    @Test
    fun gnomeLikeDesktopStartsWithAcceleratedGraphicsAndSharedDisplay() = runBlocking {
        val rootfsArchive = archive
        assumeTrue("XFCE release rootfs not pushed, skipping", rootfsArchive?.exists() == true)
        requireNotNull(rootfsArchive)

        val config = VmConfig(
            id = "xfce-artifact-test",
            name = "Ubuntu GNOME-like artifact test",
            display = com.crunzex.linuxondex.vm.DisplayConfig(
                resolution = ScreenResolution.WXGA_1280_800,
                vncDisplayNumber = 8,
            ),
            preparedImage = PreparedImageConfig(
                displayName = "Ubuntu 24.04 GNOME-like XFCE",
                diskImagePath = rootfsArchive.absolutePath,
                format = PreparedImageFormat.PROOT_ROOTFS,
            ),
        )

        engine.start(config)
        try {
            val running = engine.state.value
            assertTrue("desktop engine should be running, got $running", running is VmState.Running)
            assertTrue(
                "desktop should publish native X11, got $running",
                (running as VmState.Running).displayEndpoint is DisplayEndpoint.NativeX11,
            )

            val console = engine.openSerialConsole()
                ?: throw AssertionError("no shell from running XFCE rootfs")
            console.use {
                val validationCommand =
                    "dex-gpu eglinfo -B -p surfaceless 2>&1; " +
                        "printf 'EGL_STATUS=%s\\n' \"$?\"; " +
                        "if pgrep -x xfsettingsd >/dev/null && " +
                        "pgrep -x xfwm4 >/dev/null && " +
                        "pgrep -x xfce4-panel >/dev/null && " +
                        "! pgrep -x xfdesktop >/dev/null; " +
                        "then printf 'SESSION_' && printf 'STATUS=LEAN\\n'; " +
                        "else printf 'SESSION_' && printf 'STATUS=INVALID\\n'; fi; " +
                        "if test \"\$(cat /usr/local/share/linux-on-dex/display-backend)\" = native-x11 && " +
                        "test -S /tmp/.X11-unix/X1; " +
                        "then printf 'DISPLAY_' && printf 'STATUS=NATIVE_X11\\n'; " +
                        "else printf 'DISPLAY_' && printf 'STATUS=INVALID\\n'; fi; " +
                        "if command -v code >/dev/null && test -x /usr/share/code/code; " +
                        "then printf 'CODE_' && printf 'STATUS=INSTALLED\\n'; " +
                        "else printf 'CODE_' && printf 'STATUS=MISSING\\n'; fi; " +
                        "if command -v firefox >/dev/null && test -x /usr/lib/firefox/firefox; " +
                        "then printf 'FIREFOX_' && printf 'STATUS=INSTALLED\\n'; " +
                        "else printf 'FIREFOX_' && printf 'STATUS=MISSING\\n'; fi; " +
                        "if grep -q 'GALLIUM_DRIVER=llvmpipe' /usr/local/bin/dex-desktop && " +
                        "test -x /usr/local/bin/dex-gpu; " +
                        "then printf 'GRAPHICS_' && printf 'STATUS=ISOLATED\\n'; fi; " +
                        "if grep -q '1.3.0-beta2' /etc/linux-on-dex-release; " +
                        "then printf 'RELEASE_' && printf 'VERSION=1.3.0-beta2\\n'; fi; " +
                        "/usr/local/bin/dex-name-groups 2>/dev/null || true; " +
                        "if groups 2>&1 | grep -q 'cannot find name'; " +
                        "then printf 'GROUP_' && printf 'STATUS=INVALID\\n'; " +
                        "else printf 'GROUP_' && printf 'STATUS=NAMED\\n'; fi; " +
                        "printf 'XFCE_' && printf 'ARTIFACT_OK\\n'\n"
                console.write(validationCommand.toByteArray())
                val output = readUntil(console, "XFCE_ARTIFACT_OK", timeoutMs = 30_000)
                assertTrue("XFCE artifact probe did not complete: $output", output.contains("XFCE_ARTIFACT_OK"))
                assertTrue("eglinfo failed inside Ubuntu: $output", output.contains("EGL_STATUS=0"))
                assertTrue(
                    "Ubuntu Mesa did not render through the native virgl bridge: $output",
                    output.contains("OpenGL core profile renderer: virgl", ignoreCase = true),
                )
                assertTrue(
                    "GNOME-like session started unexpected services: $output",
                    output.contains("SESSION_STATUS=LEAN"),
                )
                assertTrue(
                    "native X11 is unavailable: $output",
                    output.contains("DISPLAY_STATUS=NATIVE_X11"),
                )
                assertTrue("VS Code is not installed: $output", output.contains("CODE_STATUS=INSTALLED"))
                assertTrue("Firefox is not installed: $output", output.contains("FIREFOX_STATUS=INSTALLED"))
                assertTrue(
                    "desktop and native application graphics are not isolated: $output",
                    output.contains("GRAPHICS_STATUS=ISOLATED"),
                )
                assertTrue("release metadata is stale: $output", output.contains("RELEASE_VERSION=1.3.0-beta2"))
                assertTrue("Android group IDs are unnamed: $output", output.contains("GROUP_STATUS=NAMED"))
            }

        } finally {
            engine.stop(gracePeriod = 5.seconds)
        }
    }

    /** Reads the native-X11 guest probe output from its PRoot console. */
    private fun readUntil(
        console: SerialConsoleConnection,
        marker: String,
        timeoutMs: Long,
    ): String {
        val collected = StringBuilder()
        val buffer = ByteArray(4_096)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val count = console.read(buffer)
            if (count < 0) break
            collected.append(String(buffer, 0, count))
            if (collected.contains(marker)) break
        }
        return collected.toString()
    }

    companion object {
        private const val ROOTFS_ARCHIVE_NAME =
            "linux-on-dex-ubuntu-24.04-proot-gnome-like-xfce-arm64.rootfs.tar.gz"
    }
}
