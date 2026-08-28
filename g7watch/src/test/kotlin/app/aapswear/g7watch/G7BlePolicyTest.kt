package app.aapswear.g7watch

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothDevice
import app.aapswear.g7.G7Sensor
import app.aapswear.g7.G7PersistedState
import app.aapswear.g7.DirectConnectResult
import app.aapswear.g7.CollectorCycleClassification
import app.aapswear.g7.CollectorDiagnosticAttempt
import app.aapswear.g7.CollectorCycleTiming
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class G7BlePolicyTest {
    @Test fun `known candidate without first reading retains initial pairing deadline`() {
        val state = G7PersistedState(
            sensor = G7Sensor("new-sensor", deviceAddress = "AA:BB:CC:DD:EE:FF"),
        )

        assertEquals(
            G7_INITIAL_PAIRING_SCAN_TIMEOUT_MS + 2L * 60_000L,
            collectorAttemptDeadlineMs(state),
        )
    }

    @Test fun `stored key is not replayed when Android bond is absent`() {
        val key = byteArrayOf(1, 2, 3)

        assertEquals(null, usableG7SharedKey(key, BluetoothDevice.BOND_NONE))
        assertTrue(usableG7SharedKey(key, BluetoothDevice.BOND_BONDING)!!.contentEquals(key))
        assertTrue(usableG7SharedKey(key, BluetoothDevice.BOND_BONDED)!!.contentEquals(key))
        assertTrue(usableG7SharedKey(key, null)!!.contentEquals(key))
        assertEquals(null, usableG7SharedKey(null, BluetoothDevice.BOND_NONE))
        assertTrue(shouldResumeG7Pairing(key, BluetoothDevice.BOND_NONE))
        assertFalse(shouldResumeG7Pairing(key, BluetoothDevice.BOND_BONDING))
        assertFalse(shouldResumeG7Pairing(key, BluetoothDevice.BOND_BONDED))
        assertFalse(shouldResumeG7Pairing(null, BluetoothDevice.BOND_NONE))
    }

    @Test fun `authenticated address anchors an addressless sensor change`() {
        val sensor = G7Sensor("new-sensor", sessionId = "new-session")

        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            restoreAuthenticatedG7Address(sensor, "AA:BB:CC:DD:EE:FF").deviceAddress,
        )
    }

    @Test fun `authenticated address never replaces an established sensor address`() {
        val sensor = G7Sensor(
            "active-sensor",
            sessionId = "active-session",
            deviceAddress = "11:22:33:44:55:66",
        )

        assertEquals(
            "11:22:33:44:55:66",
            restoreAuthenticatedG7Address(sensor, "AA:BB:CC:DD:EE:FF").deviceAddress,
        )
    }

    @Test fun `missing authenticated address leaves sensor discovery open`() {
        val sensor = G7Sensor("new-sensor", sessionId = "new-session")

        assertEquals(sensor, restoreAuthenticatedG7Address(sensor, null))
        assertEquals(sensor, restoreAuthenticatedG7Address(sensor, ""))
    }

    @Test fun `bonding waits through asynchronous initial bond none state`() {
        assertEquals(
            G7BondWaitDecision.KEEP_WAITING,
            g7BondWaitDecision(BluetoothDevice.BOND_NONE, observedBonding = false),
        )
        assertEquals(
            G7BondWaitDecision.KEEP_WAITING,
            g7BondWaitDecision(BluetoothDevice.BOND_BONDING, observedBonding = false),
        )
        assertEquals(
            G7BondWaitDecision.BONDED,
            g7BondWaitDecision(BluetoothDevice.BOND_BONDED, observedBonding = true),
        )
    }

    @Test fun `bonding none is terminal only after bonding was observed`() {
        assertEquals(
            G7BondWaitDecision.FAILED,
            g7BondWaitDecision(BluetoothDevice.BOND_NONE, observedBonding = true),
        )
    }

    @Test fun `direct connect callbacks retain actionable platform outcomes`() {
        assertEquals(DirectConnectResult.SUCCESS, classifyDirectConnectCallback(BluetoothGatt.GATT_SUCCESS, android.bluetooth.BluetoothProfile.STATE_CONNECTED))
        assertEquals(DirectConnectResult.STATUS_133, classifyDirectConnectCallback(133, android.bluetooth.BluetoothProfile.STATE_DISCONNECTED))
        assertEquals(DirectConnectResult.STATUS_19, classifyDirectConnectCallback(19, android.bluetooth.BluetoothProfile.STATE_DISCONNECTED))
        assertEquals(DirectConnectResult.DISCONNECTED_EARLY, classifyDirectConnectCallback(BluetoothGatt.GATT_SUCCESS, android.bluetooth.BluetoothProfile.STATE_DISCONNECTED))
        assertEquals(DirectConnectResult.OTHER_STATUS, classifyDirectConnectCallback(8, android.bluetooth.BluetoothProfile.STATE_DISCONNECTED))
    }

    @Test fun `radio degraded mode starts only with third consecutive complete fallback miss`() {
        val attempts = listOf(
            CollectorDiagnosticAttempt(3, 3, classification = CollectorCycleClassification.FALLBACK_SCAN_FAILED),
            CollectorDiagnosticAttempt(2, 2, classification = CollectorCycleClassification.FALLBACK_SCAN_FAILED),
            CollectorDiagnosticAttempt(1, 1, classification = CollectorCycleClassification.SUCCESS_FRESH),
        )
        assertEquals(2, consecutiveRadioFailures(attempts, 4))
        assertEquals(RADIO_DEGRADED_CLUSTER_THRESHOLD, 1 + consecutiveRadioFailures(attempts, 4))
        assertEquals(0, consecutiveRadioFailures(attempts, 2))
    }

    @Test fun `confirmed fallback sensor permits one final bounded status 133 retry`() {
        assertEquals(1, maxGatt133RetriesForCycle(fallbackSensorConfirmed = false))
        assertEquals(2, maxGatt133RetriesForCycle(fallbackSensorConfirmed = true))
    }

    @Test fun `gatt failure after fallback discovery remains part of radio failure cluster`() {
        val attempts = listOf(
            CollectorDiagnosticAttempt(
                attemptId = 3,
                startedAtEpochMs = 3,
                classification = CollectorCycleClassification.GATT_CONNECT_FAILED,
                cycle = CollectorCycleTiming(fallbackScanUsed = true),
            ),
            CollectorDiagnosticAttempt(
                attemptId = 2,
                startedAtEpochMs = 2,
                classification = CollectorCycleClassification.FALLBACK_SCAN_FAILED,
            ),
        )

        assertEquals(2, consecutiveRadioFailures(attempts, currentAttemptId = 4))
    }
    @Test fun `initial pairing keeps scanning for up to thirty minutes`() {
        assertEquals(
            G7_INITIAL_PAIRING_SCAN_TIMEOUT_MS,
            g7ScanTimeoutMs(G7Sensor("new-sensor")),
        )
    }

    @Test fun `known sensor reconnect uses shorter targeted scan`() {
        assertEquals(60_000L, G7_RECONNECT_SCAN_TIMEOUT_MS)
        assertEquals(
            G7_RECONNECT_SCAN_TIMEOUT_MS,
            g7ScanTimeoutMs(G7Sensor("known-sensor", deviceAddress = "AA:BB:CC:DD:EE:FF")),
        )
    }

    @Test fun `known address direct reconnect is default fast path`() {
        assertTrue(shouldUseDirectReconnect(G7ReconnectStrategy.KNOWN_ADDRESS_DIRECT, "AA:BB:CC:DD:EE:FF"))
        assertFalse(shouldUseDirectReconnect(G7ReconnectStrategy.KNOWN_ADDRESS_DIRECT, null))
        assertFalse(shouldUseDirectReconnect(G7ReconnectStrategy.BOUNDED_SCAN, "AA:BB:CC:DD:EE:FF"))
        assertEquals(15_000L, G7_FALLBACK_SCAN_TIMEOUT_MS)
    }

    @Test fun `recoverable direct timeout enters fallback discovery only once`() {
        assertTrue(
            shouldUseFallbackDiscovery(
                G7ReconnectStrategy.KNOWN_ADDRESS_DIRECT,
                "AA:BB:CC:DD:EE:FF",
                fallbackUsed = false,
                recoverable = true,
            ),
        )
        assertFalse(
            shouldUseFallbackDiscovery(
                G7ReconnectStrategy.KNOWN_ADDRESS_DIRECT,
                "AA:BB:CC:DD:EE:FF",
                fallbackUsed = true,
                recoverable = true,
            ),
        )
        assertFalse(
            shouldUseFallbackDiscovery(
                G7ReconnectStrategy.BOUNDED_SCAN,
                "AA:BB:CC:DD:EE:FF",
                fallbackUsed = false,
                recoverable = true,
            ),
        )
    }

    @Test fun `known sensor address rejects a different nearby G7`() {
        assertFalse(knownG7AddressMatches("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66")!!)
        assertTrue(knownG7AddressMatches("AA:BB:CC:DD:EE:FF", "aa:bb:cc:dd:ee:ff")!!)
    }

    @Test fun `missing known address leaves initial candidate selection open`() {
        assertEquals(null, knownG7AddressMatches(null, "11:22:33:44:55:66"))
    }

    @Test fun `non connectable advertisement is never used for GATT`() {
        assertFalse(isConnectableG7Advertisement(false))
        assertTrue(isConnectableG7Advertisement(true))
    }

    @Test fun `successful stale write callback is ignored instead of rejected`() {
        val expected = UUID.fromString("f8083535-849e-531c-c594-30f1f86a4ea5")
        val stale = UUID.fromString("f8083534-849e-531c-c594-30f1f86a4ea5")

        assertEquals(
            G7WriteCallbackDisposition.STALE_SUCCESS,
            classifyG7WriteCallback(expected, stale, BluetoothGatt.GATT_SUCCESS),
        )
    }

    @Test fun `successful callback for expected characteristic completes write`() {
        val expected = UUID.fromString("f8083535-849e-531c-c594-30f1f86a4ea5")

        assertEquals(
            G7WriteCallbackDisposition.EXPECTED_SUCCESS,
            classifyG7WriteCallback(expected, expected, BluetoothGatt.GATT_SUCCESS),
        )
    }

    @Test fun `failed callback for expected characteristic is rejected`() {
        val expected = UUID.fromString("f8083535-849e-531c-c594-30f1f86a4ea5")

        assertEquals(
            G7WriteCallbackDisposition.EXPECTED_FAILURE,
            classifyG7WriteCallback(expected, expected, BluetoothGatt.GATT_FAILURE),
        )
    }
}
