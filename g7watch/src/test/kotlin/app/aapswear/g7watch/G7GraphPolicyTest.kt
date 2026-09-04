package app.aapswear.g7watch

import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.model.DataSourceId
import app.aapswear.model.Trend
import org.junit.Assert.assertEquals
import org.junit.Test

class G7GraphPolicyTest {
    private val now = 10_000_000L

    @Test fun `one high does not activate high area`() {
        assertEquals(G7RangeExcursion.NONE, G7GraphPolicy.rangeExcursion(listOf(reading("1", 166.0, now - 5 * 60_000L)), 80.0, 160.0, now))
    }

    @Test fun `two consecutive high values activate high area`() {
        assertEquals(
            G7RangeExcursion.HIGH,
            G7GraphPolicy.rangeExcursion(
                listOf(reading("1", 166.0, now - 5 * 60_000L), reading("2", 171.0, now)),
                80.0, 160.0, now,
            ),
        )
    }

    @Test fun `return in range clears high area immediately`() {
        assertEquals(
            G7RangeExcursion.NONE,
            G7GraphPolicy.rangeExcursion(
                listOf(
                    reading("1", 166.0, now - 10 * 60_000L),
                    reading("2", 171.0, now - 5 * 60_000L),
                    reading("3", 158.0, now),
                ),
                80.0, 160.0, now,
            ),
        )
    }

    @Test fun `two consecutive low values activate low area`() {
        assertEquals(
            G7RangeExcursion.LOW,
            G7GraphPolicy.rangeExcursion(
                listOf(reading("1", 77.0, now - 5 * 60_000L), reading("2", 73.0, now)),
                80.0, 160.0, now,
            ),
        )
    }

    @Test fun `return in range clears low area immediately`() {
        assertEquals(
            G7RangeExcursion.NONE,
            G7GraphPolicy.rangeExcursion(
                listOf(
                    reading("1", 77.0, now - 10 * 60_000L),
                    reading("2", 73.0, now - 5 * 60_000L),
                    reading("3", 84.0, now),
                ),
                80.0, 160.0, now,
            ),
        )
    }

    @Test fun `duplicate cannot provide second high value`() {
        val first = reading("1", 166.0, now - 5 * 60_000L)
        val duplicate = first.copy(id = "duplicate", receivedAtEpochMs = first.receivedAtEpochMs + 1_000L)
        assertEquals(G7RangeExcursion.NONE, G7GraphPolicy.rangeExcursion(listOf(first, duplicate), 80.0, 160.0, now))
    }

    @Test fun `invalid events do not activate and stale time does not erase semantic range`() {
        val invalid = reading("1", 170.0, now - 5 * 60_000L).copy(status = CgmReadingStatus.INVALID)
        val high = reading("2", 175.0, now)
        assertEquals(G7RangeExcursion.NONE, G7GraphPolicy.rangeExcursion(listOf(invalid, high), 80.0, 160.0, now))

        val oldNow = now + G7GraphPolicy.STALE_AFTER_MS
        assertEquals(
            G7RangeExcursion.HIGH,
            G7GraphPolicy.rangeExcursion(
                listOf(reading("3", 170.0, now - 5 * 60_000L), reading("4", 175.0, now)),
                80.0, 160.0, oldNow,
            ),
        )
    }

    @Test fun `out of order arrival resets sequence`() {
        val first = reading("1", 170.0, now - 5 * 60_000L, receivedAt = now - 4 * 60_000L)
        val olderArrivingLater = reading("2", 172.0, now - 10 * 60_000L, receivedAt = now - 3 * 60_000L)
        val latest = reading("3", 174.0, now, receivedAt = now)
        assertEquals(G7RangeExcursion.NONE, G7GraphPolicy.rangeExcursion(listOf(first, olderArrivingLater, latest), 80.0, 160.0, now))
    }

    @Test fun `sensor or session switch resets sequence`() {
        val first = reading("1", 170.0, now - 5 * 60_000L)
        val switchedSensor = reading("2", 175.0, now).copy(sensorId = "sensor-2")
        assertEquals(G7RangeExcursion.NONE, G7GraphPolicy.rangeExcursion(listOf(first, switchedSensor), 80.0, 160.0, now))

        val switchedSession = reading("3", 175.0, now).copy(sessionId = "session-2")
        assertEquals(G7RangeExcursion.NONE, G7GraphPolicy.rangeExcursion(listOf(first, switchedSession), 80.0, 160.0, now))
    }

    private fun reading(
        id: String,
        value: Double,
        measuredAt: Long,
        receivedAt: Long = measuredAt + 1_000L,
    ) = CgmReading(
        id = id,
        source = DataSourceId.DEXCOM_G7_WATCH,
        sensorId = "sensor-1",
        sessionId = "session-1",
        glucoseMgDl = value,
        timestampEpochMs = measuredAt,
        receivedAtEpochMs = receivedAt,
        trend = Trend.FLAT,
        status = CgmReadingStatus.VALID,
    )
}
