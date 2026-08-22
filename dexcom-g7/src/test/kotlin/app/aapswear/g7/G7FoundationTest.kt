package app.aapswear.g7

import app.aapswear.model.DataSourceId
import app.aapswear.model.Trend
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder

class G7FoundationTest {
    private val now = 1_800_000_000_000L

    private fun reading(value: Double, at: Long = now, id: String = value.toString()) = CgmReading(
        id, DataSourceId.DEXCOM_G7_WATCH, "sensor", "session", value, at, at,
    )

    @Test fun `delta accepts only adjacent sensor readings`() {
        assertEquals(4.0, CgmDeltaCalculator.calculate(reading(112.0), reading(108.0, now - 5 * 60_000L)))
        assertNull(CgmDeltaCalculator.calculate(reading(112.0), reading(108.0, now - 20 * 60_000L)))
    }

    @Test fun `delta rejects sensor errors and implausible glucose values`() {
        val sensorError = reading(0.0, now - 5 * 60_000L).copy(status = CgmReadingStatus.SENSOR_ERROR)
        assertNull(CgmDeltaCalculator.calculate(reading(112.0), sensorError))
        assertNull(CgmDeltaCalculator.calculate(reading(112.0), reading(Double.NaN, now - 5 * 60_000L)))
        assertNull(CgmDeltaCalculator.calculate(reading(112.0), reading(1_001.0, now - 5 * 60_000L)))
    }

    @Test fun `CGM conversion marks implausible values invalid without inventing a replacement`() {
        val converted =
            G7Reading(
                sensorId = "sensor",
                sessionId = "session",
                sequenceNumber = 1L,
                glucoseMgDl = 14.0,
                sensorTimestampEpochMs = now,
                receivedAtEpochMs = now,
                sensorState = G7SensorState.ACTIVE,
            ).toCgm()

        assertEquals(CgmReadingStatus.INVALID, converted.status)
        assertEquals(14.0, converted.glucoseMgDl)
    }

    @Test fun `sensor and session changes never create synthetic history gaps`() {
        val previous = reading(108.0, now - 30 * 60_000L).copy(sensorId = "old-sensor", sessionId = "old-session")
        val current = reading(112.0).copy(sensorId = "new-sensor", sessionId = "new-session")

        assertTrue(CgmGapDetector.detect(listOf(previous, current)).isEmpty())
    }

    @Test fun `local current G7 wins source resolution`() {
        val g7 = reading(112.0)
        val phone = g7.copy(id = "phone", source = DataSourceId.ANDROID_APS, timestampEpochMs = now + 1_000L)
        assertEquals(g7, CgmSourceResolver.resolve(listOf(CgmSourceCandidate(DataSourceId.ANDROID_APS, phone), CgmSourceCandidate(DataSourceId.DEXCOM_G7_WATCH, g7)), now))
    }

    @Test fun `phone loss never changes watch ownership`() {
        val manager = CollectorOwnershipManager(CollectorOwner.WATCH)
        assertEquals(CollectorOwner.WATCH, manager.phoneDisconnected())
    }

    @Test fun `fresh setup clears stale errors retries and readings`() {
        val stale = G7PersistedState(
            sensor = G7Sensor("old"),
            collectorEnabled = true,
            connectionState = G7ConnectionState.DISCONNECTED,
            protocolState = G7ProtocolState.ERROR,
            sessionState = G7SessionState.RECOVERING,
            authenticationState = G7AuthenticationState.FAILED,
            lastReading = reading(99.0),
            lastSuccessfulConnectionEpochMs = now - 60_000L,
            nextReconnectEpochMs = now + 60_000L,
            retryCount = 5,
            lastError = G7CollectorError("G7-BLE-107", true, now, "not found"),
        )
        val state = G7SessionManager(stale).beginInitialSetup(G7Sensor("new"))
        assertEquals(G7ConnectionState.SCANNING, state.connectionState)
        assertEquals(G7ProtocolState.SCANNING, state.protocolState)
        assertEquals(G7SessionState.INITIAL_SETUP, state.sessionState)
        assertEquals(G7AuthenticationState.REQUIRED, state.authenticationState)
        assertEquals(0, state.retryCount)
        assertNull(state.lastError)
        assertNull(state.lastReading)
        assertNull(state.lastSuccessfulConnectionEpochMs)
        assertNull(state.nextReconnectEpochMs)
    }

