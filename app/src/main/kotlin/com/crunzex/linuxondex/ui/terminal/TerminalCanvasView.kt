package com.crunzex.linuxondex.ui.terminal

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.OverScroller
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.terminal.TerminalCellFlags
import com.crunzex.linuxondex.terminal.TerminalColors
import com.crunzex.linuxondex.terminal.TerminalKeyEncoder
import com.crunzex.linuxondex.terminal.TerminalScreenBuffer
import com.crunzex.linuxondex.terminal.TerminalSession
import kotlin.math.roundToInt

/**
 * Draws a [TerminalSession]'s screen as a character grid and feeds keys
 * back to it — the app's equivalent of iTerm's terminal surface.
 *
 * Rendering happens on the view's hardware-accelerated canvas: glyph runs
 * become GPU draw calls, so a full-screen redraw costs well under a frame.
 * Touch scrolls through scrollback with fling momentum, pinch changes the
 * font size, and both hardware and soft keyboards deliver raw bytes.
 */
@SuppressLint("ViewConstructor")
class TerminalCanvasView(context: Context) : View(context) {

    interface Host {
        /** The view measured a new grid size after layout or font change. */
        fun onGridSizeChanged(columns: Int, rows: Int)

        /** Font size changed by pinch — lets the UI mirror the value. */
        fun onFontSizeChanged(sizeSp: Float)

        /** A latched Ctrl/Alt was used up; the shortcut bar should un-highlight. */
        fun onModifierLatchConsumed()
    }

    var host: Host? = null

    private var session: TerminalSession? = null
    private val screenChanged = { postInvalidateOnAnimation() }

    // One-shot modifiers latched from the shortcut bar (soft keyboards have
    // no Ctrl key of their own).
    private var ctrlLatched = false
    private var altLatched = false

