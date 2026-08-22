package app.aapswear.g7watch

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingRepository
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.model.DataSourceId
import app.aapswear.model.Trend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class G7ReadingDatabase(context: Context) : SQLiteOpenHelper(context, "g7_readings.db", null, 2), CgmReadingRepository {
    private val appContext = context.applicationContext
    private val mutableLatest = MutableStateFlow<CgmReading?>(null)
    override val latestReading: StateFlow<CgmReading?> = mutableLatest

    init { mutableLatest.value = query(limit = 1).firstOrNull() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE readings (id TEXT PRIMARY KEY, sensor_id TEXT NOT NULL, session_id TEXT NOT NULL, glucose REAL NOT NULL, measured_at INTEGER NOT NULL, received_at INTEGER NOT NULL, delta REAL, trend TEXT NOT NULL, trend_rate REAL, predicted REAL, sensor_age INTEGER, status TEXT NOT NULL, sequence_number INTEGER, display_only INTEGER NOT NULL DEFAULT 0, sensor_clock INTEGER, sensor_start INTEGER, sensor_end INTEGER, grace_end INTEGER, protocol_status INTEGER, calibration_state INTEGER, reserved_field INTEGER, synced INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX readings_measured_at ON readings(measured_at DESC)")
        db.execSQL("CREATE INDEX readings_pending ON readings(synced, measured_at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE readings ADD COLUMN display_only INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE readings ADD COLUMN sensor_clock INTEGER")
            db.execSQL("ALTER TABLE readings ADD COLUMN sensor_start INTEGER")
            db.execSQL("ALTER TABLE readings ADD COLUMN sensor_end INTEGER")
            db.execSQL("ALTER TABLE readings ADD COLUMN grace_end INTEGER")
            db.execSQL("ALTER TABLE readings ADD COLUMN protocol_status INTEGER")
            db.execSQL("ALTER TABLE readings ADD COLUMN calibration_state INTEGER")
            db.execSQL("ALTER TABLE readings ADD COLUMN reserved_field INTEGER")
        }
    }

    override suspend fun insert(reading: CgmReading): Boolean {
        val values = ContentValues().apply {
            put("id", reading.id); put("sensor_id", reading.sensorId); put("session_id", reading.sessionId)
            put("glucose", reading.glucoseMgDl); put("measured_at", reading.timestampEpochMs); put("received_at", reading.receivedAtEpochMs)
            reading.deltaMgDl?.let { put("delta", it) }; put("trend", reading.trend.name); reading.trendRateMgDlPerMinute?.let { put("trend_rate", it) }
            reading.predictedMgDl?.let { put("predicted", it) }; reading.sensorAgeSeconds?.let { put("sensor_age", it) }
            put("status", reading.status.name); reading.sequenceNumber?.let { put("sequence_number", it) }
            put("display_only", if (reading.displayOnly) 1 else 0)
            reading.rawSourceTimestamp?.let { put("sensor_clock", it) }
            reading.sensorStartEpochMs?.let { put("sensor_start", it) }
            reading.sensorEndEpochMs?.let { put("sensor_end", it) }
            reading.graceEndEpochMs?.let { put("grace_end", it) }
            reading.protocolStatusCode?.let { put("protocol_status", it) }
            reading.calibrationStateCode?.let { put("calibration_state", it) }
            reading.reservedField?.let { put("reserved_field", it) }
        }
        val inserted = writableDatabase.insertWithOnConflict("readings", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        if (inserted) {
            prune()
            mutableLatest.value = query(limit = 1).firstOrNull()
            appContext.contentResolver.notifyChange(G7ReadingProvider.CONTENT_URI, null)
            appContext.sendBroadcast(
                Intent(ACTION_G7_READING_UPDATED).setPackage(SUGARLICIOUS_PACKAGE),
                READ_G7_PERMISSION,
            )
            G7CollectorTileService.requestUpdate(appContext)
        }
        return inserted
    }

    private fun prune(nowEpochMs: Long = System.currentTimeMillis()) {
        writableDatabase.delete(
            "readings",
            "measured_at<?",
            arrayOf((nowEpochMs - RETENTION_MS).toString()),
        )
        writableDatabase.execSQL(
            "DELETE FROM readings WHERE id NOT IN (SELECT id FROM readings ORDER BY measured_at DESC LIMIT $MAX_ROWS)",
        )
    }
    override suspend fun getLatest(): CgmReading? = query(limit = 1).firstOrNull()
    suspend fun getLatestValid(): CgmReading? =
        query(
            selection = "status=?",
            args = arrayOf(CgmReadingStatus.VALID.name),
            limit = 1,
        ).firstOrNull()
    override suspend fun getPrevious(): CgmReading? = query(limit = 2).getOrNull(1)
    override suspend fun getRecent(sinceEpochMs: Long): List<CgmReading> = query("measured_at>=?", arrayOf(sinceEpochMs.toString()))
    override suspend fun getRange(fromEpochMs: Long, toEpochMs: Long): List<CgmReading> = query("measured_at BETWEEN ? AND ?", arrayOf(fromEpochMs.toString(), toEpochMs.toString()))
    override suspend fun getUnsynced(limit: Int): List<CgmReading> =
        query(
            selection = "synced=0 AND status=?",
            args = arrayOf(CgmReadingStatus.VALID.name),
            limit = limit,
            ascending = true,
        )
    override suspend fun markSynced(ids: Set<String>) {
        if (ids.isEmpty()) return
        var updated = 0
        writableDatabase.beginTransaction()
        try {
            ids.forEach {
                updated +=
                    writableDatabase.update(
                        "readings",
                        ContentValues().apply { put("synced", 1) },
                        "id=? AND synced=0",
                        arrayOf(it),
                    )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        if (updated > 0) {
            appContext.contentResolver.notifyChange(G7ReadingProvider.CONTENT_URI, null)
            // The Wear bridge reacts only after the transaction committed, so batches larger
            // than the protocol limit continue without racing the acknowledgement write.
            appContext.sendBroadcast(
                Intent(ACTION_G7_READING_UPDATED).setPackage(SUGARLICIOUS_PACKAGE),
                READ_G7_PERMISSION,
            )
        }
    }

    fun query(
        selection: String? = null,
        args: Array<String>? = null,
        limit: Int = 300,
        ascending: Boolean = false,
    ): List<CgmReading> =
        readableDatabase.query(
            "readings",
            null,
            selection,
            args,
            null,
            null,
            if (ascending) "measured_at ASC" else "measured_at DESC",
            limit.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(CgmReading(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")), source = DataSourceId.DEXCOM_G7_WATCH,
                    sensorId = cursor.getString(cursor.getColumnIndexOrThrow("sensor_id")), sessionId = cursor.getString(cursor.getColumnIndexOrThrow("session_id")),
                    glucoseMgDl = cursor.getDouble(cursor.getColumnIndexOrThrow("glucose")), timestampEpochMs = cursor.getLong(cursor.getColumnIndexOrThrow("measured_at")), receivedAtEpochMs = cursor.getLong(cursor.getColumnIndexOrThrow("received_at")),
                    deltaMgDl = cursor.doubleOrNull("delta"), trend = runCatching { Trend.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("trend"))) }.getOrDefault(Trend.UNKNOWN),
                    trendRateMgDlPerMinute = cursor.doubleOrNull("trend_rate"), predictedMgDl = cursor.doubleOrNull("predicted"), sensorAgeSeconds = cursor.longOrNull("sensor_age"),
                    status = runCatching { CgmReadingStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status"))) }.getOrDefault(CgmReadingStatus.INVALID), sequenceNumber = cursor.longOrNull("sequence_number"),
                    displayOnly = cursor.getInt(cursor.getColumnIndexOrThrow("display_only")) != 0,
                    rawSourceTimestamp = cursor.longOrNull("sensor_clock"),
                    sensorStartEpochMs = cursor.longOrNull("sensor_start"),
                    sensorEndEpochMs = cursor.longOrNull("sensor_end"),
                    graceEndEpochMs = cursor.longOrNull("grace_end"),
                    protocolStatusCode = cursor.intOrNull("protocol_status"),
                    calibrationStateCode = cursor.intOrNull("calibration_state"),
                    reservedField = cursor.intOrNull("reserved_field"),
                ))
            }
        }

    private fun android.database.Cursor.doubleOrNull(name: String): Double? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getDouble(it) }
    private fun android.database.Cursor.longOrNull(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
    private fun android.database.Cursor.intOrNull(name: String): Int? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getInt(it) }

    private companion object {
        const val ACTION_G7_READING_UPDATED = "app.aapswear.g7watch.READING_UPDATED"
        const val SUGARLICIOUS_PACKAGE = "app.aapswear"
        const val READ_G7_PERMISSION = "app.aapswear.g7watch.permission.READ_G7_DATA"
        const val RETENTION_MS = 30L * 24L * 60L * 60_000L
        const val MAX_ROWS = 2_000
    }
}
