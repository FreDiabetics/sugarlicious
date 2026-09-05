package app.aapswear.wear

import app.aapswear.model.DataSourceId
import app.aapswear.model.Freshness
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WearStartupStateCoordinatorTest {
    @Test fun `rehydration preserves measurement identity and recalculates stale from event time`() {
        val eventAt = 1_000_000L
        val receivedAt = eventAt + 30_000L
        val persisted = TherapyDisplayState(
            source = DataSourceId.DEXCOM_G7_WATCH,
            receivedAtEpochMs = receivedAt,
            glucose = GlucoseState(
                valueMgDl = 176.0,
                displayUnit = GlucoseUnit.MG_DL,
                trend = Trend.SINGLE_UP,
                measuredAtEpochMs = eventAt,
                receivedAtEpochMs = receivedAt,
            ),
        )

        val restored = prepareStartupSnapshot(persisted, eventAt + 20 * 60_000L)

        assertSame(persisted, restored.state)
        assertEquals(eventAt, restored.state?.glucose?.measuredAtEpochMs)
        assertEquals(receivedAt, restored.state?.glucose?.receivedAtEpochMs)
        assertEquals(receivedAt, restored.state?.receivedAtEpochMs)
        assertEquals(Freshness.STALE, restored.freshness)
    }

    @Test fun `rehydration keeps explicit no data instead of inventing defaults`() {
        val restored = prepareStartupSnapshot(null, 2_000_000L)

        assertEquals(null, restored.state)
        assertEquals(Freshness.NO_DATA, restored.freshness)
    }
}
