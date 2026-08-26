package app.aapswear.g7watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import app.aapswear.g7.CollectorCycleClassification
import app.aapswear.g7.CollectorDiagnosticResult
import app.aapswear.g7.CollectorDiagnosticStage

internal fun shouldRestoreG7Collector(action: String?, collectorEnabled: Boolean): Boolean =
    collectorEnabled &&
        (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED)

/**
 * Very short alarm→FGS CPU handoff. The normal bounded cycle WakeLock in G7CollectorService takes
 * over immediately after the service starts. The timeout guarantees that a failed service launch
 * cannot leave the CPU held indefinitely.
 */
internal object G7WakeHandoff {
    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context) {
        release()
        wakeLock =
            context.applicationContext
                .getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:G7AlarmHandoff")
                .apply {
                    setReferenceCounted(false)
                    acquire(HANDOFF_TIMEOUT_MS)
                }
    }

    @Synchronized
    fun release() {
        wakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        wakeLock = null
    }

    private const val HANDOFF_TIMEOUT_MS = 15_000L
}

class G7BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val state = G7SensorStateStore(context).read()
        if (!shouldRestoreG7Collector(intent.action, state.collectorEnabled)) return

        G7CgmAlarmCoordinator.restore(context)
        G7RuntimeReconciler.reconcile(
            context,
            if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                G7RuntimeEntryPoint.PACKAGE_REPLACED
            } else {
                G7RuntimeEntryPoint.BOOT
            },
        )
        // Lifecycle recovery must never rewrite the user's persisted enable/disable decision.
        // If Android temporarily refuses the FGS launch, keep collectorEnabled=true and retain a
        // durable future alarm so a later slot can recover without re-pairing or losing the session.
        runCatching { G7CollectorService.start(context) }
            .onFailure { G7ReconnectAlarmScheduler.scheduleRecovery(context, state) }
    }
}

class G7ReconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val state = G7SensorStateStore(context).read()
        if (!state.collectorEnabled) return
        val now = System.currentTimeMillis()
        val diagnosticStore = G7CollectorDiagnosticStore(context)
        val scheduled = diagnosticStore.markScheduledAlarmReceived(now)
        G7ExpectedWindowLedger(context).markPrimaryTriggered(scheduled?.expectedWindowId, now)
        // The service will stage the following slot before BLE work. At receiver level this is an
        // observation-only reconciliation so the just-fired diagnostic envelope is not replaced.
        G7RuntimeReconciler.reconcile(
            context,
            G7RuntimeEntryPoint.RECONNECT_RECEIVER,
            allowRepair = false,
            nowEpochMs = now,
        )
        G7WakeHandoff.acquire(context)
        runCatching { G7CollectorService.startScheduledReconnect(context) }
            .onFailure { error ->
                G7WakeHandoff.release()
                // The alarm that brought us here has already fired. Always stage another future
                // slot before returning, otherwise a transient FGS launch rejection can strand the
                // collector indefinitely.
                G7ReconnectAlarmScheduler.scheduleRecovery(context, state, now)
                val attempt = diagnosticStore.begin(
                    manual = false,
                    restart = false,
                    cycle = scheduled?.copy(cycleEndedAt = System.currentTimeMillis()),
                    nowEpochMs = now,
                )
                diagnosticStore.setClassification(attempt.attemptId, CollectorCycleClassification.SERVICE_START_FAILED)
                diagnosticStore.record(
                    attempt.attemptId,
                    CollectorDiagnosticStage.ERROR,
                    CollectorDiagnosticResult.RECOVERABLE_ERROR,
                    "FGS_RESTART_FAILED · Foreground-Service konnte aus dem Sensorfenster-Alarm nicht gestartet werden; Folgeslot wurde geplant (${error.javaClass.simpleName})",
                    nowEpochMs = System.currentTimeMillis(),
                )
            }
    }
}
