package app.aapswear.g7watch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.g7.G7PersistedState
import app.aapswear.g7.G7Sensor
import app.aapswear.model.DataSourceId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class G7SensorDetachPolicyTest {
    @Test fun `takeover cleanup removes existing bond without permanently excluding its target`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val target = "AA:BB:CC:DD:EE:11"
        val alreadyRetired = "AA:BB:CC:DD:EE:12"
        G7RetiredSensors(context).retire(setOf(alreadyRetired))
        G7SensorStateStore(context).save(G7PersistedState(sensor = G7Sensor("transfer", "transfer", deviceAddress = target)))
        val result =
            unlinkG7Sensor(
                context,
                G7BondRemover { _, _ -> G7BondRemovalStatus.REMOVED },
                bondedAddresses = { setOf(target) },
                permanentlyRetire = false,
            )
        assertTrue(result.detached)
        assertFalse(G7RetiredSensors(context).contains(target))
        assertTrue(G7RetiredSensors(context).contains(alreadyRetired))
    }

    @Test fun `fully detached sensor stays excluded across store recreation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val address = "AA:BB:CC:DD:EE:01"
        G7SensorStateStore(context).save(G7PersistedState(sensor = G7Sensor("old", "old", deviceAddress = address), collectorEnabled = true))
        G7CollectorDiagnosticStore(context).stageScheduledCycle(app.aapswear.g7.CollectorCycleTiming(expectedWindowId = "old-window"))
        val removed = mutableListOf<String?>()
        val legacy = "AA:BB:CC:DD:EE:03"
        val result =
            unlinkG7Sensor(
                context,
                G7BondRemover { _, candidate ->
                    removed.add(candidate)
                    G7BondRemovalStatus.REMOVED
                },
                bondedAddresses = { setOf(legacy) },
            )
        assertTrue(result.detached)
        assertTrue(address in removed)
        assertTrue(legacy in removed)
        assertTrue(G7RetiredSensors(context).contains(address.lowercase()))
        assertTrue(G7RetiredSensors(context).contains(legacy))
        assertFalse(G7RetiredSensors(context).contains("AA:BB:CC:DD:EE:02"))
        assertNull(G7SensorStateStore(context).read().sensor)
        assertNull(G7CollectorDiagnosticStore(context).pendingScheduledCycle())
    }

    @Test fun `detach stops only local collector and preserves history without ending sensor`() {
        val semantics = g7DetachSemantics()

        assertTrue(semantics.stopsCollector)
        assertTrue(semantics.clearsLocalSession)
        assertTrue(semantics.preservesHistory)
        assertFalse(semantics.sendsSensorEndCommand)
    }

    @Test fun `unlink removes local sensor and credentials but retains cgm history`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("g7_readings.db")
        val stateStore = G7SensorStateStore(context)
        stateStore.save(G7PersistedState(sensor = G7Sensor("sensor-a", "session-a"), collectorEnabled = true))
        val now = System.currentTimeMillis()
        val reading =
            CgmReading(
                id = "retained",
                source = DataSourceId.DEXCOM_G7_WATCH,
                sensorId = "sensor-a",
                sessionId = "session-a",
                glucoseMgDl = 123.0,
                timestampEpochMs = now - 60_000L,
                receivedAtEpochMs = now,
                status = CgmReadingStatus.VALID,
            )
        G7ReadingDatabase(context).use { database -> assertTrue(runBlocking { database.insert(reading) }) }

        val result = unlinkG7Sensor(context, G7BondRemover { _, _ -> G7BondRemovalStatus.REMOVED })

        assertEquals(G7BondRemovalStatus.REMOVED, result.bondRemovalStatus)
        assertNull(stateStore.read().sensor)
        assertFalse(stateStore.read().collectorEnabled)
        assertNull(G7CredentialStore(context).read())
        G7ReadingDatabase(context).use { database ->
            assertTrue(database.query(limit = 300).any { it.id == "retained" })
        }
    }

    @Test fun `failed bond removal keeps sensor association and credentials for safe retry`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stateStore = G7SensorStateStore(context)
        stateStore.save(
            G7PersistedState(
                sensor = G7Sensor("sensor-retry", "session-retry", deviceAddress = "AA:BB:CC:DD:EE:FF"),
                collectorEnabled = true,
            ),
        )
        val result = unlinkG7Sensor(context, G7BondRemover { _, _ -> G7BondRemovalStatus.FAILED })

        assertEquals(G7BondRemovalStatus.FAILED, result.bondRemovalStatus)
        assertTrue(stateStore.read().sensor != null)
        assertFalse(stateStore.read().collectorEnabled)
        assertFalse(shouldClearG7Association(result.bondRemovalStatus))
    }

    @Test fun `bond removal wait confirms transition and times out without it`() {
        val states = ArrayDeque(listOf(android.bluetooth.BluetoothDevice.BOND_BONDED, android.bluetooth.BluetoothDevice.BOND_NONE))

        assertTrue(awaitG7BondRemoval(timeoutMs = 100L, pollMs = 10L, readBondState = { states.removeFirst() }, sleep = {}))
        assertFalse(
            awaitG7BondRemoval(
                timeoutMs = 20L,
                pollMs = 10L,
                readBondState = { android.bluetooth.BluetoothDevice.BOND_BONDED },
                sleep = {},
            ),
        )
    }

    @Test fun `new sensor cannot replace association while old bond removal failed`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stateStore = G7SensorStateStore(context)
        stateStore.save(
            G7PersistedState(
                sensor = G7Sensor("sensor-old", "session-old", deviceAddress = "AA:BB:CC:DD:EE:FF"),
                collectorEnabled = true,
            ),
        )

        try {
            moveG7SensorToThisWatch(context, "1234", G7BondRemover { _, _ -> G7BondRemovalStatus.FAILED })
            fail("replacement must stop when the previous Bluetooth bond still exists")
        } catch (_: IllegalStateException) {
            assertEquals("sensor-old", stateStore.read().sensor?.sensorId)
        }
    }
}
