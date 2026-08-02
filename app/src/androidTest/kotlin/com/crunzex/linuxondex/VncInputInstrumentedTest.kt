package com.crunzex.linuxondex

import android.graphics.Bitmap
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.crunzex.linuxondex.display.vnc.VncInputSink
import com.crunzex.linuxondex.display.vnc.VncView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VncInputInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var view: VncView
    private lateinit var inputSink: RecordingInputSink

    @Before
    fun setUp() {
        inputSink = RecordingInputSink(framebufferWidth = 1_000, framebufferHeight = 500)
        instrumentation.runOnMainSync {
            view = VncView(instrumentation.targetContext)
            view.layout(0, 0, 1_000, 500)
            view.onFramebufferReady(
                Bitmap.createBitmap(1_000, 500, Bitmap.Config.RGB_565),
            )
            view.bindInputTarget(inputSink)
        }
    }

    @Test
    fun hardwareMouseMovesClicksAndReleasesWithoutPointerCapture() {
        dispatchGeneric(mouseEvent(MotionEvent.ACTION_HOVER_MOVE, 120f, 80f))
        dispatchGeneric(
            mouseEvent(
                action = MotionEvent.ACTION_BUTTON_PRESS,
                x = 120f,
                y = 80f,
                buttonState = MotionEvent.BUTTON_PRIMARY,
            ),
        )
        dispatchTouch(
            mouseEvent(
                action = MotionEvent.ACTION_MOVE,
                x = 420f,
                y = 230f,
                buttonState = MotionEvent.BUTTON_PRIMARY,
            ),
        )
        dispatchGeneric(
            mouseEvent(
                action = MotionEvent.ACTION_BUTTON_RELEASE,
                x = 420f,
                y = 230f,
            ),
        )

        assertEquals(
            listOf(
                PointerInput(120, 80, 0),
                PointerInput(120, 80, 1),
                PointerInput(420, 230, 1),
                PointerInput(420, 230, 0),
            ),
            inputSink.pointerInputs,
        )
        assertTrue("the Android cursor must remain free", !view.hasPointerCapture())
    }

    @Test
    fun dexPrimaryClickWorksWhenActionDownOmitsButtonState() {
        dispatchTouch(mouseEvent(MotionEvent.ACTION_DOWN, 275f, 140f))
        dispatchTouch(mouseEvent(MotionEvent.ACTION_UP, 275f, 140f))

        assertEquals(
            listOf(
                PointerInput(275, 140, 1),
                PointerInput(275, 140, 0),
            ),
            inputSink.pointerInputs,
        )
    }

    @Test
    fun touchscreenTapClicksExactlyWhereTheUserTouches() {
        dispatchTouch(touchEvent(MotionEvent.ACTION_DOWN, 640f, 300f))
        dispatchTouch(touchEvent(MotionEvent.ACTION_UP, 640f, 300f))

        assertEquals(
            listOf(
                PointerInput(640, 300, 0),
                PointerInput(640, 300, 1),
                PointerInput(640, 300, 0),
                PointerInput(640, 300, 0),
            ),
            inputSink.pointerInputs,
        )
    }

    @Test
    fun secondaryMiddleAndWheelInputsUseRfbButtonMasks() {
        dispatchGeneric(mouseEvent(MotionEvent.ACTION_BUTTON_PRESS, 50f, 60f, MotionEvent.BUTTON_SECONDARY))
        dispatchGeneric(mouseEvent(MotionEvent.ACTION_BUTTON_RELEASE, 50f, 60f))
        dispatchGeneric(mouseEvent(MotionEvent.ACTION_BUTTON_PRESS, 50f, 60f, MotionEvent.BUTTON_TERTIARY))
        dispatchGeneric(mouseEvent(MotionEvent.ACTION_BUTTON_RELEASE, 50f, 60f))
        dispatchGeneric(
            pointerEvent(
                action = MotionEvent.ACTION_SCROLL,
                x = 50f,
                y = 60f,
                buttonState = 0,
                source = InputDevice.SOURCE_MOUSE,
                verticalScroll = 1f,
                horizontalScroll = 1f,
            ),
        )

        assertEquals(
            listOf(
                PointerInput(50, 60, 4), PointerInput(50, 60, 0),
                PointerInput(50, 60, 2), PointerInput(50, 60, 0),
                PointerInput(50, 60, 8), PointerInput(50, 60, 0),
                PointerInput(50, 60, 64), PointerInput(50, 60, 0),
            ),
            inputSink.pointerInputs,
        )
    }

    @Test
    fun softKeyboardCommitsAsciiThaiEmojiAndDeletion() {
        instrumentation.runOnMainSync {
            val editorInfo = EditorInfo()
            val connection = view.onCreateInputConnection(editorInfo)
            assertNotNull(connection)
            assertTrue(connection!!.commitText("Aก🙂", 1))
            assertTrue(connection.deleteSurroundingText(1, 1))
        }

        assertEquals(
            listOf(
                KeyInput(0x41, true), KeyInput(0x41, false),
                KeyInput(0x01000E01, true), KeyInput(0x01000E01, false),
                KeyInput(0x0101F642, true), KeyInput(0x0101F642, false),
                KeyInput(0xFF08, true), KeyInput(0xFF08, false),
                KeyInput(0xFFFF, true), KeyInput(0xFFFF, false),
            ),
            inputSink.keyInputs,
        )
    }

    @Test
    fun hardwareKeyboardForwardsPressAndRelease() {
        instrumentation.runOnMainSync {
            assertTrue(view.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
            assertTrue(view.onKeyUp(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
        }

        assertEquals(
            listOf(KeyInput(0xFF0D, true), KeyInput(0xFF0D, false)),
            inputSink.keyInputs,
        )
    }

    private fun dispatchGeneric(event: MotionEvent) {
        try {
            instrumentation.runOnMainSync { assertTrue(view.onGenericMotionEvent(event)) }
        } finally {
            event.recycle()
        }
    }

    private fun dispatchTouch(event: MotionEvent) {
        try {
            instrumentation.runOnMainSync { assertTrue(view.onTouchEvent(event)) }
        } finally {
            event.recycle()
        }
    }

    private fun mouseEvent(
        action: Int,
        x: Float,
        y: Float,
        buttonState: Int = 0,
    ): MotionEvent = pointerEvent(
        action = action,
        x = x,
        y = y,
        buttonState = buttonState,
        source = InputDevice.SOURCE_MOUSE,
    )

    private fun touchEvent(action: Int, x: Float, y: Float): MotionEvent =
        pointerEvent(action, x, y, buttonState = 0, source = InputDevice.SOURCE_TOUCHSCREEN)

    private fun pointerEvent(
        action: Int,
        x: Float,
        y: Float,
        buttonState: Int,
        source: Int,
        verticalScroll: Float = 0f,
        horizontalScroll: Float = 0f,
    ): MotionEvent {
        val pointerProperties = MotionEvent.PointerProperties().apply { id = 0 }
        val pointerCoordinates = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
            setAxisValue(MotionEvent.AXIS_VSCROLL, verticalScroll)
            setAxisValue(MotionEvent.AXIS_HSCROLL, horizontalScroll)
        }
        return MotionEvent.obtain(
            0L,
            1L,
            action,
            1,
            arrayOf(pointerProperties),
            arrayOf(pointerCoordinates),
            0,
            buttonState,
            1f,
            1f,
            0,
            0,
            source,
            0,
        )
    }

    private data class PointerInput(val x: Int, val y: Int, val buttonMask: Int)
    private data class KeyInput(val keysym: Int, val isDown: Boolean)

    private class RecordingInputSink(
        override val framebufferWidth: Int,
        override val framebufferHeight: Int,
    ) : VncInputSink {
        val pointerInputs = mutableListOf<PointerInput>()
        val keyInputs = mutableListOf<KeyInput>()

        override fun sendPointerEvent(x: Int, y: Int, buttonMask: Int): Boolean {
            pointerInputs += PointerInput(x, y, buttonMask)
            return true
        }

        override fun sendKeyEvent(keysym: Int, isDown: Boolean): Boolean {
            keyInputs += KeyInput(keysym, isDown)
            return true
        }
    }
}
