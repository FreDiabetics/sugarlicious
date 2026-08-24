package app.aapswear.g7watch

import android.content.Context
import app.aapswear.g7.CollectorDiagnosticAttempt
import app.aapswear.g7.G7CollectorError
import app.aapswear.g7.G7ConnectionState
import app.aapswear.g7.G7PersistedState
import app.aapswear.g7.G7ProtocolState
import app.aapswear.g7.G7SessionState
import app.aapswear.model.DiagnosticSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal enum class G7RuntimeHealth {
    INACTIVE,
    HEALTHY_ACTIVE,
    HEALTHY_ARMED,
    STALE_ATTEMPT,
    MISSED_RECONNECT,
    ORPHANED_STATE,
    RECOVERY_REQUIRED,
    RECOVERY_INVARIANT_BROKEN,
}

internal enum class G7RuntimeEntryPoint {
    SERVICE_CREATE,
    SERVICE_START,
    BOOT,
    PACKAGE_REPLACED,
    RECONNECT_RECEIVER,
    SIGNAL_LOSS,
    WATCH_APP,
    SYSTEM_STATUS,
}

internal data class G7RuntimeAssessment(
    val health: G7RuntimeHealth,
    val attemptAgeMs: Long? = null,
    val lastProgressAgeMs: Long? = null,
    val futureReconnectEpochMs: Long? = null,
    val cleanupRequired: Boolean = false,
)

internal data class G7RuntimeReconcileResult(
    val detected: G7RuntimeAssessment,
    val restoredHealth: G7RuntimeHealth,
    val recoveryReconnectEpochMs: Long? = null,
)

/** Process-local truth for whether the Service owns a real coroutine-backed BLE cycle. */
internal object G7CollectorRuntimeRegistry {
    private var owner: Any? = null
    private var activeCheck: (() -> Boolean)? = null
    private var cancelAction: (() -> Unit)? = null

    @Synchronized
    fun register(owner: Any, activeCheck: () -> Boolean, cancelAction: () -> Unit) {
        this.owner = owner
        this.activeCheck = activeCheck
        this.cancelAction = cancelAction
    }

    @Synchronized
    fun unregister(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        activeCheck = null
        cancelAction = null
    }

    @Synchronized
    fun hasLiveCycle(): Boolean = activeCheck?.invoke() == true

    @Synchronized
    fun hasRuntimeService(): Boolean = owner != null

    @Synchronized
    fun cancelLiveCycle() {
        cancelAction?.invoke()
    }
}

private val persistedBusyConnectionStates = setOf(
    G7ConnectionState.SCANNING,
    G7ConnectionState.CONNECTING,
    G7ConnectionState.DISCOVERING,
    G7ConnectionState.CONNECTED,
)

private val persistedBusyProtocolStates = setOf(
    G7ProtocolState.SCANNING,
    G7ProtocolState.SENSOR_FOUND,
    G7ProtocolState.CONNECTING,
    G7ProtocolState.DISCOVERING,
    G7ProtocolState.DISCOVERING_SERVICES,
    G7ProtocolState.ENABLING_NOTIFICATIONS,
    G7ProtocolState.AUTHENTICATION_START,
    G7ProtocolState.AUTHENTICATION_ROUND_1,
    G7ProtocolState.AUTHENTICATION_ROUND_2,
    G7ProtocolState.AUTHENTICATION_ROUND_3,
    G7ProtocolState.CHALLENGE,
    G7ProtocolState.CERTIFICATE_EXCHANGE,
    G7ProtocolState.KEY_EXCHANGE,
    G7ProtocolState.BONDING,
    G7ProtocolState.AUTHENTICATING,
    G7ProtocolState.AUTHENTICATED,
    G7ProtocolState.REQUESTING_GLUCOSE,
    G7ProtocolState.RECEIVING_GLUCOSE,
    G7ProtocolState.BACKFILL,
)

