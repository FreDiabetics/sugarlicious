package app.aapswear.g7watch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.aapswear.g7.G7CollectorError
import app.aapswear.g7.G7PersistedState
import app.aapswear.g7.G7SessionState
import app.aapswear.model.DiagnosticSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal object G7AlertPolicyStore {
    private const val PREFS = "g7_alert_policy"
    private const val KEY_ALARMS_ENABLED = "alarms_enabled"
    private const val KEY_AUTOMATIC_ENABLE_AT = "automatic_enable_at"

    fun alarmsEnabled(context: Context, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val preferences =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return preferences.getBoolean(KEY_ALARMS_ENABLED, false) ||
            preferences.getLong(KEY_AUTOMATIC_ENABLE_AT, 0L).let { it > 0L && nowEpochMs >= it }
    }

    fun nextAutomaticEnableAt(context: Context, nowEpochMs: Long = System.currentTimeMillis()): Long? =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_AUTOMATIC_ENABLE_AT, 0L)
            .takeIf { it > nowEpochMs }

    fun setPolicy(context: Context, enabled: Boolean, automaticEnableAtEpochMs: Long? = null) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ALARMS_ENABLED, enabled)
            .putLong(KEY_AUTOMATIC_ENABLE_AT, automaticEnableAtEpochMs?.takeIf { it > 0L } ?: 0L)
            .apply()
    }
}

internal const val G7_SIGNAL_LOSS_AFTER_MS = 16L * 60_000L

internal fun isG7SignalLoss(
    lastReadingEpochMs: Long?,
    nowEpochMs: Long,
): Boolean =
    lastReadingEpochMs != null &&
        nowEpochMs - lastReadingEpochMs >= G7_SIGNAL_LOSS_AFTER_MS

internal fun shouldPostImmediateCollectorAlert(
    alarmsEnabled: Boolean,
    error: G7CollectorError,
    sessionState: G7SessionState,
): Boolean {
    if (!alarmsEnabled) return false
    if (!error.recoverable) return true
    return sessionState == G7SessionState.USER_INTERVENTION_REQUIRED ||
        sessionState == G7SessionState.REQUIRES_REBOND ||
        sessionState == G7SessionState.REQUIRES_FULL_HANDSHAKE
}

internal object G7SignalLossMonitor {
    private const val REQUEST_CODE = 7011
    private const val MIN_TRIGGER_LEAD_MS = 1_000L

    fun scheduleFromState(context: Context, state: G7PersistedState) {
        if (!state.collectorEnabled) {
            cancel(context)
            return
        }
        val lastReading = state.lastReading?.timestampEpochMs ?: run {
            cancel(context)
            return
        }
        schedule(context, lastReading + G7_SIGNAL_LOSS_AFTER_MS)
    }

    fun cancel(context: Context) {
        val pending = pendingIntent(context)
        context.getSystemService(AlarmManager::class.java).cancel(pending)
        pending.cancel()
    }

    fun schedulePolicyRecheck(context: Context, atEpochMs: Long) {
        schedule(context, atEpochMs)
    }

    private fun schedule(context: Context, requestedAtEpochMs: Long) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(AlarmManager::class.java)
        val triggerAt = maxOf(requestedAtEpochMs, System.currentTimeMillis() + MIN_TRIGGER_LEAD_MS)
        val pending = pendingIntent(app)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (exactAllowed) {
            runCatching {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }.getOrElse {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, G7SignalLossReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

class G7SignalLossReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // State deserialization, alarm-channel creation and notification delivery may all touch
        // disk or system services. A BroadcastReceiver only has a few seconds on its main thread;
        // keeping the complete signal-loss path behind goAsync avoids ANRs that would kill the
        // collector process precisely while it is waiting for the next sensor advertisement.
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(8_000L) {
                    val state = G7SensorStateStore(app).read()
                    if (!state.collectorEnabled) return@withTimeout

                    val lastReadingAt = state.lastReading?.timestampEpochMs
                    val now = System.currentTimeMillis()
                    if (!isG7SignalLoss(lastReadingAt, now)) {
                        G7SignalLossMonitor.scheduleFromState(app, state)
                        return@withTimeout
                    }

                    if (!G7AlertPolicyStore.alarmsEnabled(app)) {
                        G7AlertPolicyStore.nextAutomaticEnableAt(app, now)?.let { enableAt ->
                            G7SignalLossMonitor.schedulePolicyRecheck(app, enableAt)
                        }
                        app.recordG7Diagnostic(
                            code = "G7-SIGNAL-LOSS-SUPPRESSED",
                            message = "Direct G7 signal loss suppressed because another canonical source is active",
                            severity = DiagnosticSeverity.INFO,
                            metadata = mapOf("lastReadingEpochMs" to lastReadingAt),
                        )
                        return@withTimeout
                    }

                    G7CgmAlarmCoordinator.onSignalLoss(app, state.lastReading, now)
                }
            } catch (error: Throwable) {
                app.recordG7Diagnostic(
                    code = "G7-SIGNAL-LOSS-500",
                    message = "Asynchronous signal-loss evaluation failed",
                    severity = DiagnosticSeverity.ERROR,
                    metadata = mapOf("errorType" to error.javaClass.simpleName.take(40)),
                )
            } finally {
                pending.finish()
            }
        }
    }
}
