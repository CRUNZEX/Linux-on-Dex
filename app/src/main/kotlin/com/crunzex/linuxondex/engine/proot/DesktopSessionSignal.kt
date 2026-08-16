package com.crunzex.linuxondex.engine.proot

/**
 * What a desktop supervisor log line means for the app, if anything.
 *
 * The supervisor (dex-desktop, baked into the image) restarts a crashed
 * GNOME inside the session, so the session process stays alive and the app
 * would otherwise never learn that the compositor bounced. Its log lines
 * are the only channel — parsed here, in one place, and unit-tested so a
 * wording change in the image cannot silently break the app's reactions.
 */
sealed interface DesktopSessionSignal {

    /** GNOME died and the supervisor is restarting it inside the session. */
    data class CompositorRestarting(val exitCode: Int?) : DesktopSessionSignal

    /** The supervisor gave up on the GPU renderer for this session. */
    data class GpuDesktopAbandoned(val reason: String) : DesktopSessionSignal

    companion object {
        private const val SUPERVISOR_TAG = "[dex-desktop]"
        private const val GPU_ABANDONED_MARKER = "GPU desktop abandoned:"
        private val COMPOSITOR_RESTART_PATTERN =
            Regex("""GNOME exited with code (-?\d+); restarting""")

        fun fromSupervisorLine(line: String): DesktopSessionSignal? {
            if (!line.contains(SUPERVISOR_TAG)) return null
            val abandonedIndex = line.indexOf(GPU_ABANDONED_MARKER)
            if (abandonedIndex >= 0) {
                val reason = line
                    .substring(abandonedIndex + GPU_ABANDONED_MARKER.length)
                    .substringBefore("; continuing")
                    .trim()
                return GpuDesktopAbandoned(reason)
            }
            COMPOSITOR_RESTART_PATTERN.find(line)?.let { match ->
                return CompositorRestarting(match.groupValues[1].toIntOrNull())
            }
            return null
        }
    }
}
