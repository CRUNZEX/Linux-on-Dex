package com.crunzex.linuxondex.engine.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopLogSummaryTest {

    @Test
    fun `the reason survives an X server help dump`() {
        // This is the real shape of the failure: one bad option, then the
        // server's entire parameter list, which a plain tail would show
        // instead of the cause.
        val log = buildString {
            appendLine("[dex-desktop] starting Xvnc :1 at 1280x800 on port 5901")
            appendLine("(EE) Unrecognized option: -DeferUpdate")
            appendLine("Fatal server error:")
            repeat(200) { index -> appendLine("  SomeParameter$index - explanation (default=0)") }
        }

        val summary = DesktopLogSummary.summarise(log)

        assertTrue("expected the cause, got: $summary", summary.contains("Unrecognized option"))
        assertTrue(summary.contains("Fatal server error"))
        assertTrue("help text must not be included", !summary.contains("SomeParameter199"))
    }

    @Test
    fun `supervisor messages count as reasons`() {
        val log = "[dex-desktop] Xvnc exited early\nsome unrelated chatter\n"

        assertTrue(DesktopLogSummary.summarise(log).contains("Xvnc exited early"))
    }

    @Test
    fun `a log with no obvious failure falls back to its last lines`() {
        val log = (1..40).joinToString("\n") { "line $it" }

        val summary = DesktopLogSummary.summarise(log, maxLines = 3)

        assertEquals("line 38 | line 39 | line 40", summary)
    }

    @Test
    fun `an empty log summarises to nothing so the caller can say so`() {
        assertEquals("", DesktopLogSummary.summarise(""))
        assertEquals("", DesktopLogSummary.summarise("\n   \n\t\n"))
    }

    @Test
    fun `only the last failures are kept when there are many`() {
        val log = (1..30).joinToString("\n") { "(EE) failure number $it" }

        val summary = DesktopLogSummary.summarise(log, maxLines = 2)

        assertEquals("(EE) failure number 29 | (EE) failure number 30", summary)
    }
}
