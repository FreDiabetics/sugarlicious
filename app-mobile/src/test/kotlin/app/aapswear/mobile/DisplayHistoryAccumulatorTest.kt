package app.aapswear.mobile

import app.aapswear.model.CarbState
import app.aapswear.model.CgmQuality
import app.aapswear.model.DataSourceId
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.InsulinState
import app.aapswear.model.LoopState
import app.aapswear.model.TargetSample
import app.aapswear.model.TherapyDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayHistoryAccumulatorTest {
    @Test
    fun `deduplicates and bounds display history`() {
        val now = 2 * DisplayHistoryAccumulator.WINDOW_MS
        fun state(at: Long, glucose: Double) = TherapyDisplayState(
            receivedAtEpochMs = at,
            glucose = GlucoseState(glucose, GlucoseUnit.MG_DL, measuredAtEpochMs = at),
            insulin = InsulinState(totalIob = glucose / 100),
            carbs = CarbState(cobGrams = glucose / 10),
        )
        val old = state(now - DisplayHistoryAccumulator.WINDOW_MS - 1, 90.0)
        val first = DisplayHistoryAccumulator.merge(null, old, now)
        assertEquals(0, first.glucoseHistory.size)

        val second = DisplayHistoryAccumulator.merge(first, state(now, 120.0), now)
        val replaced = DisplayHistoryAccumulator.merge(second, state(now, 125.0), now)
        assertEquals(listOf(125.0), replaced.glucoseHistory.map { it.valueMgDl })
        assertEquals(1.25, replaced.therapyHistory.single().totalIob!!, 0.001)
    }

    @Test
    fun `incoming history samples close an existing graph gap`() {
        val minute = 60_000L
        val now = 1000 * minute
        val previous = TherapyDisplayState(
            receivedAtEpochMs = now - 10 * minute,
            glucose = GlucoseState(140.0, GlucoseUnit.MG_DL, measuredAtEpochMs = now - 10 * minute),
            glucoseHistory = listOf(
                GlucoseSample(120.0, now - 25 * minute),
                GlucoseSample(140.0, now - 10 * minute),
            ),
        )
        assertTrue(DisplayHistoryAccumulator.hasGap(previous.glucoseHistory))

        val current = TherapyDisplayState(
            receivedAtEpochMs = now,
            glucose = GlucoseState(150.0, GlucoseUnit.MG_DL, measuredAtEpochMs = now),
            glucoseHistory = listOf(
                GlucoseSample(125.0, now - 20 * minute),
                GlucoseSample(132.0, now - 15 * minute),
                GlucoseSample(145.0, now - 5 * minute),
            ),
        )

        val merged = DisplayHistoryAccumulator.merge(previous, current, now)

        assertEquals(
            listOf(-25L, -20L, -15L, -10L, -5L, 0L),
            merged.glucoseHistory.map { (it.measuredAtEpochMs - now) / minute },
        )
        assertFalse(DisplayHistoryAccumulator.hasGap(merged.glucoseHistory))
    }

    @Test
    fun `AndroidAPS wins over a nearby xDrip reading`() {
        val now = 2_000_000L
        val state = TherapyDisplayState(
            source = DataSourceId.ANDROID_APS,
            receivedAtEpochMs = now,
            glucose = GlucoseState(123.0, GlucoseUnit.MG_DL, measuredAtEpochMs = now),
            glucoseHistory = listOf(
                GlucoseSample(121.0, now - 30_000L, DataSourceId.XDRIP_PLUS),
            ),
        )
        val merged = DisplayHistoryAccumulator.merge(null, state, now)
        assertEquals(1, merged.glucoseHistory.size)
        assertEquals(DataSourceId.ANDROID_APS, merged.glucoseHistory.single().source)
        assertEquals(123.0, merged.glucoseHistory.single().valueMgDl, 0.0)
    }

    @Test
    fun `sensor error samples never enter canonical graph history`() {
        val now = 2_000_000L
        val state = TherapyDisplayState(
            source = DataSourceId.ANDROID_APS,
            receivedAtEpochMs = now,
            glucose = GlucoseState(
                123.0,
                GlucoseUnit.MG_DL,
                measuredAtEpochMs = now,
                quality = CgmQuality.SENSOR_ERROR,
            ),
        )

        assertTrue(DisplayHistoryAccumulator.merge(null, state, now).glucoseHistory.isEmpty())
    }

    @Test
    fun `stores a public enacted SMB as a therapy marker without losing IOB`() {
        val now = 2_000_000L
        val state = TherapyDisplayState(
            receivedAtEpochMs = now,
            glucose = GlucoseState(123.0, GlucoseUnit.MG_DL, measuredAtEpochMs = now),
            insulin = InsulinState(totalIob = 1.2),
            loop = LoopState(
                enactedAtEpochMs = now,
                smbUnits = 0.25,
                smbAtEpochMs = now,
            ),
        )

        val sample = DisplayHistoryAccumulator.merge(null, state, now).therapyHistory.single()

        assertEquals(1.2, sample.totalIob!!, 0.0)
        assertEquals(0.25, sample.smbUnits!!, 0.0)
    }

    @Test
    fun `does not extend a temporary target beyond its published end`() {
        val minute = 60_000L
        val now = 2_000_000L
        val temporaryEnd = now - 5 * minute
        val previous = TherapyDisplayState(
            receivedAtEpochMs = temporaryEnd,
            targetHistory = listOf(
                TargetSample(
                    valueMgDl = 90.0,
                    startedAtEpochMs = now - 35 * minute,
                    endsAtEpochMs = temporaryEnd,
                    temporary = true,
                ),
            ),
        )
        val current = TherapyDisplayState(
            receivedAtEpochMs = now,
            targetHistory = listOf(
                TargetSample(
                    valueMgDl = 105.0,
                    startedAtEpochMs = now,
                    endsAtEpochMs = now,
                    temporary = false,
                ),
            ),
        )

        val merged = DisplayHistoryAccumulator.merge(previous, current, now)

        assertEquals(temporaryEnd, merged.targetHistory.first().endsAtEpochMs)
        assertEquals(now, merged.targetHistory.last().startedAtEpochMs)
    }
}
