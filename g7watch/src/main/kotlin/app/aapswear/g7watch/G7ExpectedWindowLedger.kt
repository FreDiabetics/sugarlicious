package app.aapswear.g7watch

import android.content.Context
import app.aapswear.g7.CollectorCycleClassification
import app.aapswear.g7.CollectorAlarmKind
import app.aapswear.g7.CollectorExpectedWindow
import app.aapswear.g7.CollectorHardwareMetrics
import app.aapswear.g7.CollectorWindowTerminalState
import app.aapswear.g7.DirectConnectResult
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.math.ceil
import java.util.UUID

internal class G7ExpectedWindowLedger(context: Context) {
    private val app = context.applicationContext
    private val preferences = app
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val serializer = ListSerializer(CollectorExpectedWindow.serializer())

    fun create(expectedAt: Long, primaryAlarmScheduledAt: Long, alarmKind: CollectorAlarmKind = CollectorAlarmKind.NONE): CollectorExpectedWindow = synchronized(lock) {
        val sensor = G7SensorStateStore(app).read().sensor
        val sessionId = sensor?.sessionId ?: sensor?.sensorId
        val id = expectedWindowId(sensor?.sensorId, sessionId, expectedAt)
        val current = load().firstOrNull { it.expectedWindowId == id }
        val window = (current ?: CollectorExpectedWindow(id, expectedAt)).copy(
            windowCreatedAt = current?.windowCreatedAt ?: System.currentTimeMillis(),
            primaryAlarmScheduledAt = primaryAlarmScheduledAt,
            sensorId = current?.sensorId ?: sensor?.sensorId,
            sessionId = current?.sessionId ?: sessionId,
            alarmKind = alarmKind,
            bootId = current?.bootId ?: bootId(),
        )
        saveUpsert(window)
        window
    }

    fun markPrimaryTriggered(id: String?, at: Long) = update(id) { it.copy(primaryAlarmTriggeredAt = at, alarmDeliveredAt = at, receiverReachedAt = at) }
    fun markServiceRequested(id: String?, at: Long) = update(id) { it.copy(serviceRequestedAt = at) }
    fun markCycleStarted(id: String?, at: Long, attemptId: Long, wakeLockAt: Long?) = update(id) {
        it.copy(cycleStartedAt = at, serviceStartedAt = at, wakeLockAcquiredAt = wakeLockAt, processId = Process.myPid(), processInstanceId = G7ProcessInstance.id, processUptimeMs = SystemClock.elapsedRealtime(), attemptId = attemptId)
    }
    fun markAdvertisement(id: String?, at: Long) = update(id) { it.copy(advertisementSeenAt = at) }
    fun markFallbackScan(id: String?, advertisementSeenAt: Long?) = update(id) {
        it.copy(fallbackScanUsed = true, advertisementSeenAt = advertisementSeenAt ?: it.advertisementSeenAt)
    }
    fun markGattStarted(id: String?, at: Long, generation: Long) = update(id) {
        it.copy(gattStartedAt = it.gattStartedAt ?: at, gattGeneration = generation, gattAttempts = it.gattAttempts + 1)
    }
    fun markGattResult(id: String?, result: DirectConnectResult) = update(id) {
        it.copy(
            gattResult = result,
            gatt133Count = it.gatt133Count + if (result == DirectConnectResult.STATUS_133) 1 else 0,
            noCallbackCount = it.noCallbackCount + if (result == DirectConnectResult.NO_CALLBACK) 1 else 0,
        )
    }
    fun markReading(id: String?, at: Long) = update(id) {
        it.copy(readingReceivedAt = at, finalResult = CollectorCycleClassification.SUCCESS_FRESH, recoveryRequired = false, terminalState = CollectorWindowTerminalState.SUCCESS_FRESH, terminalReason = "validated live reading stored", completedAt = at)
    }
    fun markFinal(id: String?, result: CollectorCycleClassification, recoveryRequired: Boolean, reason: String? = null) = update(id) {
        it.copy(finalResult = result, recoveryRequired = recoveryRequired, terminalState = result.toTerminalState(), terminalReason = reason ?: result.name, completedAt = System.currentTimeMillis())
    }
    fun markWatchdogScheduled(id: String?, at: Long) = update(id) { it.copy(watchdogScheduledAt = at) }
    fun markWatchdogTriggered(id: String?, at: Long) = update(id) { it.copy(watchdogTriggeredAt = at) }

