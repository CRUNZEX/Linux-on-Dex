package com.crunzex.linuxondex.vm

import kotlinx.serialization.json.Json
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.core.LxdError
import com.crunzex.linuxondex.engine.runtime.VmPaths
import java.io.File

/**
 * Persists [VmConfig]s as one JSON file per VM under filesDir/vm/configs.
 * Plain files instead of a database: configs are tiny, and file-per-VM makes
 * manual inspection/backup trivial.
 */
class VmRepository(private val paths: VmPaths) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val configsDir: File = paths.vmRootDir.resolve("configs")

    fun load(vmId: String): VmConfig? {
        val file = configFile(vmId)
        if (!file.exists()) return null
        return try {
            json.decodeFromString(VmConfig.serializer(), file.readText())
        } catch (error: Exception) {
            AppLog.error(SCOPE, "config ${file.name} is unreadable, ignoring", error)
            null
        }
    }

    fun save(config: VmConfig) {
        try {
            configsDir.mkdirs()
            val file = configFile(config.id)
            val temporary = File(configsDir, "${config.id}.json.part")
            temporary.writeText(json.encodeToString(VmConfig.serializer(), config))
            if (!temporary.renameTo(file)) {
                throw LxdError.StorageFailed("rename ${temporary.name} → ${file.name}")
            }
        } catch (error: LxdError) {
            throw error
        } catch (error: Exception) {
            throw LxdError.StorageFailed("saving config for '${config.id}'", error)
        }
    }

    /** The single VM the current UI manages; created on first use. */
    fun loadOrCreatePrimary(totalDeviceRamMb: Int, availableCpuCores: Int): VmConfig {
        load(PRIMARY_VM_ID)?.let { return it }
        val created = VmConfig.createDefault(totalDeviceRamMb, availableCpuCores)
        save(created)
        AppLog.info(SCOPE, "created default VM config: $created")
        return created
    }

    private fun configFile(vmId: String): File = configsDir.resolve("$vmId.json")

    companion object {
        private const val SCOPE = "VmRepository"
        const val PRIMARY_VM_ID = "primary"
    }
}
