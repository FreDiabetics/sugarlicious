package app.aapswear.g7

import app.aapswear.model.DataSourceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class G7ReadingSyncManagerTest {
    @Test
    fun `pending readings remain unsynced until mobile acknowledgement`() = runBlocking {
        val readings = listOf(reading("one"), reading("two"))
        val repository = FakeRepository(readings)
        val transport = RecordingTransport()
        val manager = G7ReadingSyncManager(repository, transport)

        val dispatch = manager.sendPending(batchSize = 1)

        assertEquals(setOf("one"), dispatch?.readingIds)
        assertEquals(listOf(listOf(readings.first())), transport.batches)
        assertTrue(repository.syncedIds.isEmpty())

        assertEquals(1, manager.acknowledge(dispatch!!.readingIds))
        assertEquals(setOf("one"), repository.syncedIds)
        assertEquals(setOf("two"), manager.sendPending(batchSize = 1)?.readingIds)
    }

    @Test
    fun `empty queue and empty acknowledgement are no ops`() = runBlocking {
        val repository = FakeRepository(emptyList())
        val manager = G7ReadingSyncManager(repository, RecordingTransport())

        assertNull(manager.sendPending())
        assertEquals(0, manager.acknowledge(emptySet()))
        assertTrue(repository.syncedIds.isEmpty())
    }

    private fun reading(id: String) =
        CgmReading(
            id = id,
            source = DataSourceId.DEXCOM_G7_WATCH,
            sensorId = "sensor",
            sessionId = "session",
            glucoseMgDl = 120.0,
            timestampEpochMs = 1_000L,
            receivedAtEpochMs = 2_000L,
        )

    private class FakeRepository(private val readings: List<CgmReading>) : CgmReadingRepository {
        private val latest = MutableStateFlow(readings.lastOrNull())
        override val latestReading: StateFlow<CgmReading?> = latest
        val syncedIds = linkedSetOf<String>()

        override suspend fun insert(reading: CgmReading): Boolean = false
        override suspend fun getLatest(): CgmReading? = readings.lastOrNull()
        override suspend fun getPrevious(): CgmReading? = readings.getOrNull(readings.lastIndex - 1)
        override suspend fun getRecent(sinceEpochMs: Long): List<CgmReading> = readings
        override suspend fun getRange(fromEpochMs: Long, toEpochMs: Long): List<CgmReading> = readings
        override suspend fun getUnsynced(limit: Int): List<CgmReading> =
            readings.filterNot { it.id in syncedIds }.take(limit)

        override suspend fun markSynced(ids: Set<String>) {
            syncedIds += ids
        }
    }

    private class RecordingTransport : G7WatchSyncTransport {
        val batches = mutableListOf<List<CgmReading>>()

        override suspend fun sendReadings(readings: List<CgmReading>): G7SyncDispatch {
            batches += readings
            return G7SyncDispatch("batch-${batches.size}", readings.map(CgmReading::id).toSet())
        }
    }
}
