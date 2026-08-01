package com.crunzex.linuxondex.engine.proot

/**
 * Turns a desktop session log into the few lines that explain a failure.
 *
 * A blind tail of the log is close to useless here: the X server answers a
 * bad option by printing its entire help text, so the last kilobyte is a list
 * of unrelated parameters and the one line that matters — "Unrecognized
 * option" — has already scrolled past. This picks the lines that carry the
 * reason and falls back to the tail only when nothing stands out.
 */
internal object DesktopLogSummary {

    /**
     * Markers that identify a line as the reason something failed. X servers
     * prefix errors with `(EE)`; the session supervisor prefixes its own
     * messages with its name.
     */
    private val FAILURE_MARKERS = listOf(
        "(EE)",
        "Fatal server error",
        "Unrecognized option",
        "[dex-desktop]",
        "cannot open",
        "No such file",
        "Permission denied",
        "failed",
        "Failed",
        "error:",
        "Error:",
    )

    /**
     * At most [maxLines] lines explaining the failure, newest last.
     *
     * Returns an empty string for an empty log, which the caller reports as
     * "no log" rather than as an empty reason.
     */
    fun summarise(logText: String, maxLines: Int = DEFAULT_MAX_LINES): String {
        val lines = logText.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        if (lines.isEmpty()) return ""

        val failureLines = lines.filter(::looksLikeFailure)
        val chosen = if (failureLines.isEmpty()) lines else failureLines
        return chosen.takeLast(maxLines).joinToString(" | ")
    }

    private fun looksLikeFailure(line: String): Boolean =
        FAILURE_MARKERS.any { marker -> line.contains(marker) }

    private const val DEFAULT_MAX_LINES = 8
}