    @Test fun `entering a sensor code can prepare setup without enabling scan`() {
        val stale = G7PersistedState(
            sensor = G7Sensor("old"),
            collectorEnabled = true,
            protocolState = G7ProtocolState.ERROR,
            sessionState = G7SessionState.RECOVERING,
            nextReconnectEpochMs = now + 60_000L,
            retryCount = 4,
        )
        val prepared = G7SessionManager(stale).prepareInitialSetup(G7Sensor("new"))
        assertFalse(prepared.collectorEnabled)
        assertEquals(G7ConnectionState.DISCONNECTED, prepared.connectionState)
        assertEquals(G7ProtocolState.IDLE, prepared.protocolState)
        assertEquals(G7SessionState.UNINITIALIZED, prepared.sessionState)
        assertNull(prepared.nextReconnectEpochMs)
        assertEquals(0, prepared.retryCount)
    }

    @Test fun `explicit collector start enables prepared sensor`() {
        val prepared = G7SessionManager().prepareInitialSetup(G7Sensor("sensor"))
        val started = G7SessionManager(prepared).startCollector()
        assertTrue(started.collectorEnabled)
        assertEquals(CollectorOwner.WATCH, started.collectorOwner)
        assertEquals(G7SessionState.INITIAL_SETUP, started.sessionState)
        assertEquals(G7ProtocolState.IDLE, started.protocolState)
    }

    @Test fun `collector stop prevents scheduled reconnect but keeps sensor configuration`() {
        val configured = G7PersistedState(
            sensor = G7Sensor("sensor"),
            collectorEnabled = true,
            protocolState = G7ProtocolState.WAITING_FOR_NEXT_READING,
            sessionState = G7SessionState.WAITING_FOR_NEXT_READING,
            nextReconnectEpochMs = now + 60_000L,
            retryCount = 3,
        )
        val stopped = G7SessionManager(configured).stop()
        assertFalse(stopped.collectorEnabled)
        assertEquals("sensor", stopped.sensor?.sensorId)
        assertEquals(G7ProtocolState.IDLE, stopped.protocolState)
        assertEquals(G7SessionState.UNINITIALIZED, stopped.sessionState)
        assertNull(stopped.nextReconnectEpochMs)
        assertEquals(0, stopped.retryCount)
    }

    @Test fun `reading schedules autonomous reconnect and clears retries`() {
        val manager = G7SessionManager(G7PersistedState(collectorEnabled = true, retryCount = 4))
        val state = manager.readingReceived(reading(112.0))
        assertEquals(G7SessionState.WAITING_FOR_NEXT_READING, state.sessionState)
        assertEquals(0, state.retryCount)
        assertEquals(now + 270_000L, state.nextReconnectEpochMs)
    }

    @Test fun `sensor error packet preserves last valid reading and schedules its next window`() {
        val lastValid = reading(112.0, now - 5 * 60_000L, "valid")
        val sensorError =
            reading(0.0, now, "sensor-error").copy(status = CgmReadingStatus.SENSOR_ERROR)

        val state =
            G7SessionManager(G7PersistedState(collectorEnabled = true, lastReading = lastValid))
                .readingReceived(sensorError)

        assertEquals(lastValid, state.lastReading)
        assertEquals(now + 270_000L, state.nextReconnectEpochMs)
    }

    @Test fun `recovery is bounded and eventually requires user`() {
        val manager = G7SessionManager(G7PersistedState(collectorEnabled = true))
        repeat(12) { index -> manager.failure(G7CollectorError("AUTH_$index", true, now + index, "Authentication unavailable")) }
        assertEquals(G7SessionState.USER_INTERVENTION_REQUIRED, manager.state.sessionState)
        assertTrue(manager.state.retryCount <= 10)
    }

