package app.aapswear.g7watch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.g7.G7PersistedState
import app.aapswear.g7.G7Sensor
import app.aapswear.model.DataSourceId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class G7SensorDetachPolicyTest {
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
        val reading = CgmReading(
            id = "retained", source = DataSourceId.DEXCOM_G7_WATCH,
            sensorId = "sensor-a", sessionId = "session-a", glucoseMgDl = 123.0,
            timestampEpochMs = now - 60_000L, receivedAtEpochMs = now, status = CgmReadingStatus.VALID,
        )
        G7ReadingDatabase(context).use { database -> assertTrue(runBlocking { database.insert(reading) }) }

        unlinkG7Sensor(context)

        assertNull(stateStore.read().sensor)
        assertFalse(stateStore.read().collectorEnabled)
        assertNull(G7CredentialStore(context).read())
        G7ReadingDatabase(context).use { database ->
            assertTrue(database.query(limit = 300).any { it.id == "retained" })
        }
    }
}
