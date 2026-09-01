package app.aapswear.complications

import app.aapswear.model.CarbState
import app.aapswear.model.DeviceState
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.InsulinState
import app.aapswear.model.PumpState
import app.aapswear.model.TherapyDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComplicationUpdatePlannerTest {
    @Test
    fun `managed provider set includes fixed G6 style providers`() {
        val providers = ComplicationUpdatePlanner.allManagedProviders

        assertTrue(G6StyleHeaderComplication::class.java in providers)
        assertTrue(G6StyleGraphComplication::class.java in providers)
        assertTrue(G6StyleStatusComplication::class.java in providers)
        assertEquals(providers.size, providers.distinct().size)
    }

    @Test
    fun `identical state requests no provider updates`() {
        val state = state()
        assertTrue(ComplicationUpdatePlanner.affectedProviders(state, state).isEmpty())
    }

    @Test
    fun `glucose changes update regular and G6 glucose graph providers`() {
        val old = state()
        val new = old.copy(glucose = old.glucose!!.copy(valueMgDl = 124.0))
        val affected = ComplicationUpdatePlanner.affectedProviders(old, new)

        assertTrue(GlucoseComplication::class.java in affected)
        assertTrue(GlucoseGraphComplication::class.java in affected)
        assertTrue(G6StyleHeaderComplication::class.java in affected)
        assertTrue(G6StyleGraphComplication::class.java in affected)
        assertTrue(G6StyleStatusComplication::class.java in affected)
        assertFalse(IobComplication::class.java in affected)
        assertFalse(CobComplication::class.java in affected)
        assertFalse(BasalComplication::class.java in affected)
    }

    @Test
    fun `IOB changes update IOB and combined providers only`() {
        val old = state()
        val new = old.copy(insulin = InsulinState(totalIob = 2.1))
        val affected = ComplicationUpdatePlanner.affectedProviders(old, new)

        assertEquals(
            setOf(
                IobComplication::class.java,
                IobRangedValueComplication::class.java,
                IobCobComplication::class.java,
                IobCobLongTextComplication::class.java,
                IobCobBasalComplication::class.java,
                IobCobBasalLongTextComplication::class.java,
                AapsStatusComplication::class.java,
            ),
            affected.toSet(),
        )
    }

    @Test
    fun `pump and phone battery providers receive their own updates`() {
        val old = state()

        val pumpAffected =
            ComplicationUpdatePlanner.affectedProviders(
                old,
                old.copy(pump = PumpState(reservoirUnits = 120.0, batteryPercent = 72)),
            )
        assertTrue(PumpBatteryComplication::class.java in pumpAffected)
        assertTrue(ReservoirComplication::class.java in pumpAffected)
        assertFalse(PhoneBatteryComplication::class.java in pumpAffected)

        val phoneAffected =
            ComplicationUpdatePlanner.affectedProviders(
                old,
                old.copy(device = DeviceState(phoneBatteryPercent = 85)),
            )
        assertEquals(listOf(PhoneBatteryComplication::class.java), phoneAffected)
    }

    @Test
    fun `history changes update regular and G6 graph without glucose header`() {
        val old = state()
        val new =
            old.copy(
                glucoseHistory =
                    old.glucoseHistory +
                        GlucoseSample(
                            valueMgDl = 124.0,
                            measuredAtEpochMs = 2L,
                        ),
            )
        val affected = ComplicationUpdatePlanner.affectedProviders(old, new)

        assertTrue(GlucoseGraphComplication::class.java in affected)
        assertTrue(G6StyleGraphComplication::class.java in affected)
        assertTrue(TirComplication::class.java in affected)
        assertFalse(GlucoseComplication::class.java in affected)
        assertFalse(G6StyleHeaderComplication::class.java in affected)
    }

    private fun state() =
        TherapyDisplayState(
            receivedAtEpochMs = 1L,
            glucose =
                GlucoseState(
                    valueMgDl = 123.0,
                    displayUnit = GlucoseUnit.MG_DL,
                    measuredAtEpochMs = 1L,
                ),
            glucoseHistory = listOf(GlucoseSample(123.0, 1L)),
            insulin = InsulinState(totalIob = 1.2),
            carbs = CarbState(cobGrams = 15.0),
        )
}
