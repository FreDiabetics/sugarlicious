package app.aapswear.mobile

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColorStore
import app.aapswear.mobile.ui.theme.derivedTargetValueArgb
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
    fun `explicit user color survives light and dark theme changes`() {
        val preferences = context.getSharedPreferences("theme_independent_user_override", Context.MODE_PRIVATE)
        val chosen = Color.argb(144, 7, 91, 203)
        preferences.edit().clear().putString("themeMode", "DARK").commit()
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.RANGE_HIGH, chosen)

        assertEquals(chosen, SugarliciousColorStore.load(preferences).argb(SugarliciousColorRole.RANGE_HIGH))
        preferences.edit().putString("themeMode", "LIGHT").commit()
        assertEquals(chosen, SugarliciousColorStore.load(preferences).argb(SugarliciousColorRole.RANGE_HIGH))

        SugarliciousColorStore.reset(preferences, SugarliciousColorRole.RANGE_HIGH)
        assertEquals(
            SugarliciousColorRole.RANGE_HIGH.lightArgb,
            SugarliciousColorStore.load(preferences).argb(SugarliciousColorRole.RANGE_HIGH),
        )
    }
}
