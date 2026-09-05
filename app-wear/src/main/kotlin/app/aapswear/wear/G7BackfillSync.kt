package app.aapswear.wear

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingOrigin
import app.aapswear.g7.CgmReadingRepository
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.g7.G7ReadingSyncManager
import app.aapswear.g7.G7SyncDispatch
import app.aapswear.g7.G7WatchSyncTransport
import app.aapswear.model.DataSourceId
import app.aapswear.model.Trend
import app.aapswear.protocol.G7ReadingAck
import app.aapswear.protocol.G7ReadingBatch
import app.aapswear.protocol.WearProtocol
import com.google.android.gms.wearable.Wearable
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

/** Consumer-only bridge; it never talks to a sensor or initiates sensor history. */
internal object G7BackfillSync {
    suspend fun sendPending(context: Context, targetNodeId: String? = null): G7SyncDispatch? =
        null

    suspend fun acknowledge(context: Context, ack: G7ReadingAck): Int =
        G7ReadingSyncManager(
            ProviderG7ReadingRepository(context, ack.batchId),
            WearDataLayerG7SyncTransport(context, null),
        ).acknowledge(ack.acknowledgedIds)
}

private class WearDataLayerG7SyncTransport(
    private val context: Context,
    private val targetNodeId: String?,
) : G7WatchSyncTransport {
    override suspend fun sendReadings(readings: List<CgmReading>): G7SyncDispatch {
        val batchId = UUID.randomUUID().toString()
        val batch = G7ReadingBatch(batchId = batchId, readings = readings, sentAtEpochMs = System.currentTimeMillis())
        val nodes = targetNodeId?.let(::listOf)
            ?: Wearable.getNodeClient(context).connectedNodes.await().map { it.id }
        check(nodes.isNotEmpty()) { "No connected Mobile node" }
        val payload = WearProtocol.encodeG7ReadingBatch(batch)
        nodes.forEach { nodeId ->
            Wearable.getMessageClient(context)
                .sendMessage(nodeId, WearProtocol.G7_READING_BATCH_PATH, payload)
                .await()
        }
        return G7SyncDispatch(batchId, readings.map(CgmReading::id).toSet())
    }
}

private class ProviderG7ReadingRepository(
    private val context: Context,
    private val acknowledgementBatchId: String? = null,
) : CgmReadingRepository {
    private val mutableLatest = MutableStateFlow<CgmReading?>(null)
    override val latestReading: StateFlow<CgmReading?> = mutableLatest

    override suspend fun insert(reading: CgmReading): Boolean = false
    override suspend fun getLatest(): CgmReading? = query("latest", 1).firstOrNull()
    override suspend fun getPrevious(): CgmReading? = query("readings", 2).getOrNull(1)
    override suspend fun getRecent(sinceEpochMs: Long): List<CgmReading> = query("readings", 300).filter { it.timestampEpochMs >= sinceEpochMs }
    override suspend fun getRange(fromEpochMs: Long, toEpochMs: Long): List<CgmReading> = query("readings", 300).filter { it.timestampEpochMs in fromEpochMs..toEpochMs }
    override suspend fun getUnsynced(limit: Int): List<CgmReading> = query("unsynced", limit)

    override suspend fun markSynced(ids: Set<String>) {
        if (ids.isEmpty()) return
        context.sendBroadcast(
            Intent("app.aapswear.g7watch.ACK_SYNC")
                .setComponent(ComponentName("app.aapswear.g7watch", "app.aapswear.g7watch.G7SyncControlReceiver"))
                .putExtra("batch_id", acknowledgementBatchId)
                .putStringArrayListExtra("reading_ids", ArrayList(ids)),
            "app.aapswear.g7watch.permission.CONFIGURE_G7",
        )
    }

    private fun query(path: String, limit: Int): List<CgmReading> = runCatching {
        context.contentResolver.query(Uri.parse("content://app.aapswear.g7watch.readings/$path"), null, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext() && size < limit) add(CgmReading(
                    id = cursor.string("id"), source = DataSourceId.DEXCOM_G7_WATCH,
                    sensorId = cursor.string("sensor_id"), sessionId = cursor.string("session_id"),
                    glucoseMgDl = cursor.getDouble(cursor.getColumnIndexOrThrow("glucose")),
                    timestampEpochMs = cursor.getLong(cursor.getColumnIndexOrThrow("measured_at")),
                    receivedAtEpochMs = cursor.getLong(cursor.getColumnIndexOrThrow("received_at")),
                    deltaMgDl = cursor.doubleOrNull("delta"),
                    trend = runCatching { Trend.valueOf(cursor.string("trend")) }.getOrDefault(Trend.UNKNOWN),
                    trendRateMgDlPerMinute = cursor.doubleOrNull("trend_rate"), predictedMgDl = cursor.doubleOrNull("predicted"),
                    sensorAgeSeconds = cursor.longOrNull("sensor_age"),
                    status = runCatching { CgmReadingStatus.valueOf(cursor.string("status")) }.getOrDefault(CgmReadingStatus.INVALID),
                    sequenceNumber = cursor.longOrNull("sequence_number"), displayOnly = cursor.intOrNull("display_only") == 1,
                    rawSourceTimestamp = cursor.longOrNull("sensor_clock"), sensorStartEpochMs = cursor.longOrNull("sensor_start"),
                    sensorEndEpochMs = cursor.longOrNull("sensor_end"), graceEndEpochMs = cursor.longOrNull("grace_end"),
                    protocolStatusCode = cursor.intOrNull("protocol_status"), calibrationStateCode = cursor.intOrNull("calibration_state"),
                    reservedField = cursor.intOrNull("reserved_field"),
                    origin = cursor.optionalString("origin")?.let { runCatching { CgmReadingOrigin.valueOf(it) }.getOrNull() } ?: CgmReadingOrigin.LIVE,
                ))
            }
        }.orEmpty()
    }.getOrDefault(emptyList())

    private fun android.database.Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))
    private fun android.database.Cursor.optionalString(name: String): String? = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let(::getString)
    private fun android.database.Cursor.doubleOrNull(name: String): Double? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getDouble(it) }
    private fun android.database.Cursor.longOrNull(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
    private fun android.database.Cursor.intOrNull(name: String): Int? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getInt(it) }
}