internal fun assessG7Runtime(
    state: G7PersistedState,
    activeAttempt: CollectorDiagnosticAttempt?,
    pendingReconnectEpochMs: Long?,
    liveCycle: Boolean,
    sensorConfigured: Boolean,
    nowEpochMs: Long,
    reconnectToleranceMs: Long = G7_RUNTIME_RECONNECT_TOLERANCE_MS,
    maxProgressInactivityMs: Long = G7_RUNTIME_MAX_PROGRESS_INACTIVITY_MS,
): G7RuntimeAssessment {
    if (!state.collectorEnabled || !sensorConfigured) return G7RuntimeAssessment(G7RuntimeHealth.INACTIVE)

    val attemptAge = activeAttempt?.let { (nowEpochMs - it.startedAtEpochMs).coerceAtLeast(0L) }
    val progressAge = activeAttempt?.let { (nowEpochMs - it.lastProgressAtEpochMs).coerceAtLeast(0L) }
    val attemptStale = activeAttempt?.let {
        it.deadlineEpochMs?.let { deadline -> nowEpochMs >= deadline } == true ||
            progressAge?.let { age -> age >= maxProgressInactivityMs } == true
    } == true
    // nextReconnectEpochMs is only the desired time. A pending diagnostic envelope is written
    // after AlarmManager accepted the request and is the durable evidence that the wake path was
    // actually armed. Persisted future intent without that envelope must be repaired.
    val futureReconnect = pendingReconnectEpochMs?.takeIf { it > nowEpochMs + reconnectToleranceMs }
    val missedReconnect = listOfNotNull(pendingReconnectEpochMs, state.nextReconnectEpochMs)
        .any { it < nowEpochMs - reconnectToleranceMs }

    if (attemptStale) {
        return G7RuntimeAssessment(
            health = G7RuntimeHealth.STALE_ATTEMPT,
            attemptAgeMs = attemptAge,
            lastProgressAgeMs = progressAge,
            futureReconnectEpochMs = futureReconnect,
            cleanupRequired = true,
        )
    }

    if (liveCycle) {
        return if (futureReconnect != null) {
            G7RuntimeAssessment(G7RuntimeHealth.HEALTHY_ACTIVE, attemptAge, progressAge, futureReconnect)
        } else {
            G7RuntimeAssessment(G7RuntimeHealth.RECOVERY_INVARIANT_BROKEN, attemptAge, progressAge)
        }
    }

    val persistedClaimsActivity =
        state.activeAttemptId != null ||
            state.connectionState in persistedBusyConnectionStates ||
            state.protocolState in persistedBusyProtocolStates
    if (persistedClaimsActivity) {
        return G7RuntimeAssessment(
            health = G7RuntimeHealth.ORPHANED_STATE,
            attemptAgeMs = attemptAge,
            lastProgressAgeMs = progressAge,
            futureReconnectEpochMs = futureReconnect,
            cleanupRequired = true,
        )
    }
    if (futureReconnect != null) return G7RuntimeAssessment(G7RuntimeHealth.HEALTHY_ARMED, futureReconnectEpochMs = futureReconnect)
    if (missedReconnect) return G7RuntimeAssessment(G7RuntimeHealth.MISSED_RECONNECT)
    return G7RuntimeAssessment(G7RuntimeHealth.RECOVERY_REQUIRED)
}

/**
 * Reconciles durable state with the current process. Persisted CONNECTING/SCANNING state is never
 * accepted as proof that Android BLE objects survived process or service death.
 */
