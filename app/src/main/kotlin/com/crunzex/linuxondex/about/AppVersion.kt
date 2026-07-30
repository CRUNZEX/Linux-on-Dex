package com.crunzex.linuxondex.about

/**
 * A three-part version number, compared the way releases are ordered.
 *
 * Kept separate from any network or Android type so the "is there a newer
 * build?" decision is a pure function the tests can pin exactly.
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int = compareValuesBy(
        this, other,
        AppVersion::major,
        AppVersion::minor,
        AppVersion::patch,
    )

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /**
         * Reads "1.0.0", "v1.0.0" or "1.2" into a version, or null when the
         * text is not a version at all.
         *
         * Release tags are written by hand, so this is deliberately
         * forgiving: a leading "v" is optional, missing parts count as zero,
         * and anything after a pre-release marker ("1.1.0-beta2") is ignored.
         * Text that carries no leading number is rejected rather than
         * guessed at — treating it as 0.0.0 would advertise a downgrade.
         */
        fun parseOrNull(text: String?): AppVersion? {
            val trimmed = text?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
            val numericPart = trimmed.takeWhile { it.isDigit() || it == '.' }
            val parts = numericPart.split('.').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            val numbers = parts.take(PART_COUNT).map { it.toIntOrNull() ?: return null }
            return AppVersion(
                major = numbers[0],
                minor = numbers.getOrElse(1) { 0 },
                patch = numbers.getOrElse(2) { 0 },
            )
        }

        private const val PART_COUNT = 3
    }
}

/** What the About screen shows next to the installed version. */
sealed interface UpdateStatus {
    /** The check is still running. */
    data object Checking : UpdateStatus

    /**
     * Nothing newer is published — *or* the check could not be completed.
     * The two are deliberately the same state: a user who cannot reach
     * GitHub is not helped by an error, and must never be nudged toward a
     * download the app failed to verify.
     */
    data object Latest : UpdateStatus

    /** A newer release exists; [releasePageUrl] is where to get it. */
    data class Available(
        val version: AppVersion,
        val releasePageUrl: String,
    ) : UpdateStatus
}

/**
 * Decides what to show, given the installed version and whatever the
 * release feed returned. Pure, so every branch is unit-tested.
 */
fun decideUpdateStatus(
    installedVersion: AppVersion?,
    latestReleaseTag: String?,
    releasePageUrl: String?,
): UpdateStatus {
    val installed = installedVersion ?: return UpdateStatus.Latest
    val latest = AppVersion.parseOrNull(latestReleaseTag) ?: return UpdateStatus.Latest
    if (latest <= installed) return UpdateStatus.Latest
    val url = releasePageUrl?.takeIf { it.startsWith("https://") } ?: return UpdateStatus.Latest
    return UpdateStatus.Available(latest, url)
}