    // ---- Text metrics ------------------------------------------------------

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }
    private val backgroundPaint = Paint()
    private val cursorPaint = Paint().apply { color = TerminalColors.CURSOR }
    private val selectionPaint = Paint().apply { color = SELECTION_COLOR }

    var fontSizeSp: Float = DEFAULT_FONT_SIZE_SP
        private set

    private var cellWidth = 1f
    private var cellHeight = 1
    private var cellBaseline = 0f

    /** Rows scrolled into history; 0 means live at the bottom. */
    private var scrollbackOffsetRows = 0
    private val scroller = OverScroller(context)
    private var lastScrollerY = 0

    /** Reused per-frame so drawing allocates nothing. */
    private var runBuffer = CharArray(0)

    // ---- Selection ---------------------------------------------------------
    //
    // The two ends of the current selection, in the buffer's scroll-stable
    // absolute coordinates so the highlight tracks the text as new output
    // scrolls the screen. Null means nothing is selected. The view owns this
    // transient UI state; the buffer only supplies the pure text extractor.

    /** One endpoint of a selection, in absolute (scroll-stable) coordinates. */
    private data class SelectionPoint(val absoluteRow: Int, val column: Int)

    private var selectionAnchor: SelectionPoint? = null
    private var selectionFocus: SelectionPoint? = null

    /** True once a press has moved to another cell — a drag, not a click. */
    private var selectionDragged = false

    /** A mouse primary-button drag is in progress (DeX / USB mouse). */
    private var mouseSelecting = false

    /** A touch long-press armed selection; suppresses scrollback scrolling. */
    private var touchSelecting = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        applyFontSize()
    }

    // ---- Wiring ------------------------------------------------------------

    fun attach(session: TerminalSession) {
        this.session?.removeScreenChangeListener(screenChanged)
        this.session = session
        session.addScreenChangeListener(screenChanged)
        reportGridSize()
        invalidate()
    }

    /** Keys must land in the shell the moment the screen opens — a DeX
     *  keyboard user should never have to tap the terminal first. */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { requestFocus() }
    }

    override fun onDetachedFromWindow() {
        session?.removeScreenChangeListener(screenChanged)
        super.onDetachedFromWindow()
    }

    fun setFontSize(sizeSp: Float) {
        fontSizeSp = sizeSp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
        applyFontSize()
        reportGridSize()
        host?.onFontSizeChanged(fontSizeSp)
        invalidate()
    }

    fun adjustFontSize(deltaSp: Float) = setFontSize(fontSizeSp + deltaSp)

    /** Shortcut bar: apply Ctrl/Alt to the next typed key. */
    fun setModifierLatch(ctrl: Boolean, alt: Boolean) {
        ctrlLatched = ctrl
        altLatched = alt
    }

    fun isCtrlLatched() = ctrlLatched
    fun isAltLatched() = altLatched

    private fun applyFontSize() {
        textPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics
        )
        val metrics = textPaint.fontMetrics
        cellWidth = textPaint.measureText("M")
        cellHeight = (metrics.descent - metrics.ascent).roundToInt().coerceAtLeast(1)
        cellBaseline = -metrics.ascent
    }

    // ---- Grid geometry -----------------------------------------------------

    private fun visibleColumns(): Int =
        ((width - 2 * PADDING_PX) / cellWidth).toInt().coerceAtLeast(2)

    private fun visibleRows(): Int =
        ((height - 2 * PADDING_PX) / cellHeight).toInt().coerceAtLeast(2)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        reportGridSize()
    }

    private fun reportGridSize() {
        if (width == 0 || height == 0) return
        val columns = visibleColumns()
        val rows = visibleRows()
        session?.resize(columns, rows)
        host?.onGridSizeChanged(columns, rows)
    }

    // ---- Drawing -----------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val screen = session?.screen ?: return
        canvas.drawColor(TerminalColors.DEFAULT_BACKGROUND)

        synchronized(screen) {
            val rows = minOf(visibleRows(), screen.rows)
            if (runBuffer.size < screen.columns) runBuffer = CharArray(screen.columns)
            // Backgrounds first, then the selection highlight, then glyphs, so
            // the highlight sits over coloured cell backgrounds yet behind the
            // text — the selected characters stay crisp and fully readable.
            for (viewRow in 0 until rows) {
                drawRowBackground(canvas, screen, viewRow)
            }
            drawSelectionHighlight(canvas, screen, rows)
            for (viewRow in 0 until rows) {
                drawRowGlyphs(canvas, screen, viewRow)
            }
            if (scrollbackOffsetRows == 0 && screen.cursorVisible) {
                drawCursor(canvas, screen)
            }
        }
    }

    /** Background rectangles for one row, batched over same-colour runs. */
    private fun drawRowBackground(canvas: Canvas, screen: TerminalScreenBuffer, viewRow: Int) {
        val row = screen.rowForViewport(viewRow, scrollbackOffsetRows)
        val top = PADDING_PX + viewRow * cellHeight.toFloat()
        val columns = minOf(row.columns, screen.columns)

        var column = 0
        while (column < columns) {
            val colour = cellBackground(row.background[column], row.flags[column].toInt())
            var runEnd = column + 1
            while (runEnd < columns &&
                cellBackground(row.background[runEnd], row.flags[runEnd].toInt()) == colour
            ) runEnd++
            if (colour != TerminalColors.DEFAULT_BACKGROUND) {
                backgroundPaint.color = colour
                canvas.drawRect(
                    PADDING_PX + column * cellWidth,
                    top,
                    PADDING_PX + runEnd * cellWidth,
                    top + cellHeight,
                    backgroundPaint,
                )
            }
            column = runEnd
        }
    }

    /** Glyph runs for one row, sharing colour and style, drawn in one call. */
    private fun drawRowGlyphs(canvas: Canvas, screen: TerminalScreenBuffer, viewRow: Int) {
        val row = screen.rowForViewport(viewRow, scrollbackOffsetRows)
        val top = PADDING_PX + viewRow * cellHeight.toFloat()
        val columns = minOf(row.columns, screen.columns)

        var column = 0
        while (column < columns) {
            val flags = row.flags[column].toInt()
            val colour = cellForeground(row.foreground[column], row.background[column], flags)
            var length = 0
            var runEnd = column
            while (runEnd < columns) {
                val runFlags = row.flags[runEnd].toInt()
                if (runFlags != flags ||
                    cellForeground(row.foreground[runEnd], row.background[runEnd], runFlags) != colour
                ) break
                runBuffer[length++] = row.chars[runEnd]
                runEnd++
            }
            if (flags and TerminalCellFlags.INVISIBLE == 0 && hasInk(runBuffer, length)) {
                styleTextPaint(colour, flags)
                canvas.drawText(
                    runBuffer, 0, length,
                    PADDING_PX + column * cellWidth,
                    top + cellBaseline,
                    textPaint,
                )
            }
            column = runEnd
        }
    }

    /**
     * The translucent selection band. For each visible row inside the
     * selection it fills the run of selected cells: the first row from its
     * start column, the last row up to its end column, and every row between
     * in full — the same spans [TerminalScreenBuffer.textInSelection] copies.
     */
    private fun drawSelectionHighlight(canvas: Canvas, screen: TerminalScreenBuffer, rows: Int) {
        if (!isSelectionVisible()) return
        val anchor = selectionAnchor ?: return
        val focus = selectionFocus ?: return
        // Order the endpoints top-left → bottom-right exactly as the extractor
        // does, so any drag direction highlights the cells it would copy.
        val forward = anchor.absoluteRow < focus.absoluteRow ||
            (anchor.absoluteRow == focus.absoluteRow && anchor.column <= focus.column)
        val top = if (forward) anchor else focus
        val bottom = if (forward) focus else anchor

        for (viewRow in 0 until rows) {
            val absoluteRow = screen.absoluteRowForViewport(viewRow, scrollbackOffsetRows)
            if (absoluteRow < top.absoluteRow || absoluteRow > bottom.absoluteRow) continue
            val left = if (absoluteRow == top.absoluteRow) top.column else 0
            val right = if (absoluteRow == bottom.absoluteRow) bottom.column else screen.columns - 1
            if (right < left) continue
            val rowTop = PADDING_PX + viewRow * cellHeight.toFloat()
            canvas.drawRect(
                PADDING_PX + left * cellWidth,
                rowTop,
                PADDING_PX + (right + 1) * cellWidth,
                rowTop + cellHeight,
                selectionPaint,
            )
        }
    }

    private fun hasInk(buffer: CharArray, length: Int): Boolean {
        for (index in 0 until length) if (buffer[index] != ' ') return true
        return false
    }

    private fun cellForeground(foreground: Int, background: Int, flags: Int): Int =
        if (flags and TerminalCellFlags.INVERSE != 0) background else foreground

    private fun cellBackground(background: Int, flags: Int): Int =
        if (flags and TerminalCellFlags.INVERSE != 0) TerminalColors.DEFAULT_FOREGROUND
        else background

    private fun styleTextPaint(colour: Int, flags: Int) {
        textPaint.color = colour
        textPaint.alpha = if (flags and TerminalCellFlags.FAINT != 0) FAINT_ALPHA else 255
        textPaint.isFakeBoldText = flags and TerminalCellFlags.BOLD != 0
        textPaint.textSkewX = if (flags and TerminalCellFlags.ITALIC != 0) ITALIC_SKEW else 0f
        textPaint.isUnderlineText = flags and TerminalCellFlags.UNDERLINE != 0
        textPaint.isStrikeThruText = flags and TerminalCellFlags.STRIKETHROUGH != 0
    }

    private fun drawCursor(canvas: Canvas, screen: TerminalScreenBuffer) {
        val left = PADDING_PX + screen.cursorColumn * cellWidth
        val top = PADDING_PX + screen.cursorRow * cellHeight.toFloat()
        cursorPaint.style = if (isFocused) Paint.Style.FILL else Paint.Style.STROKE
        cursorPaint.strokeWidth = CURSOR_OUTLINE_WIDTH
        canvas.drawRect(left, top, left + cellWidth, top + cellHeight, cursorPaint)

        if (isFocused) {
            // Repaint the covered glyph in the background colour for contrast.
            val row = screen.rowForViewport(screen.cursorRow, 0)
            if (screen.cursorColumn < row.columns) {
                val glyph = row.chars[screen.cursorColumn]
                if (glyph != ' ') {
                    styleTextPaint(
                        TerminalColors.DEFAULT_BACKGROUND,
                        row.flags[screen.cursorColumn].toInt(),
                    )
                    canvas.drawText(
                        charArrayOf(glyph), 0, 1, left, top + cellBaseline, textPaint,
                    )
                }
            }
        }
    }

    // ---- Touch: scrollback, tap-to-focus, pinch zoom, long-press select -----

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean {
                scroller.forceFinished(true)
                return true
            }

            override fun onSingleTapUp(event: MotionEvent): Boolean {
                // A fresh tap (not a drag) clears any selection, like iTerm.
                clearSelection()
                requestFocus()
                showSoftKeyboard()
                return true
            }

            /**
             * Double-tap selects the word under the finger — the touch twin of
             * iTerm's double-click. The first tap of the pair already ran
             * [onSingleTapUp] (focus, keyboard, cleared selection), so all
             * that is left here is the word itself.
             */
            override fun onDoubleTap(event: MotionEvent): Boolean =
                selectWordAt(event.x, event.y)

            /** Long-press arms selection; the drag that follows extends it. */
            override fun onLongPress(event: MotionEvent) {
                touchSelecting = true
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                beginSelection(selectionPointAt(event.x, event.y))
            }

            override fun onScroll(
                start: MotionEvent?,
                current: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                // Once a long-press armed selection, the drag extends it;
                // otherwise the content follows the finger to reveal history.
                if (touchSelecting) {
                    updateSelection(selectionPointAt(current.x, current.y))
                } else {
                    scrollByPixels(-distanceY)
                }
                return true
            }

            override fun onFling(
                start: MotionEvent?,
                current: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                // A selection drag must not fling the history underneath it.
                if (touchSelecting) return true
                // Flinging down (positive velocity) keeps travelling into
                // history, continuing the drag direction.
                lastScrollerY = scrollbackOffsetRows * cellHeight
                scroller.fling(
                    0, lastScrollerY,
                    0, velocityY.toInt(),
                    0, 0,
                    0, maxScrollbackOffsetPx(),
                )
                postInvalidateOnAnimation()
                return true
            }
        },
    )

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setFontSize(fontSizeSp * detector.scaleFactor)
                return true
            }
        },
    )

    /**
     * Double-click detection for a physical mouse. [handleMouseEvent] consumes
     * mouse events before [gestureDetector] can see them — deliberately, since
     * a mouse must not pop up the soft keyboard or fling the history — so the
     * mouse needs its own detector. Only `onDoubleTap` is implemented; every
     * other callback stays the base class's no-op, which keeps the click, drag
     * and paste behaviour in [handleMouseEvent] the single owner of the rest.
     */
    private val mouseDoubleClickDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(event: MotionEvent): Boolean =
                selectWordAt(event.x, event.y)
        },
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A physical mouse (DeX / USB) drives selection and paste directly and
        // must never be mistaken for a finger scroll or a pinch.
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE && handleMouseEvent(event)) {
            return true
        }

        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)

        // The gesture detector has no "gesture ended" callback, so the lift
        // that ends a long-press selection is caught here.
        if (touchSelecting &&
            (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL)
        ) {
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) clearSelection() else finishSelection()
            touchSelecting = false
        }
        return true
    }

    /**
     * Desktop-mouse selection and paste. A primary-button drag highlights
     * text, a primary-button double-click highlights the word under the
     * pointer, and a middle click pastes the clipboard. Highlighting never
     * copies — Ctrl+C does, see [handleCtrlC]. Returns true when the event was
     * consumed as a mouse gesture, false to fall back to the normal touch
     * handling.
     */
    private fun handleMouseEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                when {
                    event.buttonState and MotionEvent.BUTTON_TERTIARY != 0 -> pasteFromClipboard()
                    event.buttonState and MotionEvent.BUTTON_PRIMARY != 0 -> {
                        // Only the primary button is timed for double-clicks,
                        // so repeated middle-click pastes never select a word.
                        // A consumed press has already selected its word; any
                        // other press starts an ordinary character drag.
                        if (!mouseDoubleClickDetector.onTouchEvent(event)) {
                            beginSelection(selectionPointAt(event.x, event.y))
                        }
                        // Either way the press may still be dragged, and its
                        // release settles whatever ends up selected.
                        mouseSelecting = true
                    }
                    else -> return false // secondary/other: let default handling run
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> if (mouseSelecting) {
                updateSelection(selectionPointAt(event.x, event.y))
                return true
            }
            MotionEvent.ACTION_UP -> if (mouseSelecting) {
                // The detector needs the release too: it is what starts the
                // clock for the second click of a double-click.
                mouseDoubleClickDetector.onTouchEvent(event)
                finishSelection()
                mouseSelecting = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (mouseSelecting) {
                mouseDoubleClickDetector.onTouchEvent(event)
                clearSelection()
                mouseSelecting = false
                return true
            }
        }
        return false
    }

    /** A DeX mouse shows a text I-beam over the terminal, as on the desktop. */
    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon =
        PointerIcon.getSystemIcon(context, PointerIcon.TYPE_TEXT)

    /** Mouse wheel (DeX) scrolls history like a desktop terminal. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            // Wheel up = back in time, like every desktop terminal.
            scrollByPixels(delta * cellHeight * WHEEL_ROWS_PER_NOTCH)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    /** Positive [deltaPixels] scrolls deeper into history. */
    private fun scrollByPixels(deltaPixels: Float) {
        val screen = session?.screen ?: return
        val deltaRows = (deltaPixels / cellHeight).roundToInt()
        val maxOffset = synchronized(screen) { screen.scrollbackSize }
        scrollbackOffsetRows = (scrollbackOffsetRows + deltaRows).coerceIn(0, maxOffset)
        invalidate()
    }

    private fun maxScrollbackOffsetPx(): Int {
        val screen = session?.screen ?: return 0
        return synchronized(screen) { screen.scrollbackSize } * cellHeight
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val deltaPixels = scroller.currY - lastScrollerY
            lastScrollerY = scroller.currY
            scrollByPixels(deltaPixels.toFloat())
            postInvalidateOnAnimation()
        }
    }

    /** New input goes to the live screen, so typing snaps out of history. */
    private fun snapToLive() {
        if (scrollbackOffsetRows != 0) {
            scrollbackOffsetRows = 0
            scroller.forceFinished(true)
            invalidate()
        }
    }

    // ---- Selection and clipboard -------------------------------------------

    /** The absolute cell under a pixel, clamped to the visible grid. */
    private fun selectionPointAt(x: Float, y: Float): SelectionPoint? {
        val screen = session?.screen ?: return null
        val viewRow = ((y - PADDING_PX) / cellHeight).toInt().coerceIn(0, visibleRows() - 1)
        return synchronized(screen) {
            val column = ((x - PADDING_PX) / cellWidth).toInt().coerceIn(0, screen.columns - 1)
            SelectionPoint(screen.absoluteRowForViewport(viewRow, scrollbackOffsetRows), column)
        }
    }

    private fun beginSelection(point: SelectionPoint?) {
        if (point == null) return
        selectionAnchor = point
        selectionFocus = point
        selectionDragged = false
        invalidate()
    }

    private fun updateSelection(point: SelectionPoint?) {
        if (point == null || selectionAnchor == null || point == selectionFocus) return
        selectionFocus = point
        if (point != selectionAnchor) selectionDragged = true
        invalidate()
    }

    /**
     * iTerm's double-click: selects the whole word under the pointer, so a
     * path or a hostname is two clicks away instead of a careful drag. The
     * word is only highlighted — putting it on the clipboard stays an
     * explicit Ctrl+C (see [handleCtrlC]).
     *
     * Returns true when a word was selected. A blank cell has no word to
     * select, so the selection is cleared and false comes back — the caller
     * can then treat the press as an ordinary one.
     */
    private fun selectWordAt(x: Float, y: Float): Boolean {
        val screen = session?.screen ?: return false
        val point = selectionPointAt(x, y) ?: return false
        val word = screen.wordRangeAt(point.absoluteRow, point.column)
        if (word == null) {
            clearSelection()
            return false
        }
        selectionAnchor = SelectionPoint(point.absoluteRow, word.first)
        selectionFocus = SelectionPoint(point.absoluteRow, word.last)
        // No drag happened, but this is a genuine multi-cell selection: mark
        // it as dragged so it draws and Ctrl+C can copy it.
        selectionDragged = true
        invalidate()
        return true
    }

    /** Only a dragged (multi-cell) selection is shown and copyable; a bare
     *  press that never moved is a click, not a selection. */
    private fun isSelectionVisible(): Boolean =
        selectionAnchor != null && selectionFocus != null && (selectionDragged || touchSelecting)

    fun clearSelection() {
        if (selectionAnchor != null || selectionFocus != null) {
            selectionAnchor = null
            selectionFocus = null
            selectionDragged = false
            invalidate()
        }
    }

    /**
     * Ends a press. A real drag keeps its highlight on screen, a mere click
     * clears it. Nothing reaches the clipboard here: selecting and copying are
     * separate acts, as in iTerm and Windows Terminal, so a stray drag can
     * never overwrite what the user carefully copied a minute ago. The copy is
     * always the explicit Ctrl+C in [handleCtrlC].
     */
    private fun finishSelection() {
        if (!selectionDragged) clearSelection()
    }

    /** Copies the current selection to the clipboard, if there is one. */
    private fun copyCurrentSelection() {
        val screen = session?.screen ?: return
        val anchor = selectionAnchor ?: return
        val focus = selectionFocus ?: return
        val text = screen.textInSelection(
            anchor.absoluteRow, anchor.column, focus.absoluteRow, focus.column,
        )
        if (text.isNotEmpty()) copyToClipboard(text)
    }

    /**
     * Copies the selection and drops the highlight — the visible half of
     * "that got copied". Dropping it also re-arms the next Ctrl+C as an
     * interrupt, which is what a user expects after acting on a selection.
     */
    private fun copySelectionAndClearHighlight() {
        copyCurrentSelection()
        clearSelection()
    }

    /**
     * The clipboard is a system service reached over Binder, so a copy can
     * fail for reasons that have nothing to do with this app: an oversized
     * clip, a device policy that blocks the clipboard, a dying system UI. A
     * lost copy is a nuisance the user can retry; killing the terminal — and
     * with it the view of a running guest — would be far worse.
     */
    private fun copyToClipboard(text: String) {
        runCatching {
            clipboardManager().setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        }.onFailure { error -> AppLog.warn(SCOPE, "clipboard copy failed", error) }
    }

    /** Pastes clipboard text into the guest, honouring bracketed paste. */
    private fun pasteFromClipboard() {
        val target = session ?: return
        val text = clipboardText()
        if (text.isNullOrEmpty()) return
        clearSelection()
        snapToLive()
        target.paste(text)
    }

    /** Clipboard reads fail the same ways copies do — see [copyToClipboard]. */
    private fun clipboardText(): String? = runCatching {
        clipboardManager().primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
    }.onFailure { error -> AppLog.warn(SCOPE, "clipboard read failed", error) }.getOrNull()

    private fun clipboardManager(): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    // ---- Keyboard ----------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        sendKey(event) || super.onKeyDown(keyCode, event)

    /** Shortcut bar: send a special key (arrow, PgUp, …) by key code. */
    fun sendSpecialKey(keyCode: Int) {
        val target = session ?: return
        val bytes = TerminalKeyEncoder.encode(
            keyCode = keyCode,
            unicodeChar = 0,
            ctrl = false,
            alt = false,
            applicationCursorKeys = target.screen.applicationCursorKeys,
        ) ?: return
        clearSelection()
        snapToLive()
        target.sendBytes(bytes)
    }

    private fun sendKey(event: KeyEvent): Boolean {
        val target = session ?: return false
        // Modifier presses on their own are not input.
        if (KeyEvent.isModifierKey(event.keyCode)) return false
        // Copy/paste chords are resolved first: Ctrl+V must never reach the
        // guest as ^V, and Ctrl+C only reaches it as ^C (SIGINT) when there is
        // no selection to copy instead.
        if (handleClipboardShortcut(event)) return true

        val ctrl = event.isCtrlPressed || ctrlLatched
        val alt = event.isAltPressed || altLatched
        val baseChar = event.getUnicodeChar(
            event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
        )
        val bytes = TerminalKeyEncoder.encode(
            keyCode = event.keyCode,
            unicodeChar = baseChar,
            ctrl = ctrl,
            alt = alt,
            applicationCursorKeys = target.screen.applicationCursorKeys,
        ) ?: return false

        clearSelection()
        consumeModifierLatch()
        snapToLive()
        target.sendBytes(bytes)
        return true
    }

    /**
     * The outcome the Ctrl+C currently held down committed to, or null when no
     * Ctrl+C is down. See [handleCtrlC] for why a hold must not re-decide.
     */
    private var ctrlCOutcomeOfCurrentHold: CtrlCOutcome? = null

    /**
     * Handles the desktop copy/paste chords before they reach the encoder:
     * Ctrl+V and Ctrl+Shift+V paste the clipboard, and Ctrl+C / Ctrl+Shift+C
     * go to [handleCtrlC]. Returns true only when the chord was consumed here;
     * false lets [sendKey] encode the key and send it to the guest, which is
     * how Ctrl+C keeps reaching the shell as SIGINT.
     */
    private fun handleClipboardShortcut(event: KeyEvent): Boolean {
        if (!(event.isCtrlPressed || ctrlLatched)) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_V -> {
                if (event.repeatCount == 0) pasteFromClipboard()
                consumeModifierLatch()
                true
            }
            KeyEvent.KEYCODE_C -> handleCtrlC(event)
            else -> false
        }
    }

    /**
     * Ctrl+C is copy *or* interrupt, decided by whether anything is selected —
     * the rule iTerm and Windows Terminal use, and the reason selecting no
     * longer copies on its own.
     *
     * With a highlight on screen the user is plainly acting on that text, so
     * Ctrl+C copies it, drops the highlight and swallows the key: sending 0x03
     * as well would kill the very command whose output was being copied. With
     * nothing selected there is nothing to copy, so this returns false and the
     * key falls through to the encoder, which sends 0x03 exactly as before —
     * interrupting a runaway command must never become a two-step affair.
     * Ctrl+Shift+C is the unambiguous copy chord and is always consumed, even
     * with an empty selection: a user reaching for it never means "SIGINT".
     *
     * Auto-repeat is latched to the first press's outcome. Otherwise holding
     * Ctrl+C would copy, clear the selection, and then — seeing no selection —
     * start interrupting the command a moment later.
     */
    private fun handleCtrlC(event: KeyEvent): Boolean {
        if (event.repeatCount == 0) ctrlCOutcomeOfCurrentHold = null
        val outcome = CtrlCRule.outcomeWhileHeld(
            outcomeSoFar = ctrlCOutcomeOfCurrentHold,
            shiftPressed = event.isShiftPressed,
            hasVisibleSelection = isSelectionVisible(),
        )
        ctrlCOutcomeOfCurrentHold = outcome
        if (outcome == CtrlCOutcome.SEND_INTERRUPT) return false

        if (event.repeatCount == 0) copySelectionAndClearHighlight()
        consumeModifierLatch()
        return true
    }

    private fun consumeModifierLatch() {
        if (ctrlLatched || altLatched) {
            ctrlLatched = false
            altLatched = false
            host?.onModifierLatchConsumed()
        }
    }

    /**
     * TYPE_NULL asks soft keyboards for raw key events; the connection's
     * commitText path catches keyboards that insist on composing text.
     */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = EditorInfo.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                val target = session ?: return true
                clearSelection()
                snapToLive()
                if (ctrlLatched || altLatched) {
                    sendLatchedText(target, text.toString())
                } else {
                    target.sendText(text.toString())
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                clearSelection()
                repeat(beforeLength.coerceAtLeast(1)) {
                    session?.sendBytes(byteArrayOf(BACKSPACE_BYTE))
                }
                return true
            }
        }
    }

    /** Applies a latched Ctrl/Alt to text committed by the soft keyboard. */
    private fun sendLatchedText(target: TerminalSession, text: String) {
        val first = text.firstOrNull() ?: return
        val bytes = TerminalKeyEncoder.encode(
            keyCode = KeyEvent.KEYCODE_UNKNOWN,
            unicodeChar = first.code,
            ctrl = ctrlLatched,
            alt = altLatched,
            applicationCursorKeys = target.screen.applicationCursorKeys,
        )
        consumeModifierLatch()
        if (bytes != null) target.sendBytes(bytes)
        if (text.length > 1) target.sendText(text.substring(1))
    }

    override fun onCheckIsTextEditor(): Boolean = true

    private fun showSoftKeyboard() {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        manager.showSoftInput(this, 0)
    }

    companion object {
        const val DEFAULT_FONT_SIZE_SP = 14f
        const val MIN_FONT_SIZE_SP = 8f
        const val MAX_FONT_SIZE_SP = 32f
        private const val SCOPE = "TerminalCanvas"
        private const val CLIP_LABEL = "terminal"
        private const val PADDING_PX = 8f
        private const val FAINT_ALPHA = 160
        private const val ITALIC_SKEW = -0.25f
        private const val CURSOR_OUTLINE_WIDTH = 2f
        private const val WHEEL_ROWS_PER_NOTCH = 3
        private const val BACKSPACE_BYTE: Byte = 0x7F

        /** Semi-transparent blue selection band (ARGB, ~33% alpha). */
        private const val SELECTION_COLOR = 0x553B7BF0
    }
}

