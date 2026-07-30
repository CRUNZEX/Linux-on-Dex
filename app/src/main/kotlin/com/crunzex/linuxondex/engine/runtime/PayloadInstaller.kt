package com.crunzex.linuxondex.engine.runtime

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.core.LxdError
import java.io.File
import java.security.MessageDigest

/**
 * Copies the read-only VM data payload (QEMU firmware, keymaps, PRoot rootfs
 * archive) from APK assets into the app's files directory, verifying each
 * file against the sha256 manifest generated at build time.
 *
 * Idempotent and cheap on subsequent launches: a stamp file records the
 * manifest digest of the last successful install.
 */
class PayloadInstaller(
    private val context: Context,
    private val paths: VmPaths,
) {

    @Serializable
    private data class ManifestFile(val sha256: String, val size: Long)

    @Serializable
    private data class PayloadManifest(
        val source: String = "",
        val packages: Map<String, String> = emptyMap(),
        val files: Map<String, ManifestFile> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Ensures the data payload is installed; safe to call on every start. */
    fun ensureInstalled() {
        val manifest = loadManifest()
        val manifestDigest = digestOfManifest(manifest)
        val stampFile = paths.vmRootDir.resolve(STAMP_FILE_NAME)
        if (stampFile.takeIf(File::exists)?.readText() == manifestDigest) {
            AppLog.debug(SCOPE, "payload already installed (stamp match)")
            return
        }

        paths.createRuntimeDirectories()
        val assetEntries = manifest.files.filterKeys { it.startsWith(ASSET_KEY_PREFIX) }
        if (assetEntries.isEmpty()) {
            throw LxdError.PayloadMissing("payload manifest lists no asset files")
        }

        var copied = 0
        val startedAt = System.currentTimeMillis()
        for ((manifestKey, expected) in assetEntries) {
            val assetPath = manifestKey.removePrefix("assets/")
            val destination = destinationFor(assetPath)
            if (destination.exists() && destination.length() == expected.size) {
                continue // size check is enough inside a stamped re-install pass
            }
            copyAssetVerified(assetPath, destination, expected)
            copied++
        }

        stampFile.writeText(manifestDigest)
        val elapsedMs = System.currentTimeMillis() - startedAt
        AppLog.info(SCOPE, "payload install complete: $copied files copied in ${elapsedMs}ms")
    }

    /** Maps "vm/qemu/keymaps/en-us" → filesDir/vm/qemu/keymaps/en-us. */
    private fun destinationFor(assetPath: String): File {
        val relativeToVm = assetPath.removePrefix("vm/")
        return paths.vmRootDir.resolve(relativeToVm)
    }

    private fun copyAssetVerified(assetPath: String, destination: File, expected: ManifestFile) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open(assetPath).use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualSha256 != expected.sha256) {
                throw LxdError.PayloadCorrupted(
                    "$assetPath (expected ${expected.sha256.take(12)}…, got ${actualSha256.take(12)}…)"
                )
            }
            if (!temporary.renameTo(destination)) {
                throw LxdError.StorageFailed("rename ${temporary.name} → ${destination.name}")
            }
        } catch (error: LxdError) {
            throw error
        } catch (error: Exception) {
            throw LxdError.PayloadCorrupted(assetPath, error)
        } finally {
            temporary.delete()
        }
    }

    private fun loadManifest(): PayloadManifest = try {
        val text = context.assets.open(MANIFEST_ASSET_PATH).bufferedReader().readText()
        json.decodeFromString(PayloadManifest.serializer(), text)
    } catch (error: LxdError) {
        throw error
    } catch (error: Exception) {
        throw LxdError.PayloadMissing("$MANIFEST_ASSET_PATH (${error.message})")
    }

    private fun digestOfManifest(manifest: PayloadManifest): String {
        val canonical = manifest.files.entries
            .sortedBy { it.key }
            .joinToString("\n") { "${it.key}:${it.value.sha256}" }
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val SCOPE = "PayloadInstaller"
        private const val MANIFEST_ASSET_PATH = "vm/payload-manifest.json"
        private const val ASSET_KEY_PREFIX = "assets/vm/"
        private const val STAMP_FILE_NAME = ".payload-stamp"
        private const val COPY_BUFFER_BYTES = 1 shl 17
    }
}
