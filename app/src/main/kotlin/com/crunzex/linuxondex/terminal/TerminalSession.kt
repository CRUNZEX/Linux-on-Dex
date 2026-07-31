package com.crunzex.linuxondex.terminal

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
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.engine.SerialConsoleConnection
import com.crunzex.linuxondex.vm.VmController
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The one live terminal attached to the guest's serial console.
 *
 * There is exactly one of these per app process, owned by the container, so
 * the shell session — screen contents, scrollback, running programs —
 * survives navigation and is shared by every window that shows a terminal
 * (the in-app screen and a popped-out DeX window render this same object).
 *
 * Reconnects for as long as the VM runs: QEMU's socket chardev drops its
 * client when the app is backgrounded or DeX re-docks, and losing the
 * console does not mean the VM died.
 */
class TerminalSession(
    private val vmController: VmController,
    /**
     * Which guest console this session owns. 0 is the primary serial
     * console (and the only one that replays the boot log); 1 and 2 are the
     * extra virtio consoles, each with its own independent shell.
     */
    private val consoleIndex: Int = 0,
) {

    val screen = TerminalScreenBuffer(DEFAULT_COLUMNS, DEFAULT_ROWS)

    private val _statusLine = MutableStateFlow("Not connected")
    val statusLine: StateFlow<String> = _statusLine.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    /** Window title from OSC 0/2 — usually "user@host: dir". */
    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val parser = AnsiTerminalParser(
        screen = screen,
        respond = ::sendBytes,
        onTitleChanged = { title -> _title.value = title },
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val screenChangeListeners = CopyOnWriteArraySet<() -> Unit>()

    private var sessionJob: Job? = null

    @Volatile
    private var connection: SerialConsoleConnection? = null

    /** Boot output is replayed into the screen once, on the first attach. */
    private var bootLogReplayed = false

    /**
     * Whether a view has measured itself yet. The guest asks for our size
     * (cursor-position report) the moment we attach; answering before the
     * first layout would teach it the default 80×24 instead of the real
     * grid, so the first connect waits briefly for a measurement.
     */
    @Volatile
    private var viewSizeKnown = false

    // ---- Lifecycle ---------------------------------------------------------

    /** Starts (or resumes after VM restart) the connect/read loop. */
    @Synchronized
    fun start() {
        if (sessionJob?.isActive == true) return
        sessionJob = scope.launch { runSession() }
    }

    /**
     * A new VM boot is about to begin: wipe the screen and allow the next
     * attach to replay the (new) boot log from its beginning.
     */
    @Synchronized
    fun prepareForNewBoot() {
        screen.fullReset()
        bootLogReplayed = false
        _title.value = ""
        notifyScreenChanged()
    }

    /** Views register to learn "the screen changed, redraw". */
    fun addScreenChangeListener(listener: () -> Unit) {
        screenChangeListeners.add(listener)
    }

    fun removeScreenChangeListener(listener: () -> Unit) {
        screenChangeListeners.remove(listener)
    }

    // ---- Input from the user ----------------------------------------------

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

    /**
     * Pastes like a terminal, not like a keyboard: when the guest asked for
     * bracketed paste, the text is wrapped so multi-line pastes cannot run
     * line by line.
     */
    fun paste(text: String) {
        val bytes = normalizePastedLineEndings(text).toByteArray(Charsets.UTF_8)
        if (screen.bracketedPaste) {
            sendBytes(BRACKETED_PASTE_START + bytes + BRACKETED_PASTE_END)
        } else {
            sendBytes(bytes)
        }
    }

    /**
     * The view measured itself: [columns]×[rows] cells now fit.
     *
     * Nothing is sent to the guest. The guest learns the new size by asking
     * for it — our images query the terminal before every prompt, and the
     * parser answers that query invisibly — because typing a command into
     * whatever happens to be running is not safe: at a boot menu, in an
     * installer or in any full-screen program, those keystrokes are input,
     * and they corrupted the screen.
     */
    fun resize(columns: Int, rows: Int) {
        viewSizeKnown = true
        screen.resize(columns, rows)
        notifyScreenChanged()
    }

    /**
     * Tells the guest this terminal's size by typing a `stty` command at its
     * shell — the manual fallback behind the toolbar button.
     *
     * A serial console cannot deliver the resize signal a local terminal
     * would, and a guest that never asks for the size keeps drawing for the
     * old geometry. This works on *any* Linux guest, but it is deliberately
     * something the user asks for: the bytes are keystrokes, so they belong
     * at an idle shell prompt and nowhere else.
     *
     * The line is prefixed with kill-line so anything half-typed at the
     * prompt is cleared instead of corrupting the command.
     */
    fun syncGuestSizeToScreen() {
        sendBytes(buildSttySizeCommand(screen.rows, screen.columns))
    }

    // ---- Session loop ------------------------------------------------------

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

    /** Never blocks a headless start for long: size or 2 s, whichever first. */
    private suspend fun waitBrieflyForViewSize() {
        val deadline = VIEW_SIZE_WAIT_MILLIS / VIEW_SIZE_POLL_MILLIS
        var polls = 0L
        while (!viewSizeKnown && polls < deadline) {
            delay(VIEW_SIZE_POLL_MILLIS)
            polls++
        }
    }

    /** Only the primary console owns the boot log; extras start empty. */
    private fun replayBootLogOnce() {
        if (bootLogReplayed || consoleIndex != PRIMARY_CONSOLE_INDEX) return
        bootLogReplayed = true
        val history = vmController.readBootLog()
        if (history.isNotEmpty()) {
            val bytes = history.toByteArray(Charsets.UTF_8)
            parser.feed(bytes, 0, bytes.size)
            notifyScreenChanged()
        }
    }

    private suspend fun streamUntilClosed(serial: SerialConsoleConnection) {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        try {
            while (currentCoroutineContext().isActive) {
                val count = serial.read(buffer)
                if (count < 0) break
                if (count > 0) {
                    parser.feed(buffer, 0, count)
                    notifyScreenChanged()
                }
            }
        } catch (error: Exception) {
            AppLog.debug(SCOPE, "console stream ended: ${error.message}")
        }
    }

    private fun notifyScreenChanged() {
        for (listener in screenChangeListeners) listener()
    }

    private fun setStatus(status: String, connected: Boolean) {
        _statusLine.value = status
        _isConnected.value = connected
    }

    /** Allows [start] to relaunch after the loop ended (VM restarted). */
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

        /**
         * Rewrites the line breaks in pasted text the way a terminal sends
         * them: one carriage return per line, never a bare line feed.
         *
         * This is what the Enter key sends, and the guest's tty turns it
         * back into a newline. Sending a bare line feed instead moves the
         * cursor *down without returning to column one*, so a multi-line
         * paste marches diagonally across the screen and overwrites itself —
         * very visible on a narrow phone window, where more lines wrap.
         */
        fun normalizePastedLineEndings(text: String): String =
            text.replace("\r\n", "\r").replace('\n', '\r')

        /** Bash kill-line: clears any half-typed input before our command. */
        private const val KILL_LINE = 0x15.toByte()
        private const val CARRIAGE_RETURN = 0x0D.toByte()

        /** The exact bytes [syncGuestSizeToScreen] types, pure for tests. */
        fun buildSttySizeCommand(rows: Int, columns: Int): ByteArray {
            val command = "stty rows $rows cols $columns".toByteArray(Charsets.US_ASCII)
            return byteArrayOf(KILL_LINE) + command + CARRIAGE_RETURN
        }
        private const val READ_BUFFER_BYTES = 8_192
        private const val RECONNECT_BASE_DELAY_MILLIS = 700L
        private const val RECONNECT_MAX_DELAY_MILLIS = 5_000L
        private const val MAX_CONSECUTIVE_FAILURES = 10
        private const val VIEW_SIZE_WAIT_MILLIS = 2_000L
        private const val VIEW_SIZE_POLL_MILLIS = 50L

        private val BRACKETED_PASTE_START = "[200~".toByteArray(Charsets.US_ASCII)
        private val BRACKETED_PASTE_END = "[201~".toByteArray(Charsets.US_ASCII)
    }
}
