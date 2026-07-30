package com.crunzex.linuxondex.display.vnc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Renders the VNC framebuffer scaled to fit and forwards touch/mouse/
 * keyboard input. Works both as a phone touchscreen (tap = left click,
 * long-press = right click, drag = move with button held) and as a DeX
 * desktop surface (hover moves the pointer, hardware keys type).
 */
@SuppressLint("ViewConstructor")
class VncView(context: Context) : View(context) {

    /** Input sink — the connected [RfbClient]. */
    var inputTarget: RfbClient? = null

    private var framebuffer: Bitmap? = null
    private val drawMatrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var currentButtonMask = 0
    private var lastPointerX = 0
    private var lastPointerY = 0
    private val longPressRunnable = Runnable { performRightClick() }
    private var longPressPending = false
    private var touchMoved = false
    private var downX = 0f
    private var downY = 0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    /** Hardware keys should reach the guest without a preliminary tap. */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { requestFocus() }
    }

    fun onFramebufferReady(bitmap: Bitmap) {
        framebuffer = bitmap
        post {
            updateDrawMatrix()
            invalidate()
        }
    }

    fun onFrameUpdated() {
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateDrawMatrix()
    }

    private fun updateDrawMatrix() {
        val bitmap = framebuffer ?: return
        if (width == 0 || height == 0) return
        val scale = minOf(
            width.toFloat() / bitmap.width,
            height.toFloat() / bitmap.height,
        )
        val offsetX = (width - bitmap.width * scale) / 2f
        val offsetY = (height - bitmap.height * scale) / 2f
        drawMatrix.setScale(scale, scale)
        drawMatrix.postTranslate(offsetX, offsetY)
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = framebuffer ?: return
        synchronized(bitmap) {
            canvas.drawBitmap(bitmap, drawMatrix, paint)
        }
    }

    // ---- Pointer input -----------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val client = inputTarget ?: return false
        val guestPoint = toGuestCoordinates(event.x, event.y) ?: return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                downX = event.x; downY = event.y
                touchMoved = false
                longPressPending = true
                postDelayed(longPressRunnable, LONG_PRESS_MS)
                movePointer(guestPoint)
            }
            MotionEvent.ACTION_MOVE -> {
                if (distanceFromDown(event) > TOUCH_SLOP_PX) {
                    cancelPendingRightClick(); touchMoved = true
                    // Drag = move with the left button held.
                    currentButtonMask = BUTTON_LEFT
                }
                movePointer(guestPoint)
            }
            MotionEvent.ACTION_UP -> {
                cancelPendingRightClick()
                if (!touchMoved) clickAt(guestPoint, BUTTON_LEFT)
                currentButtonMask = 0
                movePointer(guestPoint)
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelPendingRightClick()
                currentButtonMask = 0
                movePointer(guestPoint)
            }
        }
        return true
    }

    /** DeX / mouse: hover moves, real buttons click, wheel scrolls. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val client = inputTarget ?: return false
        val guestPoint = toGuestCoordinates(event.x, event.y) ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> movePointer(guestPoint)
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                currentButtonMask = androidButtonsToMask(event.buttonState)
                movePointer(guestPoint)
            }
            MotionEvent.ACTION_SCROLL -> {
                val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val wheelButton = if (vertical > 0) BUTTON_WHEEL_UP else BUTTON_WHEEL_DOWN
                client.sendPointerEvent(guestPoint.first, guestPoint.second, wheelButton)
                client.sendPointerEvent(guestPoint.first, guestPoint.second, 0)
            }
            else -> return super.onGenericMotionEvent(event)
        }
        return true
    }

    private fun performRightClick() {
        longPressPending = false
        inputTarget?.let {
            clickAt(lastPointerX to lastPointerY, BUTTON_RIGHT)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun clickAt(point: Pair<Int, Int>, button: Int) {
        val client = inputTarget ?: return
        client.sendPointerEvent(point.first, point.second, button)
        client.sendPointerEvent(point.first, point.second, 0)
    }

    private fun movePointer(point: Pair<Int, Int>) {
        lastPointerX = point.first
        lastPointerY = point.second
        inputTarget?.sendPointerEvent(point.first, point.second, currentButtonMask)
    }

    private fun toGuestCoordinates(viewX: Float, viewY: Float): Pair<Int, Int>? {
        val bitmap = framebuffer ?: return null
        val inverse = Matrix()
        if (!drawMatrix.invert(inverse)) return null
        val mapped = floatArrayOf(viewX, viewY)
        inverse.mapPoints(mapped)
        val guestX = mapped[0].toInt().coerceIn(0, bitmap.width - 1)
        val guestY = mapped[1].toInt().coerceIn(0, bitmap.height - 1)
        return guestX to guestY
    }

    private fun distanceFromDown(event: MotionEvent): Float {
        val dx = event.x - downX
        val dy = event.y - downY
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun cancelPendingRightClick() {
        if (longPressPending) {
            removeCallbacks(longPressRunnable)
            longPressPending = false
        }
    }

    private fun androidButtonsToMask(buttonState: Int): Int {
        var mask = 0
        if (buttonState and MotionEvent.BUTTON_PRIMARY != 0) mask = mask or BUTTON_LEFT
        if (buttonState and MotionEvent.BUTTON_TERTIARY != 0) mask = mask or BUTTON_MIDDLE
        if (buttonState and MotionEvent.BUTTON_SECONDARY != 0) mask = mask or BUTTON_RIGHT
        return mask
    }

    // ---- Keyboard input ----------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        forwardKey(event, isDown = true) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        forwardKey(event, isDown = false) || super.onKeyUp(keyCode, event)

    private fun forwardKey(event: KeyEvent, isDown: Boolean): Boolean {
        val client = inputTarget ?: return false
        val keysym = VncKeyMap.keysymFor(event) ?: return false
        client.sendKeyEvent(keysym, isDown)
        return true
    }

    /** TYPE_NULL keeps soft keyboards in raw key-event mode. */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        outAttrs.inputType = EditorInfo.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
        return null
    }

    override fun onCheckIsTextEditor(): Boolean = true

    companion object {
        private const val BUTTON_LEFT = 1
        private const val BUTTON_MIDDLE = 2
        private const val BUTTON_RIGHT = 4
        private const val BUTTON_WHEEL_UP = 8
        private const val BUTTON_WHEEL_DOWN = 16
        private const val LONG_PRESS_MS = 450L
        private const val TOUCH_SLOP_PX = 18f
    }
}
