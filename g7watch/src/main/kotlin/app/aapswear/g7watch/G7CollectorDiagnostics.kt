package app.aapswear.g7watch

import android.content.Context
import app.aapswear.g7.CollectorCycleClassification
import app.aapswear.g7.CollectorCycleTiming
import app.aapswear.g7.CollectorDiagnosticAttempt
import app.aapswear.g7.CollectorDiagnosticEvent
import app.aapswear.g7.CollectorDiagnosticResult
import app.aapswear.g7.CollectorDiagnosticStage
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingStatus
import java.util.Locale
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal class G7CollectorDiagnosticStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val serializer = ListSerializer(CollectorDiagnosticAttempt.serializer())

    fun begin(
        manual: Boolean,
        restart: Boolean,
        cycle: CollectorCycleTiming? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): CollectorDiagnosticAttempt = synchronized(lock) {
        val attemptId = preferences.getLong(KEY_COUNTER, 0L) + 1L
        val initialEvents = buildList {
            add(
                CollectorDiagnosticEvent(
                    timestampEpochMs = nowEpochMs,
                    attemptId = attemptId,
                    stage = CollectorDiagnosticStage.IDLE,
                    result = CollectorDiagnosticResult.STARTED,
                    message =
                        when {
                            restart -> "Collector-Neustart gestartet"
                            manual -> "Manuelle Sensorsuche gestartet"
                            else -> "Automatischer Collection-Versuch gestartet"
                        },
                ),
            )
            cycle?.receiverReceivedAt?.let {
                add(
                    CollectorDiagnosticEvent(
                        timestampEpochMs = it,
                        attemptId = attemptId,
                        stage = CollectorDiagnosticStage.ALARM_RECEIVED,
                        result = CollectorDiagnosticResult.INFO,
                        message = "Sensorfenster-Alarm empfangen",
                        durationMs = cycle.alarmLatenessMs,
                    ),
                )
            }
            cycle?.serviceOnStartCommandAt?.let {
                add(
                    CollectorDiagnosticEvent(
                        timestampEpochMs = it,
                        attemptId = attemptId,
                        stage = CollectorDiagnosticStage.SERVICE_START,
                        result = CollectorDiagnosticResult.INFO,
                        message = "Foreground-Service für Sensorfenster gestartet",
                        durationMs = cycle.serviceStartLatenessMs,
                    ),
                )
            }
            cycle?.wakeLockAcquiredAt?.let {
                add(
                    CollectorDiagnosticEvent(
                        timestampEpochMs = it,
                        attemptId = attemptId,
                        stage = CollectorDiagnosticStage.WAKE_LOCK,
                        result = CollectorDiagnosticResult.INFO,
                        message = "Begrenzter Cycle-WakeLock übernommen",
                    ),
                )
            }
        }.sortedBy(CollectorDiagnosticEvent::timestampEpochMs)

        val attempt =
            CollectorDiagnosticAttempt(
                attemptId = attemptId,
                startedAtEpochMs = nowEpochMs,
                manual = manual,
                restart = restart,
                events = initialEvents,
                cycle = cycle,
            )
        save((load() + attempt).takeLast(MAX_ATTEMPTS), attemptId)
        attempt
    }

    fun record(
        attemptId: Long,
        stage: CollectorDiagnosticStage,
        result: CollectorDiagnosticResult = CollectorDiagnosticResult.INFO,
        message: String,
        errorCode: String? = null,
        sensorId: String? = null,
        sequence: Long? = null,
        durationMs: Long? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) = synchronized(lock) {
        val attempts = load().toMutableList()
        val index = attempts.indexOfFirst { it.attemptId == attemptId }
        if (index < 0) return@synchronized
        val current = attempts[index]
        val event =
            CollectorDiagnosticEvent(
                timestampEpochMs = nowEpochMs,
                attemptId = attemptId,
                stage = stage,
                result = result,
                message = sanitizeDiagnosticText(message),
                errorCode = errorCode?.take(40),
                sensorId = sensorId?.take(80),
                sequence = sequence,
                durationMs = durationMs,
            )
        val terminal = stage == CollectorDiagnosticStage.COMPLETE || stage == CollectorDiagnosticStage.ERROR
        attempts[index] =
            current.copy(
                completedAtEpochMs = if (terminal) nowEpochMs else current.completedAtEpochMs,
                result = if (terminal) result else current.result,
                summary = if (terminal) event.message else current.summary,
                events = (current.events + event).takeLast(MAX_EVENTS_PER_ATTEMPT),
                cycle = if (terminal) current.cycle?.copy(cycleEndedAt = nowEpochMs) else current.cycle,
            )
        save(attempts.takeLast(MAX_ATTEMPTS), preferences.getLong(KEY_COUNTER, attemptId))
    }

    fun updateCycle(
        attemptId: Long,
        transform: (CollectorCycleTiming) -> CollectorCycleTiming,
    ) = synchronized(lock) {
        val attempts = load().toMutableList()
        val index = attempts.indexOfFirst { it.attemptId == attemptId }
        if (index < 0) return@synchronized
        val current = attempts[index]
        attempts[index] = current.copy(cycle = transform(current.cycle ?: CollectorCycleTiming()))
        save(attempts.takeLast(MAX_ATTEMPTS), preferences.getLong(KEY_COUNTER, attemptId))
    }

    fun setClassification(
        attemptId: Long,
        classification: CollectorCycleClassification,
    ) = synchronized(lock) {
        val attempts = load().toMutableList()
        val index = attempts.indexOfFirst { it.attemptId == attemptId }
        if (index < 0) return@synchronized
        attempts[index] = attempts[index].copy(classification = classification)
        save(attempts.takeLast(MAX_ATTEMPTS), preferences.getLong(KEY_COUNTER, attemptId))
    }

    /** Persisted before AlarmManager scheduling so process death cannot erase the expected slot. */
    fun stageScheduledCycle(cycle: CollectorCycleTiming) = synchronized(lock) {
        preferences.edit()
            .putString(KEY_PENDING_CYCLE, json.encodeToString(CollectorCycleTiming.serializer(), cycle))
            .apply()
    }

    /** Stamps the real BroadcastReceiver delivery time before the FGS handoff starts. */
    fun markScheduledAlarmReceived(nowEpochMs: Long): CollectorCycleTiming? = synchronized(lock) {
        val pending = loadPendingCycle() ?: return@synchronized null
        val updated = pending.copy(
            alarmTriggeredAt = nowEpochMs,
            receiverReceivedAt = nowEpochMs,
        )
        preferences.edit()
            .putString(KEY_PENDING_CYCLE, json.encodeToString(CollectorCycleTiming.serializer(), updated))
            .apply()
        updated
    }

    /** Transfers one scheduled slot into the service attempt and clears only the pending envelope. */
    fun consumeScheduledCycle(serviceStartEpochMs: Long): CollectorCycleTiming? = synchronized(lock) {
        val pending = loadPendingCycle() ?: return@synchronized null
        preferences.edit().remove(KEY_PENDING_CYCLE).apply()
        pending.copy(serviceOnStartCommandAt = serviceStartEpochMs)
    }

    fun pendingScheduledCycle(): CollectorCycleTiming? = synchronized(lock) { loadPendingCycle() }

    fun snapshot(): List<CollectorDiagnosticAttempt> = synchronized(lock) {
        load().sortedByDescending(CollectorDiagnosticAttempt::attemptId)
    }

    fun attemptsBetween(fromEpochMs: Long, toEpochMs: Long): List<CollectorDiagnosticAttempt> =
        snapshot()
            .filter { attempt ->
                attempt.startedAtEpochMs <= toEpochMs &&
                    (attempt.completedAtEpochMs ?: attempt.startedAtEpochMs) >= fromEpochMs
            }
            .sortedBy(CollectorDiagnosticAttempt::startedAtEpochMs)

    private fun load(): List<CollectorDiagnosticAttempt> =
        preferences.getString(KEY_ATTEMPTS, null)
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            .orEmpty()

    private fun loadPendingCycle(): CollectorCycleTiming? =
        preferences.getString(KEY_PENDING_CYCLE, null)
            ?.let { runCatching { json.decodeFromString(CollectorCycleTiming.serializer(), it) }.getOrNull() }

    private fun save(attempts: List<CollectorDiagnosticAttempt>, counter: Long) {
        preferences.edit()
            .putLong(KEY_COUNTER, counter)
            .putString(KEY_ATTEMPTS, json.encodeToString(serializer, attempts))
            .apply()
    }

    private companion object {
        const val PREFERENCES = "g7_collector_attempts"
        const val KEY_COUNTER = "attempt_counter"
        const val KEY_ATTEMPTS = "attempts_v1"
        const val KEY_PENDING_CYCLE = "pending_cycle_v2"
        const val MAX_ATTEMPTS = 50
        const val MAX_EVENTS_PER_ATTEMPT = 100
        val lock = Any()
    }
}