    fun markNextLiveAndBackfill(
        sensorId: String,
        sessionId: String,
        liveMeasuredAt: Long,
        liveReceivedAt: Long,
        committedAt: Long,
        requestedAt: Long?,
        responseAt: Long?,
        inserted: List<Long>,
    ) = synchronized(lock) {
        val recovered = inserted.toSet()
        val updated = load().map { window ->
            if (
                window.sensorId != sensorId || window.sessionId != sessionId ||
                window.recoveryRequired.not() || window.expectedAt >= liveMeasuredAt
            ) return@map window
            val recoveredAt = recovered.minByOrNull { kotlin.math.abs(it - window.expectedAt) }
                ?.takeIf { kotlin.math.abs(it - window.expectedAt) <= G7_READING_IDENTITY_TOLERANCE_MS }
            window.copy(
                nextLiveMeasuredAt = window.nextLiveMeasuredAt ?: liveMeasuredAt,
                nextLiveReceivedAt = window.nextLiveReceivedAt ?: liveReceivedAt,
                liveCommittedAt = window.liveCommittedAt ?: committedAt,
                backfillRequestedAt = window.backfillRequestedAt ?: requestedAt,
                backfillResponseAt = window.backfillResponseAt ?: responseAt,
                backfillInsertedAt = if (recoveredAt != null) committedAt else window.backfillInsertedAt,
                recoveredMeasuredAt = recoveredAt ?: window.recoveredMeasuredAt,
                terminalState = if (recoveredAt != null) CollectorWindowTerminalState.SUCCESS_BACKFILL_ONLY else window.terminalState,
                recoveryRequired = if (recoveredAt != null) false else window.recoveryRequired,
            )
        }
        saveAll(updated)
    }

    fun markProcessInterrupted(id: String?, at: Long) = update(id) {
        if (it.terminalState != null) it else it.copy(
            terminalState = CollectorWindowTerminalState.PROCESS_INTERRUPTED,
            terminalReason = "process restarted before terminal state",
            finalResult = CollectorCycleClassification.PROCESS_INTERRUPTED,
            recoveryRequired = true,
            gapDetectedAt = it.gapDetectedAt ?: at,
            completedAt = at,
        )
    }

    fun reconstructMissed(
        sensorId: String,
        sessionId: String,
        fromExpectedAt: Long,
        untilExclusive: Long,
        sensorStartAt: Long?,
        sensorEndAt: Long?,
        nowEpochMs: Long,
    ): Int = synchronized(lock) {
        val slots = missingExpectedSlots(fromExpectedAt, untilExclusive, sensorStartAt, sensorEndAt)
        var inserted = 0
        slots.forEach { expectedAt ->
            val id = expectedWindowId(sensorId, sessionId, expectedAt)
            if (load().none { it.expectedWindowId == id }) {
                saveUpsert(
                    CollectorExpectedWindow(
                        expectedWindowId = id,
                        expectedAt = expectedAt,
                        windowCreatedAt = nowEpochMs,
                        sensorId = sensorId,
                        sessionId = sessionId,
                        bootId = bootId(),
                        terminalState = CollectorWindowTerminalState.MISSED_WINDOW,
                        terminalReason = "reconstructed after lifecycle interruption",
                        finalResult = CollectorCycleClassification.MISSED_SENSOR_WINDOW,
                        recoveryRequired = true,
                        gapDetectedAt = nowEpochMs,
                        completedAt = nowEpochMs,
                    ),
                )
                inserted += 1
            }
        }
        inserted
    }

    fun window(id: String?): CollectorExpectedWindow? = synchronized(lock) {
        id?.let { value -> load().firstOrNull { it.expectedWindowId == value } }
    }

    fun snapshot(): List<CollectorExpectedWindow> = synchronized(lock) { load().sortedBy { it.expectedAt } }
    fun metrics(): CollectorHardwareMetrics = calculateG7HardwareMetrics(snapshot())

    private fun update(id: String?, transform: (CollectorExpectedWindow) -> CollectorExpectedWindow) = synchronized(lock) {
        val value = id ?: return@synchronized
        val existing = load().firstOrNull { it.expectedWindowId == value } ?: return@synchronized
        saveUpsert(transform(existing))
    }

    private fun saveUpsert(window: CollectorExpectedWindow) {
        val values = (load() + window)
            .associateBy(CollectorExpectedWindow::expectedWindowId)
            .values.sortedBy(CollectorExpectedWindow::expectedAt)
            .takeLast(MAX_WINDOWS)
        saveAll(values)
    }

    private fun saveAll(values: List<CollectorExpectedWindow>) {
        preferences.edit().putString(
            KEY_WINDOWS,
            json.encodeToString(serializer, values.sortedBy { it.expectedAt }.takeLast(MAX_WINDOWS)),
        ).apply()
    }

    private fun load(): List<CollectorExpectedWindow> = preferences.getString(KEY_WINDOWS, null)
        ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
        .orEmpty()

    private fun bootId(): String = runCatching {
        Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT).toString()
    }.getOrDefault("unknown")

    private companion object {
        const val PREFERENCES = "g7_expected_window_ledger"
        const val KEY_WINDOWS = "windows_v1"
        const val MAX_WINDOWS = 2_304
        val lock = Any()
    }
}