    @Test fun `non recoverable collector errors never create an endless retry`() {
        val manager = G7SessionManager(G7PersistedState(collectorEnabled = true, retryCount = 2))
        val state = manager.failure(G7CollectorError("G7-AUTH-211", false, now, "Rebond required"))
        assertEquals(G7SessionState.USER_INTERVENTION_REQUIRED, state.sessionState)
        assertNull(state.nextReconnectEpochMs)
    }

    @Test fun `watch collector selects alternate KEKS auth slot`() {
        assertContentEquals(byteArrayOf(0x00), G7ReceiverSlot.PRIMARY.keksPersistence6())
        assertContentEquals(byteArrayOf(0x02), G7ReceiverSlot.WATCH_ALTERNATE.keksPersistence6())
    }

    @Test fun `high alarm is not duplicated and resolves with hysteresis`() {
        val settings = alarmSettings()
        val first = CgmAlarmEngine.evaluate(reading(181.0), emptyMap(), settings, now)
        val sameAlarm = CgmAlarmEngine.evaluate(reading(185.0, now + 5 * 60_000L), first, settings, now + 5 * 60_000L)
        assertEquals(first[CgmAlarmType.HIGH]?.triggeredAtEpochMs, sameAlarm[CgmAlarmType.HIGH]?.triggeredAtEpochMs)
        val held = CgmAlarmEngine.evaluate(reading(176.0, now + 10 * 60_000L), sameAlarm, settings, now + 10 * 60_000L)
        assertEquals(CgmAlarmState.ACTIVE, held[CgmAlarmType.HIGH]?.state)
        val resolved = CgmAlarmEngine.evaluate(reading(174.0, now + 15 * 60_000L), held, settings, now + 15 * 60_000L)
        assertEquals(CgmAlarmState.RESOLVED, resolved[CgmAlarmType.HIGH]?.state)
    }

    @Test fun `G7 packet parser accepts validated glucose framing and rejects malformed data`() {
        val packet = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0x4e); put(7)
            putInt(1234); putShort(17); putShort(42); putShort(20)
            putShort(112); put(0x06); put(12); putShort(118); put(0)
        }.array()
        val reading = G7GlucosePacketParser().parse(packet, G7Sensor("sensor", "session"), now)
        assertEquals(112.0, reading.glucoseMgDl)
        assertEquals(1.2, reading.trendRateMgDlPerMinute)
        assertEquals(118.0, reading.predictedMgDl)
        assertEquals(now - 20_000L, reading.sensorTimestampEpochMs)
        assertEquals(now - 1_234_000L, reading.sensorStartEpochMs)
        assertEquals(reading.sensorStartEpochMs!! + 10L * 24L * 60L * 60_000L, reading.sensorEndEpochMs)
        assertEquals(reading.sensorEndEpochMs!! + 12L * 60L * 60_000L, reading.graceEndEpochMs)
        assertEquals(7, reading.protocolStatusCode)
        assertEquals(6, reading.calibrationStateCode)
        assertEquals(42, reading.reservedField)
        assertFailsWith<IllegalArgumentException> { G7GlucosePacketParser().parse(byteArrayOf(1), G7Sensor("sensor"), now) }
    }

    @Test fun `setup accepts manual pin and GS1 applicator barcode`() {
        assertEquals("1234", G7SetupParser.parse("1234")?.pairingCode)
        val barcode = "0100386270000000\u001d2409876\u001d21SERIAL"
        val parsed = G7SetupParser.parse(barcode)
        assertEquals("9876", parsed?.pairingCode)
        assertEquals("SERIAL", parsed?.sensorSerial)
        assertNull(G7SetupParser.parse("12"))
    }

    @Test fun `trend mapping uses shared model`() {
        assertEquals(Trend.DOUBLE_UP, CgmTrendMapper.fromRate(3.1))
        assertEquals(Trend.FLAT, CgmTrendMapper.fromRate(0.2))
    }

    private fun alarmSettings() = CgmAlarmSettings(
        veryHighThreshold = 250.0,
        highThreshold = 180.0,
        lowThreshold = 70.0,
        veryLowThreshold = 55.0,
        rapidRiseThreshold = 3.0,
        rapidFallThreshold = 3.0,
        signalLossMinutes = 15,
    )
}
