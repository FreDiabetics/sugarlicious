package app.aapswear.wear

import app.aapswear.model.CarbState
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.TherapyDisplayState
import org.junit.Assert.assertEquals
import org.junit.Test

class SugarliciousTileUpdatePlannerTest {
    private val base =
        TherapyDisplayState(
            receivedAtEpochMs = 1L,
            glucose = GlucoseState(120.0, GlucoseUnit.MG_DL, measuredAtEpochMs = 1L),
        )

    @Test fun `glucose only update invalidates only glucose tile`() {
        val changed = base.copy(glucose = base.glucose?.copy(valueMgDl = 121.0))
        assertEquals(setOf(GlucoseTileService::class.java), affectedSugarliciousTiles(base, changed))
    }

    @Test fun `therapy only update invalidates only therapy tile`() {
        val changed = base.copy(carbs = CarbState(cobGrams = 12.0))
        assertEquals(setOf(TherapyTileService::class.java), affectedSugarliciousTiles(base, changed))
    }

    @Test fun `transport only revision invalidates no tile`() {
        assertEquals(emptySet<Class<*>>(), affectedSugarliciousTiles(base, base.copy(canonicalRevision = 2L)))
    }
}
