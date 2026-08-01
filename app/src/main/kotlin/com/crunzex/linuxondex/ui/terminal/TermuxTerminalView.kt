package com.crunzex.linuxondex.ui.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.crunzex.linuxondex.R
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.terminal.TerminalSession
import com.termux.terminal.KeyHandler
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.termux.terminal.TerminalSession as TermuxSession

/**
 * Hosts Termux's upstream [TerminalView] and adapts its callbacks to the
 * QEMU-backed [TerminalSession].
 */
@SuppressLint("ViewConstructor")
class TermuxTerminalView(context: Context) : FrameLayout(context) {

    interface Host {
        fun onGridSizeChanged(columns: Int, rows: Int)
        fun onFontSizeChanged(sizeSp: Float)
        fun onModifierLatchConsumed()
        fun onKeyboardVisibilityChanged(visible: Boolean)
        fun onSelectionModeChanged(active: Boolean)
    }

    var host: Host? = null

    private val terminalView = TerminalView(context, null)
    private var session: TerminalSession? = null
    private var listenerRegistered = false
    private val screenChanged = { terminalView.onScreenUpdated() }

    private var ctrlLatched = false
    private var altLatched = false
    private var shiftLatched = false
    private var modifierConsumptionPosted = false
    private var keyboardVisible = false
    private val keyboard by lazy {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }
    private val forceShowKeyboard = Runnable {
        if (!isKeyboardVisible()) {
            @Suppress("DEPRECATION")
            val accepted = keyboard.showSoftInput(terminalView, InputMethodManager.SHOW_FORCED)
            AppLog.debug(SCOPE, "forced keyboard request accepted=$accepted")
        }
    }

    var fontSizeSp: Float = DEFAULT_FONT_SIZE_SP
        private set

    init {
        setBackgroundColor(Color.BLACK)
        terminalView.setBackgroundColor(Color.BLACK)
        // Upstream Termux sets this in activity_termux.xml. Our view is
        // created programmatically, so omitting it makes requestFocus() fail
        // while the screen is in touch mode and the IME ignores show calls.
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        terminalView.defaultFocusHighlightEnabled = false
        terminalView.setTerminalViewClient(ViewClient())
        addView(
            terminalView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        applyFontSize()
        ResourcesCompat.getFont(context, R.font.jetbrains_mono_nerd_font_mono_regular)
            ?.let(terminalView::setTypeface)
            ?: AppLog.warn(SCOPE, "JetBrains Mono Nerd Font resource could not be loaded")
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (visible != keyboardVisible) {
                keyboardVisible = visible
                host?.onKeyboardVisibilityChanged(visible)
            }
            insets
        }
        isFocusable = false
    }

    fun attach(session: TerminalSession) {
        if (this.session === session) return
        unregisterScreenListener()
        this.session = session
        registerScreenListener()
        terminalView.attachSession(session.termuxSession)
        post { terminalView.requestFocus() }
    }

    fun setFontSize(sizeSp: Float) {
        val clamped = sizeSp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
        if (clamped == fontSizeSp) return
        fontSizeSp = clamped
        applyFontSize()
        host?.onFontSizeChanged(clamped)
    }

    fun adjustFontSize(deltaSp: Float) = setFontSize(fontSizeSp + deltaSp)

    fun setModifierLatch(ctrl: Boolean, alt: Boolean, shift: Boolean) {
        ctrlLatched = ctrl
        altLatched = alt
        shiftLatched = shift
    }

    fun sendSpecialKey(keyCode: Int) {
        if (terminalView.mEmulator == null) return
        var modifiers = 0
        if (ctrlLatched) modifiers = modifiers or KeyHandler.KEYMOD_CTRL
        if (altLatched) modifiers = modifiers or KeyHandler.KEYMOD_ALT
        if (shiftLatched) modifiers = modifiers or KeyHandler.KEYMOD_SHIFT
        terminalView.handleKeyCode(keyCode, modifiers)
        if (modifiers != 0) consumeModifierLatches()
    }

    /** Copies the active Termux selection, or the visible screen as fallback. */
    fun copySelectionOrScreen() {
        val selectedText = terminalView.selectedText?.takeIf(String::isNotEmpty)
        val text = selectedText ?: session?.screenText().orEmpty()
        if (text.isEmpty()) return

        terminalView.currentSession?.onCopyTextToClipboard(text)
        if (selectedText != null) terminalView.stopTextSelectionMode()
        terminalView.requestFocus()
    }

    /** Pastes through Termux so bracketed-paste and sanitization stay active. */
    fun pasteFromClipboard() {
        if (terminalView.isSelectingText) terminalView.stopTextSelectionMode()
        terminalView.requestFocus()
        terminalView.currentSession?.onPasteTextFromClipboard()
    }

