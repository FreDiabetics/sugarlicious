package app.aapswear.g7watch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import app.aapswear.g7.CollectorAlarmKind
import app.aapswear.g7.CollectorCycleTiming
import app.aapswear.g7.G7PersistedState
import app.aapswear.g7.G7ReconnectScheduler
import app.aapswear.model.DiagnosticSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns the durable recovery wake paths for the Watch collector.
 *
 * The exact/inexact AlarmManager slot is the primary wake path. The legacy bounded-scan strategy
 * may additionally register a filtered PendingIntent scan; direct reconnect deliberately disarms
 * it so the OS does not scan continuously between sensor windows. A future alarm is staged before
 * risky BLE/GATT work begins, so process
 * death during a cycle cannot strand the collector.
 */
internal object G7ReconnectAlarmScheduler {
    private const val REQUEST_CODE = 0
    private const val MIN_TRIGGER_LEAD_MS = 1_000L

    fun scheduleForState(
        context: Context,
        state: G7PersistedState,
    ): CollectorCycleTiming? {
        if (!state.collectorEnabled) return null
        val requestedAt = state.nextReconnectEpochMs ?: return null
        return scheduleRequested(
            context,
            requestedAt,
            directReconnect = directReconnectAvailable(context, state),
        )
    }

    fun scheduleSafetyForCycle(
        context: Context,
        scheduledCycle: CollectorCycleTiming?,
        state: G7PersistedState,
        nowEpochMs: Long,
    ): CollectorCycleTiming? {
        if (!state.collectorEnabled) return null
        val requestedAt = nextSafetyReconnectEpoch(scheduledCycle, state, nowEpochMs) ?: return null
        val expectedAt =
            scheduledCycle?.expectedReadingEpoch?.plus(G7ReconnectScheduler.EXPECTED_READING_INTERVAL_MS)
                ?: requestedAt + G7ReconnectScheduler.PRECONNECT_LEAD_MS
        return scheduleRequested(
            context,
            requestedAt,
            expectedAt,
            directReconnect = directReconnectAvailable(context, state),
        )
    }

    fun scheduleRecovery(
        context: Context,
        state: G7PersistedState,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): CollectorCycleTiming? {
        if (!state.collectorEnabled) return null
        val plan = G7ReconnectScheduler.afterExpectedWindowMiss(nowEpochMs, state.lastReading?.timestampEpochMs)
        return scheduleRequested(
            context,
            plan.nextReconnectEpochMs,
            directReconnect = directReconnectAvailable(context, state),
        )
    }

    /** Reasserts an already calculated slot without changing its cadence or PendingIntent identity. */
    fun rearmScheduledCycle(
        context: Context,
        cycle: CollectorCycleTiming,
        state: G7PersistedState,
    ): CollectorCycleTiming {
        val requested = cycle.requestedReconnectEpoch
            ?: error("Scheduled G7 cycle has no reconnect time")
        return scheduleRequested(
            context = context,
            requestedReconnectEpochMs = requested,
            expectedReadingEpochMs = cycle.expectedReadingEpoch
                ?: (requested + G7ReconnectScheduler.PRECONNECT_LEAD_MS),
            directReconnect = directReconnectAvailable(context, state),
        )
    }

