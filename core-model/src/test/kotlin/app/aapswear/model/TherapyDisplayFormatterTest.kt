package app.aapswear.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TherapyDisplayFormatterTest {
    @Test
    fun `formats mgdl and mmol without locale-dependent separators`() {
        assertEquals("123", TherapyDisplayFormatter.glucose(glucose(123.4, GlucoseUnit.MG_DL)))
        assertEquals("6.9", TherapyDisplayFormatter.glucose(glucose(124.2, GlucoseUnit.MMOL_L)))
        assertEquals("+5", TherapyDisplayFormatter.signedDelta(5.0, GlucoseUnit.MG_DL))
        assertEquals("-0.3", TherapyDisplayFormatter.signedDelta(-5.4, GlucoseUnit.MMOL_L))
    }

    @Test
    fun `maps all trends and suppresses unknown trend`() {
        val expected = listOf("⇊", "↓", "↘", "→", "↗", "↑", "⇈", "")
        assertEquals(expected, Trend.entries.map(TherapyDisplayFormatter::trendArrow))
    }

    @Test
    fun `formats missing values and future timestamps safely`() {
        assertEquals("—", TherapyDisplayFormatter.units(null, "U", 2))
        assertEquals("—", TherapyDisplayFormatter.percent(null))
        assertEquals("0m", TherapyDisplayFormatter.ageMinutes(2_000L, 1_000L))
        assertEquals(0L, TherapyDisplayFormatter.ageMinutesValue(2_000L, 1_000L))
        assertEquals("—", TherapyDisplayFormatter.target(null, GlucoseUnit.MG_DL))
    }

    @Test
    fun `formats target bounds in selected unit`() {
        val target = TargetState(lowMgDl = 72.0, highMgDl = 180.0)
        assertEquals("72–180", TherapyDisplayFormatter.target(target, GlucoseUnit.MG_DL))
        assertEquals("4.0–10.0", TherapyDisplayFormatter.target(target, GlucoseUnit.MMOL_L))
    }

    @Test
    fun `shares canonical freshness displayability and labels`() {
        val now = 20L * 60_000L
        assertEquals(Freshness.CURRENT, TherapyDisplayFormatter.freshness(stateAt(now - 5 * 60_000L), now))
        assertEquals(Freshness.DELAYED, TherapyDisplayFormatter.freshness(stateAt(now - 8 * 60_000L), now))
        assertEquals(Freshness.STALE, TherapyDisplayFormatter.freshness(stateAt(now - 13 * 60_000L), now))
        assertEquals(Freshness.NO_DATA, TherapyDisplayFormatter.freshness(null, now))
        assertEquals("AKTUELL", TherapyDisplayFormatter.freshnessLabel(Freshness.CURRENT))
        assertEquals("VERZÖGERT", TherapyDisplayFormatter.freshnessLabel(Freshness.DELAYED))
        assertEquals("VERALTET", TherapyDisplayFormatter.freshnessLabel(Freshness.STALE))
        assertEquals("SENSORFEHLER", TherapyDisplayFormatter.freshnessLabel(Freshness.ERROR))
        assertEquals("KEINE DATEN", TherapyDisplayFormatter.freshnessLabel(Freshness.NO_DATA))
        assertTrue(TherapyDisplayFormatter.isGlucoseDisplayable(stateAt(now - 8 * 60_000L), now))
        assertFalse(TherapyDisplayFormatter.isGlucoseDisplayable(stateAt(now - 13 * 60_000L), now))
        assertFalse(
            TherapyDisplayFormatter.isGlucoseDisplayable(
                stateAt(now).copy(glucose = stateAt(now).glucose?.copy(quality = CgmQuality.SENSOR_ERROR)),
                now,
            ),
        )
        assertEquals(
            Freshness.ERROR,
            TherapyDisplayFormatter.freshness(
                stateAt(now).copy(glucose = stateAt(now).glucose?.copy(quality = CgmQuality.SENSOR_ERROR)),
                now,
            ),
        )
        assertFalse(
            TherapyDisplayFormatter.isGlucoseDisplayable(
                stateAt(now).copy(glucose = stateAt(now).glucose?.copy(valueMgDl = Double.NaN)),
                now,
            ),
        )
    }

    @Test
    fun `uses stable source labels across display surfaces`() {
        assertEquals("Watch Direct", TherapyDisplayFormatter.sourceName(DataSourceId.DEXCOM_G7_WATCH))
        assertEquals("AndroidAPS", TherapyDisplayFormatter.sourceName(DataSourceId.ANDROID_APS))
        assertEquals("Nightscout", TherapyDisplayFormatter.sourceName(DataSourceId.NIGHTSCOUT))
        assertEquals("xDrip+", TherapyDisplayFormatter.sourceName(DataSourceId.XDRIP_PLUS))
        assertEquals("Keine Quelle", TherapyDisplayFormatter.sourceName(null))
    }

    private fun glucose(valueMgDl: Double, unit: GlucoseUnit) = GlucoseState(
        valueMgDl = valueMgDl,
        displayUnit = unit,
        trend = Trend.FLAT,
        measuredAtEpochMs = 1L,
        deltaMgDl = null,
        averageDeltaMgDl = null,
    )

    private fun stateAt(timestamp: Long) = TherapyDisplayState(
        source = DataSourceId.ANDROID_APS,
        receivedAtEpochMs = timestamp,
        glucose = GlucoseState(
            valueMgDl = 123.0,
            displayUnit = GlucoseUnit.MG_DL,
            trend = Trend.FLAT,
            measuredAtEpochMs = timestamp,
        ),
    )
}
