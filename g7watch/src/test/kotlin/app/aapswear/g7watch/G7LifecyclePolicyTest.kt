package app.aapswear.g7watch

import android.content.Intent
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CollectorCycleTiming
import app.aapswear.g7.CollectorOwner
import app.aapswear.g7.G7CollectorError
import app.aapswear.g7.G7ConnectionState
import app.aapswear.g7.G7PersistedState
import app.aapswear.g7.G7ProtocolState
import app.aapswear.g7.G7ReconnectScheduler
import app.aapswear.g7.G7Sensor
import app.aapswear.g7.G7SessionState
import app.aapswear.model.DataSourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class G7LifecyclePolicyTest {
    @Test fun `collector repairs missing or expired follow up but preserves future alarm`() {
        val now = 1_000_000L
        assertTrue(needsG7FollowUpRepair(true, null, now))
        assertTrue(needsG7FollowUpRepair(true, now, now))
        assertTrue(needsG7FollowUpRepair(true, now - 1L, now))
        assertFalse(needsG7FollowUpRepair(false, null, now))
        assertFalse(needsG7FollowUpRepair(true, now + 1L, now))
    }
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

    @Test fun `service create preserves a just delivered reconnect envelope for onStartCommand`() {
        assertFalse(shouldRepairG7RuntimeOnServiceCreate(receiverReceivedAtEpochMs = 1_780_000_000_000L))
        assertTrue(shouldRepairG7RuntimeOnServiceCreate(receiverReceivedAtEpochMs = null))
    }

    @Test fun `scheduled service start consumes its alarm envelope before runtime repair`() {
        assertFalse(shouldRepairG7RuntimeOnServiceStart(G7CollectorService.ACTION_RECONNECT))
        assertTrue(shouldRepairG7RuntimeOnServiceStart(G7CollectorService.ACTION_RESTART))
        assertTrue(shouldRepairG7RuntimeOnServiceStart(null))
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

    @Test fun `scheduled cycle stages next five minute safety slot before BLE work`() {
        val expected = 1_000_000L
        val state = G7PersistedState(collectorEnabled = true)

        assertEquals(
            expected + G7ReconnectScheduler.EXPECTED_READING_INTERVAL_MS - G7ReconnectScheduler.PRECONNECT_LEAD_MS,
            nextSafetyReconnectEpoch(
                CollectorCycleTiming(expectedReadingEpoch = expected),
                state,
                nowEpochMs = expected - 30_000L,
            ),
        )
    }

    @Test fun `disabled collector never stages safety reconnect`() {
        assertNull(
            nextSafetyReconnectEpoch(
                CollectorCycleTiming(expectedReadingEpoch = 1_000_000L),
                G7PersistedState(collectorEnabled = false),
                nowEpochMs = 900_000L,
            ),
        )
    }

    @Test fun `direct reconnect wakes close to the expected reading while scan strategy keeps its lead`() {
        val requested = 970_000L
        val expected = 1_000_000L

        assertEquals(
            990_000L,
            alignReconnectRequestToStrategy(requested, expected, directReconnect = true),
        )
        assertEquals(
            requested,
            alignReconnectRequestToStrategy(requested, expected, directReconnect = false),
        )
    }

    @Test fun `failed scheduled cycle retains only the staged next real sensor slot`() {
        val current = CollectorCycleTiming(expectedReadingEpoch = 1_000_000L)
        val safety = CollectorCycleTiming(
            expectedReadingEpoch = 1_300_000L,
            requestedReconnectEpoch = 1_295_000L,
        )

        assertEquals(safety, stagedSafetyCycle(current, safety))
        assertNull(
            stagedSafetyCycle(
                current,
                safety.copy(expectedReadingEpoch = 1_100_000L),
            ),
        )
    }

    @Test fun `rearming direct cycle from the same requested and expected times does not drift`() {
        val expected = 1_000_000L
        val firstRequested = alignReconnectRequestToStrategy(970_000L, expected, directReconnect = true)
        val rearmedRequested = alignReconnectRequestToStrategy(firstRequested, expected, directReconnect = true)

        assertEquals(990_000L, firstRequested)
        assertEquals(firstRequested, rearmedRequested)
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
