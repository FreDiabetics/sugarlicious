package app.aapswear.g7watch

import android.content.Context
import app.aapswear.g7.CollectorCycleClassification
import app.aapswear.g7.CollectorExpectedWindow
import app.aapswear.g7.CollectorHardwareMetrics
import app.aapswear.g7.DirectConnectResult
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.math.ceil

internal class G7ExpectedWindowLedger(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val serializer = ListSerializer(CollectorExpectedWindow.serializer())

    fun create(expectedAt: Long, primaryAlarmScheduledAt: Long): CollectorExpectedWindow = synchronized(lock) {
        val id = expectedWindowId(expectedAt)
        val current = load().firstOrNull { it.expectedWindowId == id }
        val window = (current ?: CollectorExpectedWindow(id, expectedAt)).copy(
            primaryAlarmScheduledAt = primaryAlarmScheduledAt,
        )
        saveUpsert(window)
        window
    }

    fun markPrimaryTriggered(id: String?, at: Long) = update(id) { it.copy(primaryAlarmTriggeredAt = at) }
    fun markCycleStarted(id: String?, at: Long) = update(id) { it.copy(cycleStartedAt = at) }
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
        it.copy(readingReceivedAt = at, finalResult = CollectorCycleClassification.SUCCESS_FRESH, recoveryRequired = false)
    }
    fun markFinal(id: String?, result: CollectorCycleClassification, recoveryRequired: Boolean) = update(id) {
        it.copy(finalResult = result, recoveryRequired = recoveryRequired)
    }
    fun markWatchdogScheduled(id: String?, at: Long) = update(id) { it.copy(watchdogScheduledAt = at) }
    fun markWatchdogTriggered(id: String?, at: Long) = update(id) { it.copy(watchdogTriggeredAt = at) }

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
        preferences.edit().putString(KEY_WINDOWS, json.encodeToString(serializer, values)).apply()
    }

    private fun load(): List<CollectorExpectedWindow> = preferences.getString(KEY_WINDOWS, null)
        ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
        .orEmpty()

    private companion object {
        const val PREFERENCES = "g7_expected_window_ledger"
        const val KEY_WINDOWS = "windows_v1"
        const val MAX_WINDOWS = 512
        val lock = Any()
    }
}

internal fun expectedWindowId(expectedAt: Long): String = "g7-window-$expectedAt"

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
