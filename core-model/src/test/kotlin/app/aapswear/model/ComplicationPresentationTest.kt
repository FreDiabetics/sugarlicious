package app.aapswear.model

import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComplicationPresentationTest {
    @Test
    fun `typed provider ids remain unique and resolve to their presentation family`() {
        assertEquals(36, SugarliciousComplicationIds.all.size)
        assertEquals(36, SugarliciousComplicationIds.all.distinct().size)
        assertEquals(
            SugarliciousComplicationIds.GLUCOSE_TREND,
            SugarliciousComplicationIds.baseId(SugarliciousComplicationIds.GLUCOSE_TREND_RANGED),
        )
    }

    private val now = 1_700_000_000_000L
    private val state = TherapyDisplayState(
        receivedAtEpochMs = now,
        sourceVersion = "4.0.0",
        glucose = GlucoseState(123.0, GlucoseUnit.MG_DL, Trend.FORTY_FIVE_UP, now - 2 * 60_000L, 5.0),
        glucoseHistory = listOf(
            GlucoseSample(80.0, now - 10 * 60_000L),
            GlucoseSample(120.0, now - 5 * 60_000L),
            GlucoseSample(200.0, now),
        ),
        insulin = InsulinState(totalIob = 1.2),
        carbs = CarbState(cobGrams = 15.0),
        basal = BasalState(currentUnitsPerHour = 0.7),
    )

    @Test fun `glucose trend keeps text and icon separate`() {
        val p = ComplicationPresentationFormatter.format(SugarliciousComplicationIds.GLUCOSE_TREND, state, now)
        assertEquals("123", p.text)
        assertEquals(Trend.FORTY_FIVE_UP, p.trend)
        assertNull(p.title)
    }

    @Test fun `glucose delta uses title instead of concatenating`() {
        val p = ComplicationPresentationFormatter.format(SugarliciousComplicationIds.GLUCOSE_PLUS_DELTA, state, now)
        assertEquals("123", p.text)
        assertEquals("+5", p.title)
    }

    @Test fun `time delta keeps compact lines`() {
        val p = ComplicationPresentationFormatter.format(SugarliciousComplicationIds.TIME_DELTA, state, now)
        assertEquals("+5", p.text)
        assertEquals("2m", p.title)
    }

    @Test fun `trend geometry matches mobile overview`() {
        assertEquals(TrendVisualAsset.FORTY_FIVE_UP, TrendVisuals.spec(Trend.FORTY_FIVE_UP)!!.asset)
        assertEquals(1f, TrendVisuals.spec(Trend.FORTY_FIVE_UP)!!.aspectRatio)
        assertEquals(TrendVisualAsset.DOUBLE_DOWN, TrendVisuals.spec(Trend.DOUBLE_DOWN)!!.asset)
        assertEquals(125f / 60f, TrendVisuals.spec(Trend.DOUBLE_DOWN)!!.aspectRatio)
        assertNull(TrendVisuals.spec(Trend.UNKNOWN))
    }

    @Test fun `tir presentation uses configured central thresholds`() {
        val thresholds = CgmThresholds(
            veryHighMgDl = 240.0,
            highMgDl = 160.0,
            lowMgDl = 80.0,
            veryLowMgDl = 55.0,
        )
        val presentation = ComplicationPresentationFormatter.format(
            SugarliciousComplicationIds.TIR,
            state,
            now,
            thresholds,
        )

        assertEquals("67%", presentation.text)
        assertEquals("80–160", presentation.title)
    }

    @Test fun `date weekdays always use the requested German abbreviations`() {
        val expected = listOf("MON", "DIE", "MIT", "DON", "FRE", "SAM", "SON")
        assertEquals(expected, DayOfWeek.entries.map(ComplicationPresentationFormatter::germanWeekday))
    }
}
