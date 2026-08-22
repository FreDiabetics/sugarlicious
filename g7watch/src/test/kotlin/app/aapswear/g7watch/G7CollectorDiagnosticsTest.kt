package app.aapswear.g7watch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.aapswear.g7.CollectorAlarmKind
import app.aapswear.g7.CollectorCycleClassification
import app.aapswear.g7.CollectorCycleTiming
import app.aapswear.g7.CollectorDiagnosticResult
import app.aapswear.g7.CollectorDiagnosticStage
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.g7.G7Sensor
import app.aapswear.model.DataSourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class G7CollectorDiagnosticsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("g7_collector_attempts", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `attempt ids survive recreation and only the latest fifty attempts remain`() {
        repeat(55) { index ->
            val store = G7CollectorDiagnosticStore(context)
            val attempt = store.begin(manual = index % 2 == 0, restart = index % 3 == 0, nowEpochMs = index.toLong())
            store.record(
                attempt.attemptId,
                CollectorDiagnosticStage.COMPLETE,
                CollectorDiagnosticResult.SUCCESS,
                "Erfolgreich",
                nowEpochMs = index.toLong() + 1L,
            )
        }

        val restored = G7CollectorDiagnosticStore(context).snapshot()
        assertEquals(50, restored.size)
        assertEquals(55L, restored.first().attemptId)
        assertEquals(6L, restored.last().attemptId)
        assertTrue(restored.all { it.completedAtEpochMs != null })
    }

    @Test
    fun `scheduled cycle timing survives alarm receiver service handoff and derives lateness`() {
        val store = G7CollectorDiagnosticStore(context)
        val scheduled = CollectorCycleTiming(
            expectedReadingEpoch = 1_300_000L,
            requestedReconnectEpoch = 1_000_000L,
            alarmKind = CollectorAlarmKind.EXACT,
            canScheduleExactAlarms = true,
            batteryUnrestricted = true,
            deviceIdleMode = true,
            isInteractive = false,
            charging = false,
        )
        store.stageScheduledCycle(scheduled)

        store.markScheduledAlarmReceived(1_012_000L)
        val consumed = store.consumeScheduledCycle(1_014_500L)!!
        val attempt = store.begin(false, false, consumed, 1_014_600L)
        store.updateCycle(attempt.attemptId) {
            it.copy(
                wakeLockAcquiredAt = 1_014_700L,
                scanStartedAt = 1_016_000L,
                advertisementFoundAt = 1_020_000L,
                connectGattStartedAt = 1_020_500L,
                gattConnectedAt = 1_022_000L,
                cycleEndedAt = 1_030_000L,
            )
        }
        store.setClassification(attempt.attemptId, CollectorCycleClassification.SUCCESS_FRESH)

        val restored = G7CollectorDiagnosticStore(context).snapshot().single()
        val cycle = restored.cycle!!
        assertEquals(12_000L, cycle.alarmLatenessMs)
        assertEquals(2_500L, cycle.serviceStartLatenessMs)
        assertEquals(16_000L, cycle.scanStartLatenessMs)
        assertEquals(4_000L, cycle.advertisementLatencyMs)
        assertEquals(1_500L, cycle.gattLatencyMs)
        assertEquals(18_000L, cycle.totalCycleLatencyMs)
        assertEquals(CollectorCycleClassification.SUCCESS_FRESH, restored.classification)
        assertEquals(CollectorAlarmKind.EXACT, cycle.alarmKind)
        assertTrue(cycle.deviceIdleMode == true)
        assertTrue(cycle.isInteractive == false)
    }

    @Test
    fun `fresh cycle classification rejects aged packets received now`() {
        val now = 2_000_000L
        val fresh = readingAt(now - 5 * 60_000L, now, sensorAgeSeconds = 5 * 60L)
        val aged = readingAt(now - 20 * 60_000L, now, sensorAgeSeconds = 20 * 60L)

        assertTrue(isFreshG7CycleReading(fresh, now))
        assertFalse(isFreshG7CycleReading(aged, now))
    }

    @Test
    fun `failure classification separates missed advertisement GATT auth packet and store errors`() {
        assertEquals(
            CollectorCycleClassification.NO_ADVERTISEMENT,
            classifyG7CycleFailure("G7-BLE-107", CollectorCycleTiming()),
        )
        assertEquals(
            CollectorCycleClassification.GATT_CONNECT_FAILED,
            classifyG7CycleFailure("G7-GATT-133", CollectorCycleTiming()),
        )
        assertEquals(
            CollectorCycleClassification.AUTH_FAILED,
            classifyG7CycleFailure("G7-AUTH-205", CollectorCycleTiming()),
        )
        assertEquals(
            CollectorCycleClassification.GLUCOSE_TIMEOUT,
            classifyG7CycleFailure("G7-BLE-111", CollectorCycleTiming()),
        )
        assertEquals(
            CollectorCycleClassification.INVALID_PACKET,
            classifyG7CycleFailure("G7-DATA-301", CollectorCycleTiming()),
        )
    }

    @Test
    fun `event history is bounded and secrets and Bluetooth addresses are redacted`() {
        val store = G7CollectorDiagnosticStore(context)
        val attempt = store.begin(manual = true, restart = false, nowEpochMs = 0L)
        repeat(105) { index ->
            store.record(
                attempt.attemptId,
                CollectorDiagnosticStage.SCANNING,
                message = "event=$index sharedKey=DEADBEEF pairingCode=1234 address=AA:BB:CC:DD:EE:FF\n",
                nowEpochMs = index.toLong() + 1L,
            )
        }

        val events = store.snapshot().single().events
        assertEquals(100, events.size)
        val text = events.joinToString(" ", transform = { it.message })
        assertFalse(text.contains("DEADBEEF"))
        assertFalse(text.contains("1234"))
        assertFalse(text.contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(text.contains("[REDACTED]"))
        assertTrue(text.contains("••:••:••:••:EE:FF"))
    }

    @Test
    fun `reading summary counts missing five minute windows in chronological order`() {
        val readings = listOf(reading(20L), reading(0L), reading(5L))
        val summary = summarizeG7Readings(readings, startOfDayEpochMs = 1L)

        assertEquals(3, summary.count)
        assertEquals(2, summary.todayCount)
        assertEquals(0L, summary.oldestEpochMs)
        assertEquals(20L * 60_000L, summary.latestEpochMs)
        assertEquals(2, summary.missedExpectedWindows)
    }

    @Test
    fun `reading summary does not count a sensor session change as a gap`() {
        val readings =
            listOf(
                reading(0L),
                reading(30L).copy(sensorId = "next-sensor", sessionId = "next-session"),
            )

        assertEquals(0, summarizeG7Readings(readings, startOfDayEpochMs = 0L).missedExpectedWindows)
    }

    @Test
    fun `reading summary reports only plausible valid glucose successes`() {
        val readings =
            listOf(
                reading(0L),
                reading(5L).copy(status = CgmReadingStatus.SENSOR_ERROR, glucoseMgDl = 0.0),
                reading(10L).copy(glucoseMgDl = Double.NaN),
                reading(15L).copy(glucoseMgDl = 1_001.0),
            )

        val summary = summarizeG7Readings(readings, startOfDayEpochMs = 0L)

        assertEquals(1, summary.count)
        assertEquals(0L, summary.latestEpochMs)
        assertEquals(0, summary.missedExpectedWindows)
    }

    @Test
    fun `collector graph never mixes readings from another sensor session`() {
        val active = reading(5L)
        val sameSensorOtherSession = reading(10L).copy(sessionId = "other-session")
        val otherSensor = reading(15L).copy(sensorId = "other-sensor")

        val filtered =
            currentG7SessionReadings(
                listOf(active, sameSensorOtherSession, otherSensor),
                G7Sensor("sensor", "session"),
            )

        assertEquals(listOf(active), filtered)
    }

    private fun reading(minutes: Long) = CgmReading(
        id = "reading-$minutes",
        source = DataSourceId.DEXCOM_G7_WATCH,
        sensorId = "sensor",
        sessionId = "session",
        glucoseMgDl = 120.0,
        timestampEpochMs = minutes * 60_000L,
        receivedAtEpochMs = minutes * 60_000L,
        sequenceNumber = minutes,
    )

    private fun readingAt(
        measuredAt: Long,
        receivedAt: Long,
        sensorAgeSeconds: Long,
    ) = CgmReading(
        id = "reading-$measuredAt-$receivedAt",
        source = DataSourceId.DEXCOM_G7_WATCH,
        sensorId = "sensor",
        sessionId = "session",
        glucoseMgDl = 120.0,
        timestampEpochMs = measuredAt,
        receivedAtEpochMs = receivedAt,
        sequenceNumber = 1L,
        sensorAgeSeconds = sensorAgeSeconds,
    )
}