/** What one Ctrl+C press means. See [CtrlCRule]. */
internal enum class CtrlCOutcome {
    /** Put the highlighted text on the clipboard and swallow the key. */
    COPY_SELECTION,

    /** Let the key through so the guest receives 0x03 (SIGINT). */
    SEND_INTERRUPT,
}

/**
 * Decides whether a Ctrl+C press copies or interrupts.
 *
 * The rule is the one subtle thing about terminal copy/paste, so it lives
 * here as a pure function instead of buried in [TerminalCanvasView]'s key
 * handling: it is the part a reviewer will question and the part worth
 * testing exhaustively, and neither needs an Android framework in the way.
 *
 * The terminal cannot have a dedicated copy key — Ctrl+C is already SIGINT,
 * fifty years of muscle memory deep — so iTerm and Windows Terminal overload
 * it on the one signal that says what the user is doing: a visible selection.
 * Text highlighted on screen means "act on this text", nothing highlighted
 * means "act on the running command".
 */
internal object CtrlCRule {

    /**
     * The rule for a fresh press. Shift forces the copy branch because
     * Ctrl+Shift+C is the explicit, unambiguous copy chord — pressing it can
     * never be a request to interrupt, even when the selection is empty and
     * the copy ends up a no-op.
     */
    fun outcomeFor(shiftPressed: Boolean, hasVisibleSelection: Boolean): CtrlCOutcome =
        if (shiftPressed || hasVisibleSelection) CtrlCOutcome.COPY_SELECTION
        else CtrlCOutcome.SEND_INTERRUPT

    /**
     * The outcome for a press that may be a key-repeat of one already in
     * flight: [outcomeSoFar] is null for a fresh press and the committed
     * outcome while the key stays down.
     *
     * A hold keeps its first answer because copying clears the selection —
     * re-deciding mid-hold would see an empty selection and start sending
     * SIGINT to the command the user was only copying from. A hold that began
     * as an interrupt likewise stays an interrupt, so holding Ctrl+C still
     * repeats 0x03 the way a hardware terminal does.
     */
    fun outcomeWhileHeld(
        outcomeSoFar: CtrlCOutcome?,
        shiftPressed: Boolean,
        hasVisibleSelection: Boolean,
    ): CtrlCOutcome = outcomeSoFar ?: outcomeFor(shiftPressed, hasVisibleSelection)
}