internal fun classifyG7CycleFailure(
    errorCode: String?,
    cycle: CollectorCycleTiming?,
): CollectorCycleClassification = when {
    errorCode == "G7-BLE-107" -> CollectorCycleClassification.NO_ADVERTISEMENT
    errorCode == "G7-GATT-133" || errorCode?.startsWith("G7-GATT-") == true -> CollectorCycleClassification.GATT_CONNECT_FAILED
    errorCode?.startsWith("G7-AUTH-") == true || errorCode?.startsWith("AUTH") == true -> CollectorCycleClassification.AUTH_FAILED
    errorCode == "G7-BLE-111" -> CollectorCycleClassification.GLUCOSE_TIMEOUT
    errorCode == "G7-DATA-301" -> CollectorCycleClassification.INVALID_PACKET
    cycle?.scanStartLatenessMs?.let { it > SCAN_LATE_THRESHOLD_MS } == true -> CollectorCycleClassification.SCAN_STARTED_LATE
    cycle?.alarmLatenessMs?.let { it > ALARM_LATE_THRESHOLD_MS } == true -> CollectorCycleClassification.ALARM_LATE
    else -> CollectorCycleClassification.GATT_CONNECT_FAILED
}

internal fun isFreshG7CycleReading(
    reading: CgmReading,
    nowEpochMs: Long,
): Boolean {
    if (reading.status != CgmReadingStatus.VALID) return false
    val measurementAgeMs = (nowEpochMs - reading.timestampEpochMs).coerceAtLeast(0L)
    val reportedAgeMs = reading.sensorAgeSeconds?.coerceAtLeast(0L)?.times(1_000L)
    return measurementAgeMs <= FRESH_CYCLE_MAX_AGE_MS &&
        (reportedAgeMs == null || reportedAgeMs <= FRESH_CYCLE_MAX_AGE_MS)
}

