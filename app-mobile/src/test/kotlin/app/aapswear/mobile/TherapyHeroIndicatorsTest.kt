package app.aapswear.mobile

import app.aapswear.model.BasalState
import app.aapswear.model.CarbState
import app.aapswear.model.InsulinState
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.TherapyHistorySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TherapyHeroIndicatorsTest {
    @Test
    fun `therapy mode suppresses separate details without changing stored choice`() {
        val preferences = DashboardUiPreferences(showDetails = true, glucoseTileDetailMode = GlucoseTileDetailMode.THERAPY)
        assertTrue(preferences.showDetails)
        assertEquals(false, preferences.effectiveShowDetails)
        assertEquals(true, preferences.copy(glucoseTileDetailMode = GlucoseTileDetailMode.TIR).effectiveShowDetails)
    }

    @Test
    fun `three rings use canonical values and clamp their scales`() {
        val state = state(
            insulin = InsulinState(totalIob = 12.0),
            carbs = CarbState(cobGrams = 450.0),
            basal = BasalState(currentUnitsPerHour = 0.8, tempAbsoluteUnitsPerHour = 1.2, tempPercent = 150),
        )
        val values = therapyIndicatorPresentations(state, iobMaximumUnits = 10f, nowEpochMs = 1_000L)
        assertEquals(3, values.size)
        assertEquals(1f, values[0].progress!!, 0.0001f)
        assertEquals(1f, values[1].progress!!, 0.0001f)
        assertEquals("12,00U", values[0].value)
        assertEquals("450g", values[1].value)
        assertEquals("1,20U/h", values[2].value)
        assertEquals("@150%", values[2].secondary)
        assertEquals(0.3f, values[2].progress!!, 0.0001f)
    }

    @Test
    fun `basal icon follows standard lower and higher temp basal`() {
        assertEquals(R.drawable.ic_basal, basalIconResource(100))
        assertEquals(R.drawable.ic_basalless, basalIconResource(80))
        assertEquals(R.drawable.ic_basalmore, basalIconResource(120))
    }

    @Test
    fun `latest canonical therapy history supplies missing basal snapshot`() {
        val state = TherapyDisplayState(
            receivedAtEpochMs = 1_000L,
            therapyHistory = listOf(
                TherapyHistorySample(800L, basalUnitsPerHour = 0.75, baseBasalUnitsPerHour = 0.5),
            ),
        )
        val basal = therapyIndicatorPresentations(state, 10f, 1_000L)[2]
        assertEquals("0,75U/h", basal.value)
        assertEquals("@150%", basal.secondary)
        assertEquals(R.drawable.ic_basalmore, basal.iconRes)
    }

    @Test
    fun `zero IOB maximum is safe and never invents progress`() {
        val values = therapyIndicatorPresentations(state(insulin = InsulinState(totalIob = 2.0)), 0f, 1_000L)
        assertNull(values.first().progress)
    }

    @Test
    fun `missing values remain unknown instead of zero or normal basal`() {
        val values = therapyIndicatorPresentations(null, 10f, 1_000L)
        values.forEach { value ->
            assertEquals("—", value.value)
            assertNull(value.progress)
        }
        assertNull(values[2].secondary)
    }

    @Test
    fun `expired temp basal falls back to canonical base rate and 100 percent`() {
        val basal = BasalState(
            currentUnitsPerHour = 0.8,
            tempAbsoluteUnitsPerHour = 1.2,
            tempPercent = 150,
            tempEndsAtEpochMs = 900L,
        )
        assertEquals(EffectiveBasalPresentation(0.8, 100), effectiveBasalPresentation(basal, 1_000L))
    }

    @Test
    fun `graph separates plot from time and value axis background lanes`() {
        val bounds = mobileCgmGraphBounds(400f, 240f, 1f, 32f, 40f, scaleOnRight = true)
        assertEquals(359f, bounds.plot.right, 0.001f)
        assertEquals(bounds.plot.bottom, bounds.timeAxis.top, 0.001f)
        assertEquals(bounds.plot.right, bounds.valueAxis.left, 0.001f)
        assertTrue(bounds.tile.contains(bounds.plot))
        assertTrue(!bounds.plot.contains(bounds.valueAxis.centerX(), bounds.valueAxis.centerY()))
    }

    private fun state(
        insulin: InsulinState? = null,
        carbs: CarbState? = null,
        basal: BasalState? = null,
    ) = TherapyDisplayState(receivedAtEpochMs = 1_000L, insulin = insulin, carbs = carbs, basal = basal)
}
