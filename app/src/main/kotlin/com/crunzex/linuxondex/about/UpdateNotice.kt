package com.crunzex.linuxondex.about

import android.content.Context
import com.crunzex.linuxondex.core.AppLog

/**
 * Remembers which published release the user has already been shown, so the
 * One UI "new" dot appears once per release rather than on every launch.
 *
 * The dot is a nudge, not an alert: it is cleared the moment the user opens
 * About, and only comes back when GitHub publishes something newer than what
 * they have already looked at.
 */
class UpdateNotice(context: Context) {

    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** The newest release the user has already seen, if any. */
    fun lastSeenVersion(): AppVersion? =
        AppVersion.parseOrNull(preferences.getString(KEY_LAST_SEEN_VERSION, null))

    /** Records that the user has looked at [version]; the dot goes away. */
    fun markSeen(version: AppVersion) {
        runCatching {
            preferences.edit().putString(KEY_LAST_SEEN_VERSION, version.toString()).apply()
        }.onFailure { AppLog.warn(SCOPE, "could not remember the seen version", it) }
    }

    companion object {
        private const val SCOPE = "UpdateNotice"
        private const val PREFERENCES_NAME = "update-notice"
        private const val KEY_LAST_SEEN_VERSION = "last-seen-version"

        /**
         * Whether the "new" dot should be showing.
         *
         * True only when a newer release genuinely exists *and* the user has
         * not already been shown that exact version. A failed or pending
         * check never marks anything, because an unreachable server is not
         * evidence of an update.
         */
        fun shouldShowUpdateDot(
            updateStatus: UpdateStatus,
            lastSeenVersion: AppVersion?,
        ): Boolean {
            val available = updateStatus as? UpdateStatus.Available ?: return false
            return lastSeenVersion == null || available.version > lastSeenVersion
        }
    }
}
