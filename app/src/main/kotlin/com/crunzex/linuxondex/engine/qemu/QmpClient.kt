package com.crunzex.linuxondex.engine.qemu

import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.core.LxdError
import java.io.BufferedReader
import java.io.File
import java.io.FileDescriptor
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Minimal client for the QEMU Machine Protocol over a unix socket:
 * newline-delimited JSON with a capabilities handshake. Used to ask the
 * guest to power down cleanly and to query VM status.
 *
 * Synchronous by design — callers run it on a IO dispatcher and each engine
 * owns at most one control connection.
 */
class QmpClient private constructor(
    private val socket: LocalSocket,
    private val reader: BufferedReader,
    private val writer: OutputStreamWriter,
) : AutoCloseable {

    /** Fire-and-forget events QEMU pushed since the last command. */
    val pendingEvents = mutableListOf<String>()

    fun requestGuestPowerdown() {
        execute("system_powerdown")
    }

    fun requestImmediateQuit() {
        execute("quit")
    }

    /** e.g. "running", "shutdown", "paused". */
    fun queryStatus(): String {
        val reply = execute("query-status")
        return reply["return"]?.jsonObject?.get("status")?.toString()?.trim('"') ?: "unknown"
    }

    /**
     * Writes the current guest screen to [outputFile] as a PPM image.
     *
     * This is the only way to observe a guest whose kernel logs to the
     * graphical console (`console=tty0`, as Ubuntu's installer does) without
     * attaching a VNC client.
     */
    fun captureScreenshot(outputFile: File) {
        execute("screendump") {
            put("filename", outputFile.absolutePath)
        }
    }

    /**
     * Presses a key in the guest, named with QEMU's `qcode` vocabulary
     * ("ret", "esc", "ctrl", "delete"…). Used to answer boot menus and to
     * offer shortcuts the soft keyboard cannot produce.
     */
    fun sendKey(vararg qemuKeyCodes: String) {
        execute("send-key") {
            putJsonArray("keys") {
                qemuKeyCodes.forEach { code ->
                    addJsonObject {
                        put("type", "qcode")
                        put("data", code)
                    }
                }
            }
        }
    }

    /**
     * Adds a user-network port forward while the VM runs, using the same
     * slirp specification the command line takes (`tcp:127.0.0.1:8080-:80`).
     * Goes through QEMU's human-monitor bridge: slirp forwards have no
     * native QMP command, but `hostfwd_add` has been stable for years.
     */
    fun addPortForward(slirpSpecification: String) {
        requireEmptyHumanMonitorResult("hostfwd_add net0 $slirpSpecification")
    }

    /** Removes a live forward; identified by protocol and host binding. */
    fun removePortForward(protocol: String, hostPort: Int) {
        requireEmptyHumanMonitorResult(
            "hostfwd_remove net0 $protocol:127.0.0.1:$hostPort"
        )
    }

    /**
     * Gives QEMU one of this process's open file descriptors and returns the
     * id of the fd set it was filed under.
     *
     * The descriptor itself travels as ancillary data on this very socket —
     * the kernel duplicates it into QEMU, which is the only way to hand a
     * device to a process that could never open it itself. Android exposes
     * that as [LocalSocket.setFileDescriptorsForSend], which attaches the
     * descriptors to the *next* write, so the command below carries it.
     *
     * The fd set outlives this connection, so the caller may disconnect
     * once the device that uses it has been added.
     */
    fun addFileDescriptorToNewSet(fileDescriptor: FileDescriptor): Int {
        socket.setFileDescriptorsForSend(arrayOf(fileDescriptor))
        val reply = try {
            execute("add-fd")
        } finally {
            // Never leave it armed: the next unrelated command would send
            // the descriptor a second time.
            socket.setFileDescriptorsForSend(null)
        }
        return reply["return"]?.jsonObject?.get("fdset-id")?.jsonPrimitive?.int
            ?: throw LxdError.ControlChannelFailed("add-fd returned no fdset id")
    }

    /**
     * Plugs a host USB device into the running guest, reading it from the
     * descriptor already filed under [fdSetId].
     *
     * `hostdevice` is deliberate: telling QEMU to go and *find* the device by
     * vendor and product id makes it enumerate `/dev/bus/usb`, which an
     * Android app is not allowed to read, so it finds nothing and attaches
     * nothing. Naming the descriptor skips the search entirely.
     */
    fun attachUsbHostDevice(deviceId: String, fdSetId: Int) {
        execute("device_add") {
            put("driver", "usb-host")
            put("id", deviceId)
            put("hostdevice", "/dev/fdset/$fdSetId")
        }
    }

    /** Unplugs a device added by [attachUsbHostDevice]. */
    fun detachDevice(deviceId: String) {
        execute("device_del") {
            put("id", deviceId)
        }
    }

    /** Releases an fd set once nothing is using it any more. */
    fun removeFileDescriptorSet(fdSetId: Int) {
        execute("remove-fd") {
            put("fdset-id", fdSetId)
        }
    }

    /** Human-readable guest USB bus contents, used by hardware integration tests. */
    fun queryUsbDevices(): String = executeHumanMonitorCommand("info usb")

    /**
     * HMP passthrough. Unlike QMP proper, HMP reports failure as TEXT in
     * the return value, so anything non-blank is treated as an error.
     */
    private fun requireEmptyHumanMonitorResult(commandLine: String) {
        val output = executeHumanMonitorCommand(commandLine)
        if (output.isNotBlank()) {
            throw LxdError.ControlChannelFailed("'$commandLine' failed: $output")
        }
    }

    /**
     * Returns HMP text verbatim. Mutation commands normally return an empty
     * string, while queries such as `info usb` intentionally return content.
     */
    private fun executeHumanMonitorCommand(commandLine: String): String {
        val reply = execute("human-monitor-command") {
            put("command-line", commandLine)
        }
        return reply["return"]?.jsonPrimitive?.content.orEmpty().trim()
    }

    private fun execute(
        command: String,
        argumentsBuilder: (JsonObjectBuilder.() -> Unit)? = null,
    ): JsonObject {
        val request = buildJsonObject {
            put("execute", command)
            if (argumentsBuilder != null) {
                put("arguments", buildJsonObject(argumentsBuilder))
            }
        }
        writer.write(request.toString())
        writer.write("\n")
        writer.flush()
        while (true) {
            val line = reader.readLine()
                ?: throw LxdError.ControlChannelFailed("connection closed during '$command'")
            val message = parseJsonObject(line) ?: continue
            when {
                message.containsKey("return") -> return message
                message.containsKey("error") -> throw LxdError.ControlChannelFailed(
                    "'$command' rejected: ${message["error"]}"
                )
                message.containsKey("event") -> pendingEvents.add(line)
                // Anything else (greetings, unknown) is ignored.
            }
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun parseJsonObject(line: String): JsonObject? = runCatching {
        json.parseToJsonElement(line).jsonObject
    }.getOrNull()

    companion object {
        private const val SCOPE = "QmpClient"
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Connects and completes the QMP handshake. QEMU sends a greeting,
         * then expects `qmp_capabilities` before accepting commands.
         */
        fun connect(socketFile: File): QmpClient {
            val socket = LocalSocket()
            try {
                socket.connect(
                    LocalSocketAddress(
                        socketFile.absolutePath,
                        LocalSocketAddress.Namespace.FILESYSTEM,
                    )
                )
                socket.soTimeout = SOCKET_TIMEOUT_MILLIS
                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                val writer = OutputStreamWriter(socket.outputStream)
                val greeting = reader.readLine()
                    ?: throw LxdError.ControlChannelFailed("no QMP greeting")
                AppLog.debug(SCOPE, "greeting: ${greeting.take(120)}")

                val client = QmpClient(socket, reader, writer)
                client.execute("qmp_capabilities")
                return client
            } catch (error: LxdError) {
                runCatching { socket.close() }
                throw error
            } catch (error: Exception) {
                runCatching { socket.close() }
                throw LxdError.ControlChannelFailed("connect to ${socketFile.name}", error)
            }
        }

        private const val SOCKET_TIMEOUT_MILLIS = 5_000
    }
}
