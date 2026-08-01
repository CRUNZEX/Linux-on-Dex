package com.crunzex.linuxondex.about

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The One UI "new" dot is a nudge that must be honest: it appears only for a
 * release that genuinely exists and that the user has not already looked at,
 * and it never appears because a network check failed.
 */
class UpdateNoticeTest {

    private val newerRelease = UpdateStatus.Available(
        version = AppVersion(1, 1, 0),
        releasePageUrl = "https://github.com/CRUNZEX/Linux-on-Dex/releases/tag/v1.1.0",
    )

    @Test
    fun `a newer release the user has never seen shows the dot`() {
        assertTrue(UpdateNotice.shouldShowUpdateDot(newerRelease, lastSeenVersion = null))
    }

    @Test
    fun `the dot stays hidden once that release has been seen`() {
        assertFalse(
            UpdateNotice.shouldShowUpdateDot(newerRelease, lastSeenVersion = AppVersion(1, 1, 0))
        )
    }

    @Test
    fun `a release newer than the one already seen shows the dot again`() {
        assertTrue(
            UpdateNotice.shouldShowUpdateDot(
                UpdateStatus.Available(AppVersion(1, 2, 0), newerRelease.releasePageUrl),
                lastSeenVersion = AppVersion(1, 1, 0),
            )
        )
    }

    @Test
    fun `being up to date never shows a dot`() {
        assertFalse(UpdateNotice.shouldShowUpdateDot(UpdateStatus.Latest, lastSeenVersion = null))
    }

    @Test
    fun `a check still running never shows a dot`() {
        // Showing it optimistically would flash a marker on every launch.
        assertFalse(UpdateNotice.shouldShowUpdateDot(UpdateStatus.Checking, lastSeenVersion = null))
    }

    @Test
    fun `an older seen version does not suppress a newer one`() {
        assertTrue(
            UpdateNotice.shouldShowUpdateDot(newerRelease, lastSeenVersion = AppVersion(1, 0, 5))
        )
    }
}
