package app.aapswear.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WearGlucoseCardPresentationTest {
    private val now = 10_000_000L

    @Test
    fun `current card omits current label and keeps value trend unit delta and age`() {
        val result = wearGlucoseCardPresentation(
            WearGlucoseCardInput(123.0, GlucoseUnit.MG_DL, 4.0, Trend.FORTY_FIVE_UP, now - 2 * 60_000L, sourceLabel = "AndroidAPS"),
            CgmThresholds.DEFAULT,
            now,
        )
        assertEquals("123", result.value)
        assertEquals("+4 mg/dL · 2m", result.primaryMeta)
        assertEquals("", result.secondaryMeta)
        assertEquals(Trend.FORTY_FIVE_UP, result.trend)
        assertFalse(result.secondaryMeta.contains("Aktuell", ignoreCase = true))
    }

    @Test
    fun `stale and sensor error never present an old glucose as current`() {
        val stale = wearGlucoseCardPresentation(
            WearGlucoseCardInput(123.0, GlucoseUnit.MG_DL, 4.0, Trend.FLAT, now - 20 * 60_000L),
            CgmThresholds.DEFAULT,
            now,
        )
        val error = wearGlucoseCardPresentation(
            WearGlucoseCardInput(55.0, GlucoseUnit.MG_DL, null, Trend.SINGLE_DOWN, now, CgmQuality.SENSOR_ERROR),
            CgmThresholds.DEFAULT,
            now,
        )
        assertEquals("—", stale.value)
        assertEquals("—", error.value)
        assertNull(stale.trend)
        assertNull(error.trend)
        assertFalse(stale.displayable)
        assertFalse(error.displayable)
    }

    @Test
    fun `missing delta keeps the canonical compact secondary line`() {
        val result = wearGlucoseCardPresentation(
            WearGlucoseCardInput(123.0, GlucoseUnit.MG_DL, null, Trend.FLAT, now),
            CgmThresholds.DEFAULT,
            now,
        )

        assertEquals("— mg/dL · 0m", result.primaryMeta)
        assertEquals("", result.secondaryMeta)
    }

    @Test
    fun `range classification and mmol formatting use canonical thresholds`() {
        val result = wearGlucoseCardPresentation(
            WearGlucoseCardInput(252.0, GlucoseUnit.MMOL_L, -5.4, Trend.FLAT, now),
            CgmThresholds.DEFAULT,
            now,
        )
        assertEquals("14.0", result.value)
        assertEquals("-0.3 mmol/L · 0m", result.primaryMeta)
        assertEquals(CgmRangeClass.VERY_HIGH, result.rangeClass)
        assertTrue(result.displayable)
    }
}
