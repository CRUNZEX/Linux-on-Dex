package com.crunzex.linuxondex.capability

import com.crunzex.linuxondex.core.DiagnosticsLogExporter
import java.text.SimpleDateFormat
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeX reports itself differently depending on One UI version and whether the
 * app is on the phone screen or the monitor, so detection asks every source
 * and accepts any positive. Getting this wrong shows the user "Not active"
 * while they are plainly using DeX.
 */
class DexDetectionTest {

    @Test
    fun `the standard desk ui-mode is enough on its own`() {
        assertTrue(
            DexEnvironment.isDesktopActive(
                hasDesktopUiMode = true,
                hasSamsungConfigurationFlag = false,
                hasSamsungServiceFlag = false,
                isOnExternalDisplay = false,
            )
        )
    }

    @Test
    fun `samsung's own signals count even when the ui-mode does not say desk`() {
        // Older One UI leaves uiMode at NORMAL while DeX is running, which is
        // exactly the case that used to read as "Not active".
        assertTrue(
            DexEnvironment.isDesktopActive(
                hasDesktopUiMode = false,
                hasSamsungConfigurationFlag = true,
                hasSamsungServiceFlag = false,
                isOnExternalDisplay = false,
            )
        )
        assertTrue(
            DexEnvironment.isDesktopActive(
                hasDesktopUiMode = false,
                hasSamsungConfigurationFlag = false,
                hasSamsungServiceFlag = true,
                isOnExternalDisplay = false,
            )
        )
    }

    @Test
    fun `a window presented on the monitor counts as DeX`() {
        // Standalone DeX puts the app on the DeX display, not the phone's.
        assertTrue(
            DexEnvironment.isDesktopActive(
                hasDesktopUiMode = false,
                hasSamsungConfigurationFlag = false,
                hasSamsungServiceFlag = false,
                isOnExternalDisplay = true,
            )
        )
    }

    @Test
    fun `an ordinary phone window is not DeX`() {
        assertFalse(
            DexEnvironment.isDesktopActive(
                hasDesktopUiMode = false,
                hasSamsungConfigurationFlag = false,
                hasSamsungServiceFlag = false,
                isOnExternalDisplay = false,
            )
        )
    }

    @Test
    fun `the ui-mode check reads only the type bits`() {
        val deskWithNightBits = android.content.res.Configuration.UI_MODE_TYPE_DESK or 0x30
        assertTrue(DexEnvironment.isDesktopUiMode(deskWithNightBits))
        assertFalse(
            DexEnvironment.isDesktopUiMode(android.content.res.Configuration.UI_MODE_TYPE_NORMAL)
        )
    }

    @Test
    fun `an exported log is named like the backups, by date and time`() {
        val moment = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2026-07-31 22:04:31") as Date

        assertEquals(
            "linux-on-dex-20260731-220431.log",
            DiagnosticsLogExporter.logFileName(moment),
        )
    }
}
