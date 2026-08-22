package app.aapswear.g7watch

import android.content.Intent
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CollectorOwner
import app.aapswear.g7.G7CollectorError
import app.aapswear.g7.G7ConnectionState
import app.aapswear.g7.G7PersistedState
import app.aapswear.g7.G7ProtocolState
import app.aapswear.g7.G7Sensor
import app.aapswear.g7.G7SessionState
import app.aapswear.model.DataSourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class G7LifecyclePolicyTest {
    @Test fun `enabled collector restores after boot`() {
        assertTrue(shouldRestoreG7Collector(Intent.ACTION_BOOT_COMPLETED, collectorEnabled = true))
    }

    @Test fun `enabled collector restores after app update`() {
        assertTrue(shouldRestoreG7Collector(Intent.ACTION_MY_PACKAGE_REPLACED, collectorEnabled = true))
    }

    @Test fun `disabled collector stays disabled after lifecycle broadcasts`() {
        assertFalse(shouldRestoreG7Collector(Intent.ACTION_BOOT_COMPLETED, collectorEnabled = false))
        assertFalse(shouldRestoreG7Collector(Intent.ACTION_MY_PACKAGE_REPLACED, collectorEnabled = false))
    }

    @Test fun `unrelated broadcasts never restore collector`() {
        assertFalse(shouldRestoreG7Collector(Intent.ACTION_SCREEN_ON, collectorEnabled = true))
        assertFalse(shouldRestoreG7Collector(null, collectorEnabled = true))
    }

    @Test fun `enabled collector keeps foreground runtime between sensor cycles`() {
        assertTrue(shouldKeepG7RuntimeForeground(collectorEnabled = true))
        assertFalse(shouldKeepG7RuntimeForeground(collectorEnabled = false))
    }

    @Test fun `source selection cannot enable a user-disabled collector`() {
        assertFalse(
            shouldResumeEnabledCollectorForSourceSignal(
                g7Selected = true,
                collectorEnabled = false,
            ),
        )
    }

    @Test fun `leaving direct source never stops or restarts collector`() {
        assertFalse(
            shouldResumeEnabledCollectorForSourceSignal(
                g7Selected = false,
                collectorEnabled = true,
            ),
        )
    }

    @Test fun `direct source signal may only resume an already enabled collector`() {
        assertTrue(
            shouldResumeEnabledCollectorForSourceSignal(
                g7Selected = true,
                collectorEnabled = true,
            ),
        )
    }

    @Test fun `restart resets only volatile runtime and retains enabled sensor session and history`() {
        val sensor = G7Sensor("sensor", "session", deviceAddress = "AA:BB:CC:DD:EE:FF")
        val reading = CgmReading(
            id = "reading",
            source = DataSourceId.DEXCOM_G7_WATCH,
            sensorId = "sensor",
            sessionId = "session",
            glucoseMgDl = 123.0,
            timestampEpochMs = 1_000L,
            receivedAtEpochMs = 1_001L,
            sequenceNumber = 7L,
        )
        val original = G7PersistedState(
            sensor = sensor,
            collectorEnabled = true,
            collectorOwner = CollectorOwner.WATCH,
            connectionState = G7ConnectionState.CONNECTED,
            protocolState = G7ProtocolState.RECOVERING,
            sessionState = G7SessionState.WAITING_FOR_NEXT_READING,
            lastReading = reading,
            lastSuccessfulConnectionEpochMs = 1_001L,
            nextReconnectEpochMs = 2_000L,
            retryCount = 4,
            lastError = G7CollectorError("G7-BLE-107", true, 1_500L, "recoverable"),
            activeAttemptId = 9L,
            scanStartedAtEpochMs = 1_600L,
            scanTimeoutAtEpochMs = 91_600L,
        )

        val restarted = resetG7RuntimeForRestart(original)

        assertTrue(restarted.collectorEnabled)
        assertEquals(CollectorOwner.WATCH, restarted.collectorOwner)
        assertEquals(sensor, restarted.sensor)
        assertEquals(G7SessionState.WAITING_FOR_NEXT_READING, restarted.sessionState)
        assertEquals(reading, restarted.lastReading)
        assertEquals(1_001L, restarted.lastSuccessfulConnectionEpochMs)
        assertEquals(0, restarted.retryCount)
        assertEquals(null, restarted.nextReconnectEpochMs)
        assertEquals(null, restarted.lastError)
        assertEquals(G7ConnectionState.DISCONNECTED, restarted.connectionState)
        assertEquals(G7ProtocolState.UNINITIALIZED, restarted.protocolState)
    }
}