internal object G7RuntimeReconciler {
    fun reconcile(
        context: Context,
        entryPoint: G7RuntimeEntryPoint,
        liveCycle: Boolean? = null,
        allowRepair: Boolean = true,
        cancelLiveCycle: (() -> Unit)? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): G7RuntimeReconcileResult {
        val app = context.applicationContext
        val stateStore = G7SensorStateStore(app)
        val diagnosticStore = G7CollectorDiagnosticStore(app)
        val state = stateStore.read()
        val activeAttempt = diagnosticStore.activeAttempt(state.activeAttemptId)
        val pendingCycle = diagnosticStore.pendingScheduledCycle()
        val pendingReconnect = pendingCycle?.requestedReconnectEpoch
        val configured = state.sensor != null && G7CredentialStore(app).read() != null
        val effectiveLiveCycle = liveCycle ?: G7CollectorRuntimeRegistry.hasLiveCycle()
        val assessment = assessG7Runtime(
            state = state,
            activeAttempt = activeAttempt,
            pendingReconnectEpochMs = pendingReconnect,
            liveCycle = effectiveLiveCycle,
            sensorConfigured = configured,
            nowEpochMs = nowEpochMs,
        )
        recordRuntimeEvent(app, "RUNTIME_RECONCILE_START", entryPoint, assessment, state.nextReconnectEpochMs, null)
        if (
            state.collectorEnabled && configured && !G7CollectorRuntimeRegistry.hasRuntimeService() &&
            entryPoint in setOf(
                G7RuntimeEntryPoint.BOOT,
                G7RuntimeEntryPoint.PACKAGE_REPLACED,
                G7RuntimeEntryPoint.SIGNAL_LOSS,
                G7RuntimeEntryPoint.WATCH_APP,
            )
        ) {
            recordRuntimeEvent(app, "FGS_MISSING", entryPoint, assessment, state.nextReconnectEpochMs, null)
        }
        if (assessment.health == G7RuntimeHealth.STALE_ATTEMPT) {
            recordRuntimeEvent(app, "STALE_ATTEMPT_DETECTED", entryPoint, assessment, state.nextReconnectEpochMs, null)
        }

        val shouldReassertDurableAlarm =
            allowRepair &&
                assessment.health == G7RuntimeHealth.HEALTHY_ARMED &&
                entryPoint in setOf(
                    G7RuntimeEntryPoint.BOOT,
                    G7RuntimeEntryPoint.PACKAGE_REPLACED,
                    G7RuntimeEntryPoint.SIGNAL_LOSS,
                )
        if (shouldReassertDurableAlarm && pendingCycle != null) {
            val rearmed = runCatching {
                G7ReconnectAlarmScheduler.rearmScheduledCycle(app, pendingCycle, state)
            }.getOrNull()
            val rearmedAt = rearmed?.requestedReconnectEpoch
            if (rearmedAt != null) stateStore.save(state.copy(nextReconnectEpochMs = rearmedAt))
            recordRuntimeEvent(
                app,
                if (rearmedAt != null) "RECOVERY_ALARM_ARMED" else "RECOVERY_ALARM_REARM_FAILED",
                entryPoint,
                assessment,
                state.nextReconnectEpochMs,
                rearmedAt,
            )
            return G7RuntimeReconcileResult(
                assessment,
                if (rearmedAt != null) G7RuntimeHealth.HEALTHY_ARMED else G7RuntimeHealth.RECOVERY_REQUIRED,
                rearmedAt,
            )
        }

        if (!allowRepair || assessment.health in setOf(G7RuntimeHealth.INACTIVE, G7RuntimeHealth.HEALTHY_ACTIVE, G7RuntimeHealth.HEALTHY_ARMED)) {
            val healthyEvent = when (assessment.health) {
                G7RuntimeHealth.HEALTHY_ACTIVE -> "RUNTIME_HEALTHY_ACTIVE"
                G7RuntimeHealth.HEALTHY_ARMED -> "RUNTIME_HEALTHY_ARMED"
                G7RuntimeHealth.INACTIVE -> "RUNTIME_INACTIVE"
                else -> "RUNTIME_RECONCILE_OBSERVED"
            }
            recordRuntimeEvent(
                app,
                healthyEvent,
                entryPoint,
                assessment,
                state.nextReconnectEpochMs,
                assessment.futureReconnectEpochMs,
            )
            return G7RuntimeReconcileResult(assessment, assessment.health, assessment.futureReconnectEpochMs)
        }

        if (assessment.cleanupRequired) {
            (cancelLiveCycle ?: G7CollectorRuntimeRegistry::cancelLiveCycle).invoke()
            AndroidG7Scanner.forceCleanup()
            diagnosticStore.expireStaleAttempts(nowEpochMs, 0L)
        }
        val cleaned = stateStore.read().copy(
            connectionState = G7ConnectionState.DISCONNECTED,
            protocolState = G7ProtocolState.RECOVERING,
            sessionState = G7SessionState.RECOVERING,
            activeAttemptId = null,
            scanStartedAtEpochMs = null,
            scanTimeoutAtEpochMs = null,
            lastError = G7CollectorError(
                code = "G7-RUNTIME-276",
                recoverable = true,
                occurredAtEpochMs = nowEpochMs,
                safeMessage = "Collector-Laufzeit wurde automatisch wiederhergestellt",
            ),
        )
        stateStore.save(cleaned)

        val recovery = runCatching {
            G7ReconnectAlarmScheduler.scheduleRecovery(app, cleaned, nowEpochMs)
        }.getOrNull()
        val recoveryAt = recovery?.requestedReconnectEpoch
        if (recoveryAt != null) {
            recordRuntimeEvent(app, "RECOVERY_SLOT_CALCULATED", entryPoint, assessment, state.nextReconnectEpochMs, recoveryAt)
        }
        val restored = if (recoveryAt != null && recoveryAt > nowEpochMs) {
            stateStore.save(cleaned.copy(nextReconnectEpochMs = recoveryAt))
            G7RuntimeHealth.HEALTHY_ARMED
        } else {
            G7RuntimeHealth.RECOVERY_REQUIRED
        }
        val event = when (assessment.health) {
            G7RuntimeHealth.STALE_ATTEMPT -> "STALE_ATTEMPT_CLEANED"
            G7RuntimeHealth.ORPHANED_STATE -> "ORPHANED_CONNECTION_STATE"
            G7RuntimeHealth.MISSED_RECONNECT -> "MISSED_RECONNECT_DETECTED"
            else -> if (restored == G7RuntimeHealth.HEALTHY_ARMED) "RECOVERY_INVARIANT_RESTORED" else "RECOVERY_ALARM_REARM_FAILED"
        }
        recordRuntimeEvent(app, event, entryPoint, assessment, state.nextReconnectEpochMs, recoveryAt)
        if (recoveryAt != null) {
            recordRuntimeEvent(app, "RECOVERY_ALARM_ARMED", entryPoint, assessment, state.nextReconnectEpochMs, recoveryAt)
            recordRuntimeEvent(app, "RECOVERY_INVARIANT_RESTORED", entryPoint, assessment, state.nextReconnectEpochMs, recoveryAt)
        }
        return G7RuntimeReconcileResult(assessment, restored, recoveryAt)
    }

    private fun recordRuntimeEvent(
        context: Context,
        event: String,
        entryPoint: G7RuntimeEntryPoint,
        assessment: G7RuntimeAssessment,
        oldReconnectEpochMs: Long?,
        newReconnectEpochMs: Long?,
    ) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            context.recordG7Diagnostic(
                code = event,
                message = "$event · ${entryPoint.name}",
                severity = if (event.endsWith("FAILED") || assessment.health == G7RuntimeHealth.RECOVERY_INVARIANT_BROKEN) {
                    DiagnosticSeverity.WARNING
                } else {
                    DiagnosticSeverity.INFO
                },
                metadata = mapOf(
                    "runtimeHealth" to assessment.health.name,
                    "attemptAgeMs" to assessment.attemptAgeMs,
                    "lastProgressAgeMs" to assessment.lastProgressAgeMs,
                    "oldReconnectEpochMs" to oldReconnectEpochMs,
                    "newReconnectEpochMs" to newReconnectEpochMs,
                ),
            )
        }
    }
}

internal const val G7_RUNTIME_RECONNECT_TOLERANCE_MS = 30_000L
internal const val G7_RUNTIME_MAX_PROGRESS_INACTIVITY_MS = 2L * 60_000L
