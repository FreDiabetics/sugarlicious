package app.aapswear.wear

import app.aapswear.model.BasalState
import app.aapswear.model.CarbState
import app.aapswear.model.DataSourceId
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.InsulinState
import app.aapswear.model.TargetState
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
import app.aapswear.protocol.WatchUiColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SugarliciousTilesTest {
    private val now = 50_000_000L
    private val colors = WatchUiColors(
        glucoseLow = 0xFFAA0000.toInt(),
        glucoseInRange = 0xFF00AA00.toInt(),
        glucoseHigh = 0xFFAAAA00.toInt(),
    )

    @Test
    fun `glucose tile keeps value trend and source separate and explicit`() {
        val presentation = wearGlucoseTilePresentation(state(123.0, now - 2 * 60_000L), colors, now)

        assertEquals("123", presentation.value)
        assertEquals(Trend.FORTY_FIVE_UP, presentation.trend)
        assertTrue(presentation.meta.contains("mg/dL"))
        assertTrue(presentation.footer.contains("AndroidAPS"))
        assertEquals(colors.glucoseInRange, presentation.valueColor)
        assertEquals("AKTUELL", presentation.status)
    }

    @Test
    fun `stale tile never presents an old value as current`() {
        val stale = wearGlucoseTilePresentation(state(123.0, now - 20 * 60_000L), colors, now)
        val therapy = wearTherapyTilePresentation(state(123.0, now - 20 * 60_000L), now)

        assertEquals("—", stale.value)
        assertNull(stale.trend)
        assertEquals("VERALTET", stale.status)
        assertFalse(therapy.displayable)
        assertEquals("—", therapy.iob)
        assertEquals("—", therapy.cob)
        assertEquals("—", therapy.basal)
    }

    @Test
    fun `therapy tile exposes three independent modern metric cards`() {
        val presentation = wearTherapyTilePresentation(state(123.0, now - 60_000L), now)

        assertTrue(presentation.displayable)
        assertEquals("1.2 U", presentation.iob)
        assertEquals("18 g", presentation.cob)
        assertEquals("0.70", presentation.basal)
    }

    private fun state(value: Double, measuredAt: Long) = TherapyDisplayState(
        source = DataSourceId.ANDROID_APS,
        receivedAtEpochMs = now,
        glucose = GlucoseState(
            valueMgDl = value,
            displayUnit = GlucoseUnit.MG_DL,
            trend = Trend.FORTY_FIVE_UP,
            measuredAtEpochMs = measuredAt,
            deltaMgDl = 5.0,
        ),
        insulin = InsulinState(totalIob = 1.2),
        carbs = CarbState(cobGrams = 18.0),
        basal = BasalState(currentUnitsPerHour = 0.70),
        target = TargetState(lowMgDl = 80.0, highMgDl = 160.0),
    )
}
