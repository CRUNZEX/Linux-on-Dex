package com.crunzex.linuxondex.capability

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DexEnvironmentTest {

    @Test
    fun `desk ui mode is desktop`() {
        val uiMode = Configuration.UI_MODE_TYPE_DESK or Configuration.UI_MODE_NIGHT_YES
        assertTrue(DexEnvironment.isDesktopUiMode(uiMode))
    }

    @Test
    fun `normal and other ui modes are not desktop`() {
        assertFalse(DexEnvironment.isDesktopUiMode(Configuration.UI_MODE_TYPE_NORMAL))
        assertFalse(DexEnvironment.isDesktopUiMode(Configuration.UI_MODE_TYPE_TELEVISION))
        assertFalse(
            DexEnvironment.isDesktopUiMode(
                Configuration.UI_MODE_TYPE_CAR or Configuration.UI_MODE_NIGHT_NO
            )
        )
    }

    @Test
    fun `night flags never masquerade as desktop`() {
        assertFalse(
            DexEnvironment.isDesktopUiMode(
                Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES
            )
        )
    }
}
