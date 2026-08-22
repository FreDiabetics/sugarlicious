package app.aapswear.g7

import app.aapswear.model.DataSourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CgmAlarmEngineTest {
    private val minute = 60_000L
    private val now = 2_000_000L
    private val settings = CgmAlarmSettings(
        veryHighThreshold = 250.0,
        highThreshold = 180.0,
        lowThreshold = 70.0,
        veryLowThreshold = 40.0,
        rapidRiseThreshold = 2.0,
        rapidFallThreshold = 2.0,
        signalLossMinutes = 16,
        repeatIntervalMinutes = 15,
    )

    @Test
    fun `signal loss starts at sixteen minutes and not before or without a reference reading`() {
        assertNull(state(CgmAlarmEngine.evaluate(null, emptyMap(), settings, now), CgmAlarmType.SIGNAL_LOSS))
        assertNull(
            state(
                CgmAlarmEngine.evaluate(reading(120.0, now - 16 * minute + 1), emptyMap(), settings, now),
                CgmAlarmType.SIGNAL_LOSS,
            ),
        )
        assertEquals(
            CgmAlarmState.ACTIVE,
            state(
                CgmAlarmEngine.evaluate(reading(120.0, now - 16 * minute), emptyMap(), settings, now),
                CgmAlarmType.SIGNAL_LOSS,
            ),
        )
    }

    @Test
    fun `stale glucose activates only signal loss and not glucose or rate alarms`() {
        val alarms = CgmAlarmEngine.evaluate(
            reading(300.0, now - 16 * minute, rate = 3.0),
            emptyMap(),
            settings,
            now,
        )

        assertEquals(CgmAlarmState.ACTIVE, state(alarms, CgmAlarmType.SIGNAL_LOSS))
        assertNull(state(alarms, CgmAlarmType.VERY_HIGH))
        assertNull(state(alarms, CgmAlarmType.HIGH))
        assertNull(state(alarms, CgmAlarmType.RAPID_RISE))

        val staleSensorError = CgmAlarmEngine.evaluate(
            reading(0.0, now - 16 * minute, status = CgmReadingStatus.SENSOR_ERROR),
            emptyMap(),
            settings,
            now,
        )
        assertEquals(CgmAlarmState.ACTIVE, state(staleSensorError, CgmAlarmType.SIGNAL_LOSS))
        assertNull(state(staleSensorError, CgmAlarmType.SENSOR_ERROR))
    }

    @Test
    fun `very low low high and very high are mutually prioritized`() {
        val urgent = CgmAlarmEngine.evaluate(reading(40.0), emptyMap(), settings, now)
        assertEquals(CgmAlarmState.ACTIVE, state(urgent, CgmAlarmType.VERY_LOW))
        assertNull(state(urgent, CgmAlarmType.LOW))

        val low = CgmAlarmEngine.evaluate(reading(65.0), emptyMap(), settings, now)
        assertEquals(CgmAlarmState.ACTIVE, state(low, CgmAlarmType.LOW))
        assertNull(state(low, CgmAlarmType.VERY_LOW))

        val high = CgmAlarmEngine.evaluate(reading(180.0), emptyMap(), settings, now)
        assertEquals(CgmAlarmState.ACTIVE, state(high, CgmAlarmType.HIGH))
        assertNull(state(high, CgmAlarmType.VERY_HIGH))

        val veryHigh = CgmAlarmEngine.evaluate(reading(250.0), emptyMap(), settings, now)
        assertEquals(CgmAlarmState.ACTIVE, state(veryHigh, CgmAlarmType.VERY_HIGH))
        assertNull(state(veryHigh, CgmAlarmType.HIGH))
    }

    @Test
    fun `rate and sensor alarms activate and recover`() {
        val rising = CgmAlarmEngine.evaluate(reading(120.0, rate = 2.1), emptyMap(), settings, now)
        assertEquals(CgmAlarmState.ACTIVE, state(rising, CgmAlarmType.RAPID_RISE))

        val falling = CgmAlarmEngine.evaluate(reading(120.0, rate = -2.1), rising, settings, now)
        assertEquals(CgmAlarmState.RESOLVED, state(falling, CgmAlarmType.RAPID_RISE))
        assertEquals(CgmAlarmState.ACTIVE, state(falling, CgmAlarmType.RAPID_FALL))

        val sensorError = CgmAlarmEngine.evaluate(
            reading(300.0, rate = 3.0, status = CgmReadingStatus.SENSOR_ERROR),
            falling,
            settings,
            now,
        )
        assertEquals(CgmAlarmState.ACTIVE, state(sensorError, CgmAlarmType.SENSOR_ERROR))
        assertNull(state(sensorError, CgmAlarmType.VERY_HIGH))
        assertEquals(CgmAlarmState.RESOLVED, state(sensorError, CgmAlarmType.RAPID_RISE))
        assertEquals(CgmAlarmState.RESOLVED, state(sensorError, CgmAlarmType.RAPID_FALL))
    }

    @Test
    fun `acknowledge prevents duplicate activation until recovery and repeat honors interval`() {
        val active = CgmAlarmEngine.evaluate(reading(65.0), emptyMap(), settings, now)
            .getValue(CgmAlarmType.LOW)
        val acknowledged = CgmAlarmEngine.acknowledge(active, now + minute)
        val stillLow = CgmAlarmEngine.evaluate(
            reading(64.0, now + 5 * minute),
            mapOf(CgmAlarmType.LOW to acknowledged),
            settings,
            now + 5 * minute,
        )
        assertEquals(CgmAlarmState.ACKNOWLEDGED, state(stillLow, CgmAlarmType.LOW))
        assertFalse(CgmAlarmEngine.shouldRepeat(acknowledged, settings, now + 30 * minute))

        val recovered = CgmAlarmEngine.evaluate(reading(100.0), stillLow, settings, now + 10 * minute)
        assertEquals(CgmAlarmState.RESOLVED, state(recovered, CgmAlarmType.LOW))
        val retriggered = CgmAlarmEngine.evaluate(reading(60.0), recovered, settings, now + 15 * minute)
            .getValue(CgmAlarmType.LOW)
        assertEquals(CgmAlarmState.ACTIVE, retriggered.state)
        assertFalse(CgmAlarmEngine.shouldRepeat(retriggered, settings, now + 29 * minute))
        assertTrue(CgmAlarmEngine.shouldRepeat(retriggered, settings, now + 30 * minute))
    }

    private fun state(alarms: Map<CgmAlarmType, CgmAlarm>, type: CgmAlarmType) = alarms[type]?.state

    private fun reading(
        glucose: Double,
        timestamp: Long = now,
        rate: Double? = null,
        status: CgmReadingStatus = CgmReadingStatus.VALID,
    ) = CgmReading(
        id = "reading-$glucose-$timestamp-$rate-$status",
        source = DataSourceId.DEXCOM_G7_WATCH,
        sensorId = "sensor",
        sessionId = "session",
        glucoseMgDl = glucose,
        timestampEpochMs = timestamp,
        receivedAtEpochMs = timestamp,
        trendRateMgDlPerMinute = rate,
        status = status,
        sequenceNumber = timestamp / minute,
    )
}
