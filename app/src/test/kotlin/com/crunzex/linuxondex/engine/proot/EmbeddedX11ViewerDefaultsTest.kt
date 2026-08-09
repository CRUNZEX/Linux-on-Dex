package com.crunzex.linuxondex.engine.proot

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedX11ViewerDefaultsTest {

    @Test
    fun `first run writes the simulated-touch mode`() {
        val preferences = FakeSharedPreferences()

        EmbeddedX11ViewerDefaults.ensureApplied(preferences)

        assertEquals("2", preferences.values["touchMode"])
    }

    @Test
    fun `a user choice is never overwritten`() {
        val preferences = FakeSharedPreferences()
        preferences.values["touchMode"] = "1" // the user prefers trackpad

        EmbeddedX11ViewerDefaults.ensureApplied(preferences)

        assertEquals("1", preferences.values["touchMode"])
    }

    @Test
    fun `applying twice writes nothing the second time`() {
        val preferences = FakeSharedPreferences()
        EmbeddedX11ViewerDefaults.ensureApplied(preferences)
        preferences.editCount = 0

        EmbeddedX11ViewerDefaults.ensureApplied(preferences)

        assertEquals(0, preferences.editCount)
        assertTrue(preferences.values.containsKey("touchMode"))
    }

    /** Just enough of [SharedPreferences] for the merge rule. */
    private class FakeSharedPreferences : SharedPreferences {
        val values = mutableMapOf<String, String>()
        var editCount = 0

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor {
            editCount += 1
            return FakeEditor(this)
        }

        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String, defValue: String?): String? =
            values[key] ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int): Int = defValue
        override fun getLong(key: String, defValue: Long): Long = defValue
        override fun getFloat(key: String, defValue: Float): Float = defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    private class FakeEditor(
        private val owner: FakeSharedPreferences,
    ) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, String>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            if (value != null) pending[key] = value
            return this
        }

        override fun apply() {
            owner.values.putAll(pending)
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = this
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String): SharedPreferences.Editor = this
        override fun clear(): SharedPreferences.Editor = this
    }
}
