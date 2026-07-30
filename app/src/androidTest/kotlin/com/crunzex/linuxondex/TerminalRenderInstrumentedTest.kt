package com.crunzex.linuxondex

import android.graphics.Bitmap
import android.graphics.Canvas
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
import com.crunzex.linuxondex.terminal.AnsiTerminalParser
import com.crunzex.linuxondex.terminal.TerminalColors
import com.crunzex.linuxondex.terminal.TerminalSession
import com.crunzex.linuxondex.ui.TerminalActivity
import com.crunzex.linuxondex.ui.terminal.TerminalCanvasView

/**
 * On-device proof for the two claims the terminal makes:
 *  1. its surface draws through a hardware-accelerated (GPU) canvas, and
 *  2. parsed escape-sequence output really lands as coloured glyphs.
 */
@RunWith(AndroidJUnit4::class)
class TerminalRenderInstrumentedTest {

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
        val session = TerminalSession(container.vmController)

        // Red block characters via SGR, straight through the real parser.
        val parser = AnsiTerminalParser(session.screen)
        val payload = "[31m████████".toByteArray(Charsets.UTF_8)
        parser.feed(payload, 0, payload.size)

        var bitmap: Bitmap? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = TerminalCanvasView(context)
            view.attach(session)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 800, 400)
            bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap!!))
        }

        val pixels = IntArray(800 * 400)
        bitmap!!.getPixels(pixels, 0, 800, 0, 0, 800, 400)
        val redCount = pixels.count { it == TerminalColors.indexed(1) }
        assertTrue("expected red glyph pixels, found $redCount", redCount > 100)
        assertNotEquals(0, pixels.count { it == TerminalColors.DEFAULT_BACKGROUND })
    }

    private fun findTerminalView(root: View): TerminalCanvasView? {
        if (root is TerminalCanvasView) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findTerminalView(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
