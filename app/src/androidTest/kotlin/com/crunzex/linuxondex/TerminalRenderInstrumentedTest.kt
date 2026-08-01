package com.crunzex.linuxondex

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.crunzex.linuxondex.terminal.TerminalSession
import com.crunzex.linuxondex.ui.TerminalActivity
import com.crunzex.linuxondex.ui.terminal.TermuxTerminalView
import com.termux.terminal.TerminalColors
import com.termux.view.TerminalView

/**
 * On-device proof for the two claims the terminal makes:
 *  1. its surface draws through a hardware-accelerated (GPU) canvas, and
 *  2. parsed escape-sequence output really lands as coloured glyphs.
 */
@RunWith(AndroidJUnit4::class)
class TerminalRenderInstrumentedTest {

    @Test
    fun terminalViewCanTakeImeFocusInTouchMode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var terminalView: TerminalView? = null

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val wrapper = TermuxTerminalView(context)
            terminalView = findTerminalView(wrapper)
        }

        assertNotNull("Termux TerminalView should be present", terminalView)
        assertTrue("terminal must be focusable", terminalView!!.isFocusable)
        assertTrue(
            "terminal must accept focus while the screen is in touch mode",
            terminalView!!.isFocusableInTouchMode,
        )
        assertTrue("terminal must advertise an IME input connection", terminalView!!.onCheckIsTextEditor())
    }

    @Test
    fun terminalWindowRendersOnTheGpu() {
        ActivityScenario.launch(TerminalActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val terminalView = findTerminalView(activity.window.decorView)
                assertNotNull("terminal view should be in the hierarchy", terminalView)
                assertTrue(
                    "terminal must draw on a hardware-accelerated canvas",
                    terminalView!!.isHardwareAccelerated,
                )
            }
        }
    }

    @Test
    fun colouredOutputRendersToPixels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as LinuxOnDexApp).container
        val session = TerminalSession(container.vmController, appContext = context)

        // Red block characters via SGR, straight through Termux's emulator.
        val payload = "[31m████████".toByteArray(Charsets.UTF_8)

        var bitmap: Bitmap? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = TermuxTerminalView(context)
            view.attach(session)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 800, 400)
            session.termuxSession.appendOutput(payload, 0, payload.size)
            bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap!!))
        }

        val pixels = IntArray(800 * 400)
        bitmap!!.getPixels(pixels, 0, 800, 0, 0, 800, 400)
        val redCount = pixels.count { it == TerminalColors.COLOR_SCHEME.mDefaultColors[1] }
        assertTrue("expected red glyph pixels, found $redCount", redCount > 100)
        assertNotEquals(0, pixels.count { it == Color.BLACK })
    }

    private fun findTerminalView(root: View): TerminalView? {
        if (root is TerminalView) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findTerminalView(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