internal data class G7ReadingHistorySummary(
    val count: Int,
    val todayCount: Int,
    val oldestEpochMs: Long?,
    val latestEpochMs: Long?,
    val missedExpectedWindows: Int,
)

internal fun summarizeG7Readings(
    readings: List<CgmReading>,
    startOfDayEpochMs: Long,
): G7ReadingHistorySummary {
    val ordered =
        readings
            .asSequence()
            .filter {
                it.status == CgmReadingStatus.VALID &&
                    it.glucoseMgDl.isFinite() &&
                    it.glucoseMgDl in 20.0..1_000.0
            }
            .sortedBy(CgmReading::timestampEpochMs)
            .toList()
    val missed =
        ordered.groupBy { it.sensorId to it.sessionId }.values.sumOf { stream ->
            stream.zipWithNext().sumOf { (before, after) ->
                val interval = after.timestampEpochMs - before.timestampEpochMs
                if (interval <= EXPECTED_INTERVAL_MS + WINDOW_TOLERANCE_MS) {
                    0
                } else {
                    (interval / EXPECTED_INTERVAL_MS - 1L).coerceAtLeast(1L).toInt()
                }
            }
        }
    return G7ReadingHistorySummary(
        count = ordered.size,
        todayCount = ordered.count { it.timestampEpochMs >= startOfDayEpochMs },
        oldestEpochMs = ordered.firstOrNull()?.timestampEpochMs,
        latestEpochMs = ordered.lastOrNull()?.timestampEpochMs,
        missedExpectedWindows = missed,
    )
}

internal fun maskBluetoothAddress(address: String?): String {
    if (address.isNullOrBlank()) return "—"
    val parts = address.uppercase(Locale.US).split(':')
    return if (parts.size == 6) "••:••:••:••:${parts[4]}:${parts[5]}" else "••••${address.takeLast(4)}"
}

internal fun sanitizeDiagnosticText(value: String): String =
    value
        .replace(
            Regex("(?i)\\b(shared[_ -]?key|pairing[_ -]?secret|auth(?:entication)?[_ -]?(?:key|credential)|pairing[_ -]?code|sensor[_ -]?code)\\s*[:=]\\s*\\S+"),
        ) { match ->
            "${match.groupValues[1]}=[REDACTED]"
        }
        .replace(Regex("(?i)(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")) { matchBluetooth ->
            maskBluetoothAddress(matchBluetooth.value)
        }
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .trim()
        .take(240)

internal const val FRESH_CYCLE_MAX_AGE_MS = 7L * 60_000L
internal const val ALARM_LATE_THRESHOLD_MS = 15_000L
internal const val SCAN_LATE_THRESHOLD_MS = 20_000L
private const val EXPECTED_INTERVAL_MS = 5L * 60_000L
private const val WINDOW_TOLERANCE_MS = 90_000L