private fun CollectorCycleClassification.toTerminalState(): CollectorWindowTerminalState = when (this) {
    CollectorCycleClassification.SUCCESS_FRESH, CollectorCycleClassification.SUCCESS_AGED -> CollectorWindowTerminalState.SUCCESS_FRESH
    CollectorCycleClassification.SERVICE_START_FAILED -> CollectorWindowTerminalState.SERVICE_START_FAILED
    CollectorCycleClassification.AUTH_FAILED -> CollectorWindowTerminalState.AUTH_FAILED
    CollectorCycleClassification.GATT_NO_CALLBACK, CollectorCycleClassification.GLUCOSE_TIMEOUT -> CollectorWindowTerminalState.NO_CALLBACK
    CollectorCycleClassification.GATT_CONNECT_FAILED, CollectorCycleClassification.DIRECT_CONNECT_FAILED -> CollectorWindowTerminalState.CONNECT_FAILED
    CollectorCycleClassification.FALLBACK_SCAN_FAILED, CollectorCycleClassification.NO_ADVERTISEMENT, CollectorCycleClassification.SCAN_STARTED_LATE -> CollectorWindowTerminalState.SCAN_FAILED
    CollectorCycleClassification.CANCELLED, CollectorCycleClassification.COALESCED -> CollectorWindowTerminalState.CANCELLED
    CollectorCycleClassification.PROCESS_INTERRUPTED -> CollectorWindowTerminalState.PROCESS_INTERRUPTED
    CollectorCycleClassification.MISSED_SENSOR_WINDOW, CollectorCycleClassification.ALARM_LATE -> CollectorWindowTerminalState.MISSED_WINDOW
    else -> CollectorWindowTerminalState.UNKNOWN
}

internal object G7ProcessInstance {
    val id: String = UUID.randomUUID().toString()
    val startedAtEpochMs: Long = System.currentTimeMillis()
}

internal fun expectedWindowId(sensorId: String?, sessionId: String?, expectedAt: Long): String =
    "g7-window-${sensorId ?: "unknown"}-${sessionId ?: "unknown"}-$expectedAt"

internal fun expectedWindowId(expectedAt: Long): String = expectedWindowId(null, null, expectedAt)

internal fun missingExpectedSlots(
    fromExpectedAt: Long,
    untilExclusive: Long,
    sensorStartAt: Long?,
    sensorEndAt: Long?,
    intervalMs: Long = G7_SLOT_INTERVAL_MS,
): List<Long> {
    if (intervalMs <= 0L || fromExpectedAt >= untilExclusive) return emptyList()
    val end = minOf(untilExclusive, sensorEndAt ?: Long.MAX_VALUE)
    if (fromExpectedAt >= end) return emptyList()
    return generateSequence(fromExpectedAt) { previous -> (previous + intervalMs).takeIf { it > previous } }
        .dropWhile { it < (sensorStartAt ?: Long.MIN_VALUE) }
        .takeWhile { it < end }
        .toList()
}

internal const val G7_READING_IDENTITY_TOLERANCE_MS = 60_000L

internal fun calculateG7HardwareMetrics(windows: List<CollectorExpectedWindow>): CollectorHardwareMetrics {
    val ordered = windows.sortedBy(CollectorExpectedWindow::expectedAt)
    val successes = ordered.filter { it.finalResult == CollectorCycleClassification.SUCCESS_FRESH }
    val delays = successes.mapNotNull { window -> window.readingReceivedAt?.minus(window.expectedAt) }
        .map { it.coerceAtLeast(0L) }.sorted()
    val readingTimes = successes.mapNotNull(CollectorExpectedWindow::readingReceivedAt).sorted()
    val gaps = readingTimes.zipWithNext { a, b -> b - a }
    fun percentile(values: List<Long>, fraction: Double): Long? =
        values.takeIf { it.isNotEmpty() }?.get((ceil(values.size * fraction).toInt() - 1).coerceIn(0, values.lastIndex))
    return CollectorHardwareMetrics(
        expectedWindows = ordered.size,
        attemptedWindows = ordered.count { it.cycleStartedAt != null },
        successfulWindows = successes.size,
        missedWindows = ordered.count { it.finalResult == CollectorCycleClassification.MISSED_SENSOR_WINDOW },
        firstAttemptSuccess = successes.count { it.gattAttempts == 1 && it.gattResult == DirectConnectResult.SUCCESS },
        retrySuccess = successes.count { it.gattAttempts > 1 },
        gatt133Count = ordered.sumOf(CollectorExpectedWindow::gatt133Count),
        noCallbackCount = ordered.sumOf(CollectorExpectedWindow::noCallbackCount),
        fallbackScanCount = ordered.count(CollectorExpectedWindow::fallbackScanUsed),
        longestReadingGapMs = gaps.maxOrNull(),
        availabilityPercent = if (ordered.isEmpty()) 0.0 else successes.size * 100.0 / ordered.size,
        medianReceiveDelayMs = percentile(delays, 0.5),
        p95ReceiveDelayMs = percentile(delays, 0.95),
    )
}
