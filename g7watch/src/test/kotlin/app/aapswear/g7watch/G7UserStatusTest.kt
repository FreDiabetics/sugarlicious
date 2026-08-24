package app.aapswear.g7watch

import app.aapswear.g7.CgmReading
import app.aapswear.g7.G7CollectorError
import app.aapswear.g7.G7PersistedState
import app.aapswear.g7.G7ProtocolState
import app.aapswear.g7.G7Sensor
import app.aapswear.g7.G7SensorState
import app.aapswear.g7.G7SessionState
import app.aapswear.model.DataSourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class G7UserStatusTest {
    private val now = 2_000_000L
    private val sensor = G7Sensor("sensor", "session", "DXCMZU")

    private fun reading(at: Long) = CgmReading(
        id = "reading-$at",
        source = DataSourceId.DEXCOM_G7_WATCH,
        sensorId = "sensor",
        sessionId = "session",
        glucoseMgDl = 123.0,
        timestampEpochMs = at,
        receivedAtEpochMs = at,
    )

    @Test fun `waiting between healthy readings is explicit normal operation`() {
        val status = deriveG7UserStatus(
            G7PersistedState(
                sensor = sensor,
                collectorEnabled = true,
                protocolState = G7ProtocolState.WAITING_FOR_NEXT_READING,
                sessionState = G7SessionState.WAITING_FOR_NEXT_READING,
                lastReading = reading(now - 5 * 60_000L),
            ),
            credentialsPresent = true,
            nowEpochMs = now,
        )

        assertEquals(G7UserStatusLevel.OK, status.level)
        assertEquals("Verbunden", status.title)
        assertTrue(status.status.contains("Datenfluss in Ordnung"))
        assertTrue(status.action.contains("Kein Eingriff"))
    }

    @Test fun `single missed window remains automatic recovery before sixteen minutes`() {
        val status = deriveG7UserStatus(
            G7PersistedState(
                sensor = sensor,
                collectorEnabled = true,
                protocolState = G7ProtocolState.RECOVERING,
                sessionState = G7SessionState.RECOVERING,
                lastReading = reading(now - 6 * 60_000L),
                lastError = G7CollectorError("G7-BLE-107", true, now, "Kein sendender Dexcom-G7-Sensor gefunden"),
            ),
            credentialsPresent = true,
            nowEpochMs = now,
        )

        assertEquals(G7UserStatusLevel.WORKING, status.level)
        assertEquals("Automatische Wiederverbindung", status.title)
        assertTrue(status.action.contains("Nichts zurücksetzen"))
    }

    @Test fun `sixteen minutes without reading becomes signal loss`() {
        val status = deriveG7UserStatus(
            G7PersistedState(
                sensor = sensor,
                collectorEnabled = true,
                protocolState = G7ProtocolState.RECOVERING,
                sessionState = G7SessionState.RECOVERING,
                lastReading = reading(now - G7_SIGNAL_LOSS_AFTER_MS),
            ),
            credentialsPresent = true,
            nowEpochMs = now,
        )

        assertEquals(G7UserStatusLevel.ATTENTION, status.level)
        assertEquals("Signalverlust", status.title)
    }

    @Test fun `sensor error is surfaced without presenting the last valid value as current`() {
        val status = deriveG7UserStatus(
            G7PersistedState(
                sensor = sensor.copy(state = G7SensorState.ERROR),
                collectorEnabled = true,
                protocolState = G7ProtocolState.WAITING_FOR_NEXT_READING,
                sessionState = G7SessionState.WAITING_FOR_NEXT_READING,
                lastReading = reading(now - 5 * 60_000L),
            ),
            credentialsPresent = true,
            nowEpochMs = now,
        )

        assertEquals(G7UserStatusLevel.ATTENTION, status.level)
        assertEquals("Sensorfehler", status.title)
        assertTrue(status.status.contains("Kein gültiger"))
    }
}
