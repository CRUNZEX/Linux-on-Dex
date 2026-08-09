package com.crunzex.linuxondex.about

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The About screen offers an Update button, so the decision behind it has
 * to be exactly right: never nudge a user toward a download when the check
 * did not actually establish that a newer release exists.
 */
class AppVersionTest {

    private val profileJson = Json { ignoreUnknownKeys = true }

    // ---- Parsing -------------------------------------------------------------

    @Test
    fun `release tags are read with or without the v prefix`() {
        assertEquals(AppVersion(1, 0, 0), AppVersion.parseOrNull("1.0.0"))
        assertEquals(AppVersion(1, 0, 0), AppVersion.parseOrNull("v1.0.0"))
        assertEquals(AppVersion(2, 4, 11), AppVersion.parseOrNull("  v2.4.11  "))
    }

    @Test
    fun `missing parts count as zero`() {
        assertEquals(AppVersion(1, 2, 0), AppVersion.parseOrNull("1.2"))
        assertEquals(AppVersion(3, 0, 0), AppVersion.parseOrNull("v3"))
    }

    @Test
    fun `pre-release suffixes are ignored rather than rejected`() {
        assertEquals(AppVersion(1, 1, 0), AppVersion.parseOrNull("v1.1.0-beta2"))
        assertEquals(AppVersion(1, 1, 0), AppVersion.parseOrNull("1.1.0+build7"))
    }

    @Test
    fun `text that is not a version is rejected, never guessed at`() {
        // Guessing 0.0.0 here would make every real release look newer.
        assertNull(AppVersion.parseOrNull("nightly"))
        assertNull(AppVersion.parseOrNull(""))
        assertNull(AppVersion.parseOrNull(null))
        assertNull(AppVersion.parseOrNull("v"))
    }

    // ---- Ordering ------------------------------------------------------------

    @Test
    fun `versions order by major then minor then patch`() {
        assertTrue(AppVersion(1, 0, 0) < AppVersion(1, 0, 1))
        assertTrue(AppVersion(1, 0, 9) < AppVersion(1, 1, 0))
        assertTrue(AppVersion(1, 9, 9) < AppVersion(2, 0, 0))
        assertEquals(0, AppVersion(1, 0, 0).compareTo(AppVersion(1, 0, 0)))
        // Plain numeric ordering, not string ordering.
        assertTrue(AppVersion(1, 10, 0) > AppVersion(1, 9, 0))
    }

    @Test
    fun `a version prints the way releases are tagged`() {
        assertEquals("1.0.0", AppVersion(1, 0, 0).toString())
    }

    // ---- The update decision -------------------------------------------------

    private val installed = AppVersion(1, 0, 0)
    private val releaseUrl = "https://github.com/CRUNZEX/linux-on-dex/releases/tag/v1.1.0"

    @Test
    fun `a newer published release is offered`() {
        val status = decideUpdateStatus(installed, "v1.1.0", releaseUrl)

        assertEquals(UpdateStatus.Available(AppVersion(1, 1, 0), releaseUrl), status)
    }

    @Test
    fun `the same or an older release means we are current`() {
        assertEquals(UpdateStatus.Latest, decideUpdateStatus(installed, "v1.0.0", releaseUrl))
        assertEquals(UpdateStatus.Latest, decideUpdateStatus(installed, "v0.9.9", releaseUrl))
    }

    @Test
    fun `an unreachable or unreadable release feed reads as latest, not as an update`() {
        // This is the state the user sees today: the repository has no
        // release yet, so the check comes back empty.
        assertEquals(UpdateStatus.Latest, decideUpdateStatus(installed, null, null))
        assertEquals(UpdateStatus.Latest, decideUpdateStatus(installed, "", null))
        assertEquals(UpdateStatus.Latest, decideUpdateStatus(installed, "not-a-version", releaseUrl))
    }

    @Test
    fun `an update is never offered without a usable https link`() {
        assertEquals(UpdateStatus.Latest, decideUpdateStatus(installed, "v2.0.0", null))
        assertEquals(
            UpdateStatus.Latest,
            decideUpdateStatus(installed, "v2.0.0", "http://example.com/insecure"),
        )
    }

    @Test
    fun `an unknown installed version never claims an update`() {
        assertEquals(UpdateStatus.Latest, decideUpdateStatus(null, "v9.9.9", releaseUrl))
    }

    // ---- Profile parsing -----------------------------------------------------

    @Test
    fun `the developer profile is read from GitHub's real response shape`() {
        // Trimmed from https://api.github.com/users/CRUNZEX; the unknown
        // fields must be ignored rather than fail the whole screen.
        val body = """
            {
              "login": "CRUNZEX",
              "id": 69286857,
              "avatar_url": "https://avatars.githubusercontent.com/u/69286857?v=4",
              "html_url": "https://github.com/CRUNZEX",
              "name": "CRUNZEX",
              "company": null,
              "location": "Thailand",
              "bio": null,
              "public_repos": 28,
              "followers": 3
            }
        """.trimIndent()

        val profile = profileJson.decodeFromString(DeveloperProfile.serializer(), body)

        assertEquals("CRUNZEX", profile.displayName)
        assertEquals("@CRUNZEX", profile.handle)
        assertTrue(profile.avatarUrl.startsWith("https://avatars.githubusercontent.com/"))
    }

    @Test
    fun `a profile with no display name falls back to the handle`() {
        val profile = DeveloperProfile(login = "CRUNZEX", fullName = null)

        assertEquals("CRUNZEX", profile.displayName)
        assertFalse(profile.handle.isEmpty())
    }
}
