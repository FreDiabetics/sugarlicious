package app.aapswear.mobile

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColorStore
import app.aapswear.mobile.ui.theme.derivedTargetValueArgb
import app.aapswear.model.AppearanceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SugarliciousColorStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `every configurable color role round trips exact ARGB in dark and light mode`() {
        val preferences = context.getSharedPreferences("color_picker_roundtrip", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()

        listOf("DARK", "LIGHT").forEachIndexed { modeIndex, mode ->
            preferences.edit().putString("themeMode", mode).commit()
            SugarliciousColorRole.entries.filter { it.configurable }.forEachIndexed { index, role ->
                val argb = Color.argb(
                    40 + (index * 13 + modeIndex * 7) % 216,
                    20 + (index * 31) % 220,
                    25 + (index * 47) % 215,
                    30 + (index * 61) % 210,
                )
                SugarliciousColorStore.save(preferences, role, argb)
                assertEquals("$mode ${role.name}", argb, SugarliciousColorStore.load(preferences).argb(role))
            }
        }
    }

    @Test
    fun `target value picker is exposed without inheriting legacy target band preference`() {
        val preferences = context.getSharedPreferences("target_value_legacy_isolation", Context.MODE_PRIVATE)
        val legacyTargetBand = Color.rgb(6, 48, 18)
        preferences.edit()
            .clear()
            .putString("themeMode", "DARK")
            .putInt("color.dark.target_band", legacyTargetBand)
            .commit()

        assertTrue(SugarliciousColorRole.TARGET_VALUE.configurable)
        assertEquals("target_value", SugarliciousColorRole.TARGET_VALUE.preferenceKey)
        assertEquals(
            derivedTargetValueArgb(SugarliciousColorRole.RANGE_IN_RANGE.defaultArgb),
            SugarliciousColorStore.load(preferences).argb(SugarliciousColorRole.TARGET_VALUE),
        )
    }

    @Test
    fun `default target follows an overridden in range hue and stays brighter`() {
        val preferences = context.getSharedPreferences("target_value_derived_hue", Context.MODE_PRIVATE)
        val inRange = Color.rgb(42, 92, 160)
        preferences.edit().clear().putString("themeMode", "DARK").commit()
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.RANGE_IN_RANGE, inRange)

        val target = SugarliciousColorStore.load(preferences).argb(SugarliciousColorRole.TARGET_VALUE)
        val inRangeHsv = FloatArray(3).also { Color.colorToHSV(inRange, it) }
        val targetHsv = FloatArray(3).also { Color.colorToHSV(target, it) }

        assertEquals(inRangeHsv[0], targetHsv[0], 0.01f)
        assertTrue(targetHsv[2] > inRangeHsv[2])
    }

    @Test
    fun `explicit user colors are isolated between light and dark themes`() {
        val preferences = context.getSharedPreferences("theme_specific_user_override", Context.MODE_PRIVATE)
        val chosen = Color.argb(144, 7, 91, 203)
        preferences.edit().clear().putString("themeMode", "DARK").commit()
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.RANGE_HIGH, chosen)

        assertEquals(chosen, SugarliciousColorStore.load(preferences).argb(SugarliciousColorRole.RANGE_HIGH))
        preferences.edit().putString("themeMode", "LIGHT").commit()
        assertEquals(
            SugarliciousColorRole.RANGE_HIGH.lightArgb,
            SugarliciousColorStore.load(preferences).argb(SugarliciousColorRole.RANGE_HIGH),
        )

        val lightChosen = Color.rgb(12, 34, 56)
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.RANGE_HIGH, lightChosen)
        assertEquals(lightChosen, SugarliciousColorStore.load(preferences).argb(SugarliciousColorRole.RANGE_HIGH))

        preferences.edit().putString("themeMode", "DARK").commit()
        assertEquals(chosen, SugarliciousColorStore.load(preferences).argb(SugarliciousColorRole.RANGE_HIGH))
    }

    @Test
    fun `light graph and in range dots remain independently configurable`() {
        val preferences = context.getSharedPreferences("light_graph_contrast", Context.MODE_PRIVATE)
        preferences.edit().clear()
            .putString("themeMode", "LIGHT")
            .putInt("color.light.graph_background", Color.rgb(32, 32, 32))
            .putInt("color.light.cgm_dot_in_range", Color.WHITE)
            .commit()

        val palette = SugarliciousColorStore.load(preferences)
        assertEquals(Color.rgb(32, 32, 32), palette.argb(SugarliciousColorRole.GRAPH_BACKGROUND))
        assertEquals(Color.WHITE, palette.argb(SugarliciousColorRole.CGM_DOT_IN_RANGE))
        assertEquals(SugarliciousColorRole.CGM_DOT_LOW.lightArgb, palette.argb(SugarliciousColorRole.CGM_DOT_LOW))
        assertEquals(SugarliciousColorRole.CGM_DOT_HIGH.lightArgb, palette.argb(SugarliciousColorRole.CGM_DOT_HIGH))
    }

    @Test
    fun `explicit mode API edits preview profile without mutating runtime profile`() {
        val preferences = context.getSharedPreferences("explicit_preview_profile", Context.MODE_PRIVATE)
        preferences.edit().clear().putString("themeMode", "DARK").commit()
        val light = Color.rgb(240, 230, 220)
        val dark = Color.rgb(12, 23, 34)
        SugarliciousColorStore.save(preferences, AppearanceMode.LIGHT, SugarliciousColorRole.BACKGROUND, light)
        SugarliciousColorStore.save(preferences, AppearanceMode.DARK, SugarliciousColorRole.BACKGROUND, dark)

        assertEquals(dark, SugarliciousColorStore.load(preferences).argb(SugarliciousColorRole.BACKGROUND))
        assertEquals(light, SugarliciousColorStore.load(preferences, AppearanceMode.LIGHT).argb(SugarliciousColorRole.BACKGROUND))
    }

    @Test
    fun `new semantic graph roles inherit legacy appearance until individually changed`() {
        val preferences = context.getSharedPreferences("semantic_graph_role_migration", Context.MODE_PRIVATE)
        val high = Color.rgb(181, 92, 7)
        val low = Color.rgb(182, 8, 63)
        val divider = Color.rgb(72, 81, 90)
        preferences.edit()
            .clear()
            .putString("themeMode", "DARK")
            .putInt("color.dark.range_high", high)
            .putInt("color.dark.range_low", low)
            .putInt("color.dark.graph_divider", divider)
            .commit()

        var palette = SugarliciousColorStore.load(preferences)
        assertEquals(high, palette.argb(SugarliciousColorRole.GRAPH_HIGH_LINE))
        assertEquals(low, palette.argb(SugarliciousColorRole.GRAPH_LOW_LINE))
        assertEquals(divider, palette.argb(SugarliciousColorRole.GRAPH_AXIS_TICK))
        assertEquals(divider, palette.argb(SugarliciousColorRole.GRAPH_NOW_LINE))

        val independentNowLine = Color.rgb(9, 201, 211)
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.GRAPH_NOW_LINE, independentNowLine)
        palette = SugarliciousColorStore.load(preferences)
        assertEquals(independentNowLine, palette.argb(SugarliciousColorRole.GRAPH_NOW_LINE))
        assertEquals(divider, palette.argb(SugarliciousColorRole.GRAPH_AXIS_TICK))
    }
}