    fun scheduleRequested(
        context: Context,
        requestedReconnectEpochMs: Long,
        expectedReadingEpochMs: Long = requestedReconnectEpochMs + G7ReconnectScheduler.PRECONNECT_LEAD_MS,
        directReconnect: Boolean = false,
    ): CollectorCycleTiming {
        val app = context.applicationContext

        if (directReconnect) {
            G7AdvertisementWakeScheduler.disarm(app)
        } else {
            G7AdvertisementWakeScheduler.arm(app)
        }

        val strategyRequest = alignReconnectRequestToStrategy(
            requestedReconnectEpochMs,
            expectedReadingEpochMs,
            directReconnect,
        )
        val triggerAt = maxOf(strategyRequest, System.currentTimeMillis() + MIN_TRIGGER_LEAD_MS)
        val pending = reconnectPendingIntent(app)
        val alarmManager = app.getSystemService(AlarmManager::class.java)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        val exactScheduled =
            if (exactAllowed) {
                runCatching {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                    true
                }.getOrElse {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                    false
                }
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                false
            }
        val power = app.getSystemService(PowerManager::class.java)
        val cycle =
            CollectorCycleTiming(
                expectedWindowId = expectedWindowId(expectedReadingEpochMs),
                expectedReadingEpoch = expectedReadingEpochMs,
                requestedReconnectEpoch = triggerAt,
                alarmKind = if (exactScheduled) CollectorAlarmKind.EXACT else CollectorAlarmKind.INEXACT,
                canScheduleExactAlarms = exactAllowed,
                batteryUnrestricted = G7BackgroundAccess.isBatteryUnrestricted(app),
                deviceIdleMode = power.isDeviceIdleMode,
                isInteractive = power.isInteractive,
                charging = runCatching { app.getSystemService(BatteryManager::class.java).isCharging }.getOrNull(),
            )
        G7CollectorDiagnosticStore(app).stageScheduledCycle(cycle)
        G7ExpectedWindowLedger(app).create(expectedReadingEpochMs, triggerAt, cycle.alarmKind)
        G7SensorWindowWatchdog.arm(app, cycle)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            app.recordG7Diagnostic(
                "EXPECTED_WINDOW_CREATED",
                "EXPECTED_WINDOW_CREATED · Primary und Watchdog armiert",
                DiagnosticSeverity.INFO,
                mapOf(
                    "expectedWindowId" to cycle.expectedWindowId,
                    "expectedAt" to expectedReadingEpochMs,
                    "primaryAlarmScheduledAt" to triggerAt,
                    "watchdogScheduledAt" to (expectedReadingEpochMs + G7SensorWindowWatchdog.WINDOW_TOLERANCE_MS),
                ),
            )
            app.recordG7Diagnostic(
                "PRIMARY_ALARM_ARMED",
                "PRIMARY_ALARM_ARMED · ${cycle.alarmKind.name}",
                DiagnosticSeverity.INFO,
                mapOf("expectedWindowId" to cycle.expectedWindowId, "requestedReconnectEpoch" to triggerAt),
            )
        }
        return cycle
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        G7AdvertisementWakeScheduler.disarm(app)
        G7SensorWindowWatchdog.cancel(app)
        val pending = reconnectPendingIntent(app)
        app.getSystemService(AlarmManager::class.java).cancel(pending)
        pending.cancel()
    }

    private fun reconnectPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, G7ReconnectReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun directReconnectAvailable(context: Context, state: G7PersistedState): Boolean =
        shouldUseDirectReconnect(
            G7ReconnectStrategyStore.read(context.applicationContext),
            state.sensor?.deviceAddress,
        )
}

// Hardware baseline 2026-08-24: a nominal 5 s alarm lead produced the actual GATT request only
// ~3 s before the slot, while connection p50/p95 completed ~2.7/5.0 s after it. Ten seconds keeps
// the bounded direct-first design but gives Android BLE scheduling enough reserve before fallback.
internal const val G7_DIRECT_PRECONNECT_LEAD_MS = 10_000L

internal fun alignReconnectRequestToStrategy(
    requestedReconnectEpochMs: Long,
    expectedReadingEpochMs: Long,
    directReconnect: Boolean,
): Long =
    if (directReconnect) {
        maxOf(requestedReconnectEpochMs, expectedReadingEpochMs - G7_DIRECT_PRECONNECT_LEAD_MS)
    } else {
        requestedReconnectEpochMs
    }

internal fun nextSafetyReconnectEpoch(
    scheduledCycle: CollectorCycleTiming?,
    state: G7PersistedState,
    nowEpochMs: Long,
): Long? {
    if (!state.collectorEnabled) return null
    scheduledCycle?.expectedReadingEpoch?.let { expected ->
        return expected +
            G7ReconnectScheduler.EXPECTED_READING_INTERVAL_MS -
            G7ReconnectScheduler.PRECONNECT_LEAD_MS
    }
    state.nextReconnectEpochMs?.takeIf { it > nowEpochMs + 1_000L }?.let { return it }
    return G7ReconnectScheduler.afterExpectedWindowMiss(
        nowEpochMs,
        state.lastReading?.timestampEpochMs,
    ).nextReconnectEpochMs
}

internal fun stagedSafetyCycle(
    currentCycle: CollectorCycleTiming?,
    pendingCycle: CollectorCycleTiming?,
): CollectorCycleTiming? {
    val currentExpected = currentCycle?.expectedReadingEpoch ?: return null
    val expectedSafetySlot = currentExpected + G7ReconnectScheduler.EXPECTED_READING_INTERVAL_MS
    return pendingCycle?.takeIf { it.expectedReadingEpoch == expectedSafetySlot }
}
