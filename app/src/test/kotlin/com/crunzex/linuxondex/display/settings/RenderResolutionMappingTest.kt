package com.crunzex.linuxondex.display.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderResolutionMappingTest {

    @Test
    fun `presets follow the viewer's inverted scale semantics`() {
        // The viewer computes X-screen size as surface * 100 / scale, so a
        // LARGER percentage renders FEWER pixels. Getting this backwards
        // would make "Smoothest" supersample at 4x the cost.
        assertEquals(
            mapOf(
                DisplayPreferenceKeys.RESOLUTION_MODE to "scaled",
                DisplayPreferenceKeys.SCALE_PERCENT to 200,
            ),
            RenderResolutionMapping.writesFor(
                RenderResolutionChoice.Preset(RenderQuality.SMOOTHEST)
            ),
        )
        assertEquals(
            mapOf(
                DisplayPreferenceKeys.RESOLUTION_MODE to "scaled",
                DisplayPreferenceKeys.SCALE_PERCENT to 150,
            ),
            RenderResolutionMapping.writesFor(
                RenderResolutionChoice.Preset(RenderQuality.BALANCED)
            ),
        )
    }

    @Test
    fun `sharpest returns to native mode and resets the scale`() {
        assertEquals(
            mapOf(
                DisplayPreferenceKeys.RESOLUTION_MODE to "native",
                DisplayPreferenceKeys.SCALE_PERCENT to 100,
            ),
            RenderResolutionMapping.writesFor(
                RenderResolutionChoice.Preset(RenderQuality.SHARPEST)
            ),
        )
    }

    @Test
    fun `exact resolution writes mode and geometry together`() {
        assertEquals(
            mapOf(
                DisplayPreferenceKeys.RESOLUTION_MODE to "exact",
                DisplayPreferenceKeys.RESOLUTION_EXACT to "1280x1024",
            ),
            RenderResolutionMapping.writesFor(
                RenderResolutionChoice.ExactResolution("1280x1024")
            ),
        )
    }

    @Test
    fun `reading back a written choice round-trips`() {
        listOf(
            RenderResolutionChoice.Preset(RenderQuality.SHARPEST),
            RenderResolutionChoice.Preset(RenderQuality.BALANCED),
            RenderResolutionChoice.Preset(RenderQuality.SMOOTHEST),
            RenderResolutionChoice.ExactResolution("1920x1080"),
        ).forEach { choice ->
            val writes = RenderResolutionMapping.writesFor(choice)
            val readBack = RenderResolutionMapping.choiceFrom(
                mode = writes[DisplayPreferenceKeys.RESOLUTION_MODE] as String,
                scalePercent = writes[DisplayPreferenceKeys.SCALE_PERCENT] as? Int ?: 100,
                exact = writes[DisplayPreferenceKeys.RESOLUTION_EXACT] as? String,
            )
            assertEquals(choice, readBack)
        }
    }

    @Test
    fun `unknown stored values fall back safely`() {
        // A pref file edited by an older or newer build must never crash the
        // settings screen: unknown mode reads as Sharpest, unknown scale as
        // Balanced.
        assertEquals(
            RenderResolutionChoice.Preset(RenderQuality.SHARPEST),
            RenderResolutionMapping.choiceFrom(mode = null, scalePercent = 100, exact = null),
        )
        assertEquals(
            RenderResolutionChoice.Preset(RenderQuality.SHARPEST),
            RenderResolutionMapping.choiceFrom("anything", 100, null),
        )
        assertEquals(
            RenderResolutionChoice.Preset(RenderQuality.BALANCED),
            RenderResolutionMapping.choiceFrom("scaled", 123, null),
        )
    }
}