    fun showKeyboard() {
        terminalView.removeCallbacks(forceShowKeyboard)
        if (!terminalView.isAttachedToWindow) {
            terminalView.post {
                if (terminalView.isAttachedToWindow) showKeyboard()
            }
            return
        }

        val focusGranted = terminalView.requestFocus()
        AppLog.debug(
            SCOPE,
            "keyboard show: focusGranted=$focusGranted hasFocus=${terminalView.hasFocus()}",
        )
        terminalView.post {
            // InsetsController is the supported path on current Android;
            // InputMethodManager is retained because Samsung keyboards and
            // freeform DeX windows do not always honour one path alone.
            terminalView.windowInsetsController?.show(WindowInsets.Type.ime())
            val accepted = keyboard.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
            AppLog.debug(SCOPE, "keyboard request accepted=$accepted")
            terminalView.postDelayed(forceShowKeyboard, FORCE_KEYBOARD_DELAY_MS)
        }
    }

    fun toggleKeyboard() {
        if (isKeyboardVisible()) {
            terminalView.removeCallbacks(forceShowKeyboard)
            terminalView.windowInsetsController?.hide(WindowInsets.Type.ime())
            keyboard.hideSoftInputFromWindow(terminalView.windowToken, 0)
        } else {
            showKeyboard()
        }
    }

    private fun isKeyboardVisible(): Boolean =
        ViewCompat.getRootWindowInsets(this)
            ?.isVisible(WindowInsetsCompat.Type.ime())
            ?: keyboardVisible

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerScreenListener()
        ViewCompat.requestApplyInsets(this)
        post { terminalView.requestFocus() }
    }

    override fun onDetachedFromWindow() {
        terminalView.removeCallbacks(forceShowKeyboard)
        unregisterScreenListener()
        super.onDetachedFromWindow()
    }

    private fun registerScreenListener() {
        if (listenerRegistered) return
        val attachedSession = session ?: return
        attachedSession.addScreenChangeListener(screenChanged)
        listenerRegistered = true
    }

    private fun unregisterScreenListener() {
        if (!listenerRegistered) return
        session?.removeScreenChangeListener(screenChanged)
        listenerRegistered = false
    }

    private fun applyFontSize() {
        val pixels = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            fontSizeSp,
            resources.displayMetrics,
        ).toInt().coerceAtLeast(1)
        terminalView.setTextSize(pixels)
    }

    private fun scheduleModifierConsumption() {
        if (modifierConsumptionPosted) return
        modifierConsumptionPosted = true
        post {
            modifierConsumptionPosted = false
            consumeModifierLatches()
        }
    }

    private fun consumeModifierLatches() {
        if (!ctrlLatched && !altLatched && !shiftLatched) return
        ctrlLatched = false
        altLatched = false
        shiftLatched = false
        host?.onModifierLatchConsumed()
    }

    private inner class ViewClient : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) {
                adjustFontSize(if (scale > 1f) FONT_STEP_SP else -FONT_STEP_SP)
                return 1f
            }
            return scale
        }

        override fun onSingleTapUp(event: MotionEvent?) = showKeyboard()

        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = terminalView.hasFocus()
        override fun copyModeChanged(copyMode: Boolean) {
            host?.onSelectionModeChanged(copyMode)
        }
        override fun onKeyDown(keyCode: Int, event: KeyEvent?, session: TermuxSession?) = false
        override fun onKeyUp(keyCode: Int, event: KeyEvent?) = false
        override fun onLongPress(event: MotionEvent?): Boolean {
            if (event == null || terminalView.mEmulator == null) return false
            terminalView.startTextSelectionMode(event)
            return true
        }

        override fun readControlKey(): Boolean = ctrlLatched.also {
            if (it) scheduleModifierConsumption()
        }

        override fun readAltKey(): Boolean = altLatched.also {
            if (it) scheduleModifierConsumption()
        }

        override fun readShiftKey(): Boolean = shiftLatched.also {
            if (it) scheduleModifierConsumption()
        }
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TermuxSession?) = false

        override fun onEmulatorSet() {
            terminalView.mEmulator?.let { emulator ->
                host?.onGridSizeChanged(emulator.mColumns, emulator.mRows)
            }
        }

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

        override fun logStackTraceWithMessage(
            tag: String?,
            message: String?,
            error: Exception?,
        ) = AppLog.error(tag ?: SCOPE, message.orEmpty(), error)

        override fun logStackTrace(tag: String?, error: Exception?) =
            AppLog.error(tag ?: SCOPE, error?.message.orEmpty(), error)
    }

    companion object {
        private const val SCOPE = "TermuxTerminalView"
        const val DEFAULT_FONT_SIZE_SP = 14f
        const val MIN_FONT_SIZE_SP = 8f
        const val MAX_FONT_SIZE_SP = 32f
        private const val FONT_STEP_SP = 2f
        private const val FORCE_KEYBOARD_DELAY_MS = 500L
    }
}
