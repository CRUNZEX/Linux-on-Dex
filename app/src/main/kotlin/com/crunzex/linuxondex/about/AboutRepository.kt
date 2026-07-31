package com.crunzex.linuxondex.about

import android.content.Context
import android.content.pm.PackageManager
import com.crunzex.linuxondex.core.AppLog
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The developer shown on the About screen, as GitHub describes them. */
@Serializable
data class DeveloperProfile(
    val login: String,
    @SerialName("name") val fullName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String = "",
) {
    /** The name to show: the profile's own, falling back to the handle. */
    val displayName: String get() = fullName?.takeIf { it.isNotBlank() } ?: login

    val handle: String get() = "@$login"
}

/** The published release the update check compares against. */
@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
)

/**
 * Everything the About screen needs: who wrote the app, what version is
 * installed, and whether a newer one is published.
 *
 * All three answers degrade instead of failing. The profile and avatar are
 * cached on disk after the first successful fetch, so the screen still shows
 * them offline; the update check reports [UpdateStatus.Latest] when GitHub
 * cannot be reached, because an unreachable server is not evidence that an
 * update exists.
 */
class AboutRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val cacheDirectory: File
        get() = File(context.filesDir, CACHE_FOLDER).apply { mkdirs() }

    /** The version of the running build, from the package manager. */
    fun installedVersion(): AppVersion? = AppVersion.parseOrNull(installedVersionName())

    /** The raw version string to display, e.g. "1.0.0". */
    fun installedVersionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: UNKNOWN_VERSION
    } catch (missing: PackageManager.NameNotFoundException) {
        AppLog.warn(SCOPE, "own package info unavailable", missing)
        UNKNOWN_VERSION
    }

    /**
     * The developer profile: fetched from GitHub, then cached. A failed
     * fetch falls back to the last good copy, and only returns null when
     * there has never been one.
     */
    suspend fun loadDeveloperProfile(): DeveloperProfile? {
        val cache = File(cacheDirectory, PROFILE_CACHE_FILE)
        val fetched = readText(PROFILE_URL)
        if (fetched != null) {
            val parsed = parseProfile(fetched)
            if (parsed != null) {
                runCatching { cache.writeText(fetched) }
                    .onFailure { AppLog.warn(SCOPE, "caching the profile failed", it) }
                return parsed
            }
        }
        return cache.takeIf { it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull() }
            ?.let(::parseProfile)
    }

    /**
     * The avatar's PNG/JPEG bytes, cached beside the profile. Returns null
     * when it has never been downloaded and cannot be now.
     */
    suspend fun loadAvatar(avatarUrl: String): ByteArray? {
        if (avatarUrl.isBlank()) return null
        val cache = File(cacheDirectory, AVATAR_CACHE_FILE)
        readBytes(avatarUrl, AVATAR_MAX_BYTES)?.let { bytes ->
            runCatching { cache.writeBytes(bytes) }
                .onFailure { AppLog.warn(SCOPE, "caching the avatar failed", it) }
            return bytes
        }
        return cache.takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }
    }

    /**
     * Whether a newer release is published. Never throws, and never reports
     * an update it could not confirm.
     */
    suspend fun checkForUpdate(): UpdateStatus {
        val body = readText(LATEST_RELEASE_URL) ?: return UpdateStatus.Latest
        val release = runCatching { json.decodeFromString<GithubRelease>(body) }
            .onFailure { AppLog.warn(SCOPE, "release feed was not readable", it) }
            .getOrNull()
            ?: return UpdateStatus.Latest
        // Drafts and pre-releases are not offered: they are not what a user
        // tapping "Update" expects to install.
        if (release.draft || release.prerelease) return UpdateStatus.Latest
        return decideUpdateStatus(installedVersion(), release.tagName, release.htmlUrl)
    }

    // ---- Networking --------------------------------------------------------

    private fun parseProfile(body: String): DeveloperProfile? =
        runCatching { json.decodeFromString<DeveloperProfile>(body) }
            .onFailure { AppLog.warn(SCOPE, "profile was not readable", it) }
            .getOrNull()

    private fun readText(url: String): String? =
        readBytes(url, RESPONSE_MAX_BYTES)?.toString(Charsets.UTF_8)

    /**
     * A plain GET with short timeouts and a size ceiling. The About screen
     * is optional decoration, so it must never hold a thread for long or
     * grow without bound on a hostile response.
     */
    private fun readBytes(url: String, maximumBytes: Int): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                // GitHub rejects requests without a user agent.
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                AppLog.debug(SCOPE, "GET $url answered ${connection.responseCode}")
                return null
            }
            connection.inputStream.use { stream ->
                stream.readNBytes(maximumBytes).takeIf { it.isNotEmpty() }
            }
        } catch (unreachable: Exception) {
            AppLog.debug(SCOPE, "GET $url failed: ${unreachable.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        private const val SCOPE = "About"

        /** The developer's GitHub handle; the profile and repo both hang off it. */
        const val DEVELOPER_LOGIN = "CRUNZEX"
        // The canonical repository name; GitHub is case-insensitive on
        // redirects, but the API and the links should use the real one.
        const val PROJECT_REPOSITORY = "Linux-on-Dex"

        const val PROFILE_PAGE_URL = "https://github.com/$DEVELOPER_LOGIN"
        const val REPOSITORY_PAGE_URL = "$PROFILE_PAGE_URL/$PROJECT_REPOSITORY"

        private const val PROFILE_URL = "https://api.github.com/users/$DEVELOPER_LOGIN"
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/$DEVELOPER_LOGIN/$PROJECT_REPOSITORY/releases/latest"

        private const val CACHE_FOLDER = "about"
        private const val PROFILE_CACHE_FILE = "developer-profile.json"
        private const val AVATAR_CACHE_FILE = "developer-avatar.img"

        private const val UNKNOWN_VERSION = "unknown"
        private const val USER_AGENT = "LinuxOnDeX-Android"
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val READ_TIMEOUT_MILLIS = 5_000
        private const val RESPONSE_MAX_BYTES = 128 * 1024
        private const val AVATAR_MAX_BYTES = 2 * 1024 * 1024
    }
}
