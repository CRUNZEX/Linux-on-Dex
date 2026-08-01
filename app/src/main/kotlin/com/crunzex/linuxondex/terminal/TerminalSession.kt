package com.crunzex.linuxondex.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.engine.SerialConsoleConnection
import com.crunzex.linuxondex.vm.VmController
import com.termux.terminal.ExternalTerminalTransport
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet
import com.termux.terminal.TerminalSession as TermuxSession

/**
 * One persistent Termux emulator attached to a guest serial console.
 *
 * The guest process remains owned by QEMU; [TermuxSession] handles terminal
 * escape sequences, scrollback, input encoding, and the view-facing model.
 * The session survives navigation and can be rendered by multiple windows.
 */
class TerminalSession(
    private val vmController: VmController,
    private val consoleIndex: Int = PRIMARY_CONSOLE_INDEX,
    private val appContext: Context? = null,
) {

    private val _statusLine = MutableStateFlow("Not connected")
    val statusLine: StateFlow<String> = _statusLine.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val screenChangeListeners = CopyOnWriteArraySet<() -> Unit>()

    private var sessionJob: Job? = null

    @Volatile
    private var connection: SerialConsoleConnection? = null

    @Volatile
    private var columns = DEFAULT_COLUMNS

    @Volatile
    private var rows = DEFAULT_ROWS

    @Volatile
    private var viewSizeKnown = false

    /** Boot output is replayed once, on the first attach to the primary console. */
    private var bootLogReplayed = false

    private val termuxClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TermuxSession) = notifyScreenChanged()

        override fun onTitleChanged(changedSession: TermuxSession) {
            _title.value = changedSession.title.orEmpty()
            notifyScreenChanged()
        }

        override fun onSessionFinished(finishedSession: TermuxSession) = Unit

        override fun onCopyTextToClipboard(session: TermuxSession, text: String?) {
            clipboard()?.setPrimaryClip(ClipData.newPlainText("Terminal", text.orEmpty()))
        }

        override fun onPasteTextFromClipboard(session: TermuxSession?) {
            val clip = clipboard()?.primaryClip ?: return
            if (clip.itemCount == 0) return
            val item = clip.getItemAt(0)
            paste(item.coerceToText(appContext).toString())
        }

        override fun onBell(session: TermuxSession) = Unit

        override fun onColorsChanged(session: TermuxSession) = notifyScreenChanged()

        override fun onTerminalCursorStateChange(state: Boolean) = notifyScreenChanged()

        override fun setTerminalShellPid(session: TermuxSession, pid: Int) = Unit

        override fun getTerminalCursorStyle(): Int? = null

        override fun logError(tag: String?, message: String?) =
            AppLog.error(tag ?: SCOPE, message.orEmpty())

        override fun logWarn(tag: String?, message: String?) =
            AppLog.warn(tag ?: SCOPE, message.orEmpty())

        override fun logInfo(tag: String?, message: String?) =
            AppLog.info(tag ?: SCOPE, message.orEmpty())

        override fun logDebug(tag: String?, message: String?) =
            AppLog.debug(tag ?: SCOPE, message.orEmpty())

        override fun logVerbose(tag: String?, message: String?) =
            AppLog.debug(tag ?: SCOPE, message.orEmpty())

        override fun logStackTraceWithMessage(tag: String?, message: String?, error: Exception?) =
            AppLog.error(tag ?: SCOPE, message.orEmpty(), error)

        override fun logStackTrace(tag: String?, error: Exception?) =
            AppLog.error(tag ?: SCOPE, error?.message.orEmpty(), error)
    }

    private val transport = object : ExternalTerminalTransport {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            sendBytes(data.copyOfRange(offset, offset + count))
        }

        override fun onSizeChanged(columns: Int, rows: Int) {
            this@TerminalSession.columns = columns
            this@TerminalSession.rows = rows
            viewSizeKnown = true
        }
    }

    /** The upstream session consumed directly by Termux TerminalView. */
    val termuxSession = TermuxSession(transport, TRANSCRIPT_ROWS, termuxClient)

    /** Starts (or resumes after VM restart) the serial connect/read loop. */
    @Synchronized
    fun start() {
        if (sessionJob?.isActive == true) return
        sessionJob = scope.launch { runSession() }
    }

    /** Clears the emulator so the next boot starts with an empty transcript. */
    @Synchronized
    fun prepareForNewBoot() {
        termuxSession.reset()
        bootLogReplayed = false
        _title.value = ""
    }

    fun addScreenChangeListener(listener: () -> Unit) {
        screenChangeListeners.add(listener)
    }

    fun removeScreenChangeListener(listener: () -> Unit) {
        screenChangeListeners.remove(listener)
    }

    fun sendBytes(data: ByteArray) {
        val serial = connection ?: return
        scope.launch {
            try {
                serial.write(data)
            } catch (error: Exception) {
                AppLog.warn(SCOPE, "console write failed", error)
                _statusLine.value = "Reconnecting…"
            }
        }
    }

    fun sendText(text: String) = sendBytes(text.toByteArray(Charsets.UTF_8))

    /** Uses Termux's bracketed-paste and control-character sanitization. */
    fun paste(text: String) {
        val emulator = termuxSession.emulator
        if (emulator == null) {
            sendText(normalizePastedLineEndings(text))
        } else {
            emulator.paste(text)
        }
    }

    /** Current visible screen, used by the toolbar's copy action. */
    fun screenText(): String {
        val emulator = termuxSession.emulator ?: return ""
        return emulator.screen.getSelectedText(0, 0, emulator.mColumns, emulator.mRows)
    }

    /** Manual serial-console fallback: type the measured grid into `stty`. */
    fun syncGuestSizeToScreen() {
        sendBytes(buildSttySizeCommand(rows, columns))
    }

    private suspend fun runSession() {
        waitBrieflyForViewSize()
        replayBootLogOnce()
        var consecutiveFailures = 0

        while (currentCoroutineContext().isActive) {
            if (!vmController.vmState.value.isRunning) {
                setStatus("Virtual machine is not running", connected = false)
                clearSessionJob()
                return
            }

            val serial = vmController.openConsole(consoleIndex)
            if (serial == null) {
                consecutiveFailures++
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    setStatus("Console unavailable", connected = false)
                    clearSessionJob()
                    return
                }
                setStatus("Reconnecting…", connected = false)
                delay(reconnectDelayMillis(consecutiveFailures))
                continue
            }

            consecutiveFailures = 0
            connection = serial
            setStatus("Connected", connected = true)
            streamUntilClosed(serial)
            runCatching { serial.close() }
            connection = null

            if (!currentCoroutineContext().isActive) return
            setStatus("Reconnecting…", connected = false)
            delay(RECONNECT_BASE_DELAY_MILLIS)
        }
    }

    private suspend fun waitBrieflyForViewSize() {
        val deadline = VIEW_SIZE_WAIT_MILLIS / VIEW_SIZE_POLL_MILLIS
        var polls = 0L
        while (!viewSizeKnown && polls < deadline) {
            delay(VIEW_SIZE_POLL_MILLIS)
            polls++
        }
    }

    private fun replayBootLogOnce() {
        if (bootLogReplayed || consoleIndex != PRIMARY_CONSOLE_INDEX) return
        bootLogReplayed = true
        val history = vmController.readBootLog()
        if (history.isNotEmpty()) {
            val bytes = history.toByteArray(Charsets.UTF_8)
            termuxSession.appendOutput(bytes, 0, bytes.size)
        }
    }

    private suspend fun streamUntilClosed(serial: SerialConsoleConnection) {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        try {
            while (currentCoroutineContext().isActive) {
                val count = serial.read(buffer)
                if (count < 0) break
                if (count > 0) termuxSession.appendOutput(buffer, 0, count)
            }
        } catch (error: Exception) {
            AppLog.debug(SCOPE, "console stream ended: ${error.message}")
        }
    }

    private fun clipboard(): ClipboardManager? =
        appContext?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private fun notifyScreenChanged() {
        for (listener in screenChangeListeners) listener()
    }

    private fun setStatus(status: String, connected: Boolean) {
        _statusLine.value = status
        _isConnected.value = connected
    }

    @Synchronized
    private fun clearSessionJob() {
        sessionJob = null
    }

    private fun reconnectDelayMillis(attempt: Int): Long =
        (RECONNECT_BASE_DELAY_MILLIS * attempt).coerceAtMost(RECONNECT_MAX_DELAY_MILLIS)

    companion object {
        private const val SCOPE = "TerminalSession"
        const val PRIMARY_CONSOLE_INDEX = 0
        const val DEFAULT_COLUMNS = 80
        const val DEFAULT_ROWS = 24

        fun normalizePastedLineEndings(text: String): String =
            text.replace("\r\n", "\r").replace('\n', '\r')

        private const val KILL_LINE = 0x15.toByte()
        private const val CARRIAGE_RETURN = 0x0D.toByte()

        fun buildSttySizeCommand(rows: Int, columns: Int): ByteArray {
            val command = "stty rows $rows cols $columns".toByteArray(Charsets.US_ASCII)
            return byteArrayOf(KILL_LINE) + command + CARRIAGE_RETURN
        }

        private const val TRANSCRIPT_ROWS = 5_000
        private const val READ_BUFFER_BYTES = 8_192
        private const val RECONNECT_BASE_DELAY_MILLIS = 700L
        private const val RECONNECT_MAX_DELAY_MILLIS = 5_000L
        private const val MAX_CONSECUTIVE_FAILURES = 10
        private const val VIEW_SIZE_WAIT_MILLIS = 2_000L
        private const val VIEW_SIZE_POLL_MILLIS = 50L
    }
}
