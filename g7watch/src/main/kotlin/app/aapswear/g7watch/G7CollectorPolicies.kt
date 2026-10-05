package app.aapswear.g7watch

import app.aapswear.g7.CollectorCycleClassification
import app.aapswear.g7.CollectorDiagnosticAttempt
import app.aapswear.g7.G7CollectorHealth
import app.aapswear.g7.G7ConnectionState
import app.aapswear.g7.G7FailureClass
import app.aapswear.g7.G7PersistedState
import app.aapswear.g7.G7ProtocolState
import app.aapswear.g7.G7Sensor

internal fun shouldKeepG7RuntimeForeground(collectorEnabled: Boolean): Boolean = collectorEnabled

internal fun shouldUpdateG7ForegroundNotification(inserted: Boolean, classification: CollectorCycleClassification): Boolean =
    inserted && classification == CollectorCycleClassification.SUCCESS_FRESH

internal fun restoreAuthenticatedG7Address(sensor: G7Sensor, sharedKeyAddress: String?): G7Sensor =
    if (sensor.deviceAddress.isNullOrBlank() && !sharedKeyAddress.isNullOrBlank()) sensor.copy(deviceAddress = sharedKeyAddress) else sensor

internal fun shouldRepairG7RuntimeOnServiceStart(action: String?): Boolean = action != G7CollectorService.ACTION_RECONNECT

internal fun shouldCoalesceG7CollectorTrigger(automatic: Boolean, activeCycle: Boolean): Boolean = automatic && activeCycle

internal fun needsG7FollowUpRepair(collectorEnabled: Boolean, pendingReconnectEpochMs: Long?, nowEpochMs: Long): Boolean =
    collectorEnabled && (pendingReconnectEpochMs == null || pendingReconnectEpochMs <= nowEpochMs)

internal fun ensureG7PairingAttempt(state: G7PersistedState, nowEpochMs: Long): G7PersistedState {
    if (!state.collectorEnabled || state.sensor == null || state.lastReading != null) return state
    val deadline = state.pairingDeadlineEpochMs?.takeIf { it > nowEpochMs } ?: (nowEpochMs + G7_INITIAL_PAIRING_SCAN_TIMEOUT_MS)
    return state.copy(
        pairingAttemptId =
            state.pairingAttemptId ?: java.util.UUID
                .randomUUID()
                .toString(),
        pairingStartedAtEpochMs = state.pairingStartedAtEpochMs ?: nowEpochMs,
        pairingDeadlineEpochMs = deadline,
        scanTimeoutAtEpochMs = deadline,
    )
}

internal fun resetG7RuntimeForRestart(state: G7PersistedState): G7PersistedState =
    state.copy(
        connectionState = G7ConnectionState.DISCONNECTED,
        protocolState = G7ProtocolState.UNINITIALIZED,
        authenticationState = app.aapswear.g7.G7AuthenticationState.UNKNOWN,
        retryCount = 0,
        nextReconnectEpochMs = null,
        lastError = null,
        activeAttemptId = null,
        scanStartedAtEpochMs = null,
        scanTimeoutAtEpochMs = state.pairingDeadlineEpochMs,
    )

internal fun clearG7ScanRuntime(state: G7PersistedState): G7PersistedState =
    state.copy(scanStartedAtEpochMs = null, scanTimeoutAtEpochMs = null)

internal fun collectorAttemptDeadlineMs(state: G7PersistedState): Long =
    if (state.sensor?.deviceAddress.isNullOrBlank() || state.lastReading == null) {
        G7_INITIAL_PAIRING_SCAN_TIMEOUT_MS + 2L * 60_000L
    } else {
        3L * 60_000L
    }

internal const val RADIO_DEGRADED_CLUSTER_THRESHOLD = 3
internal const val G7_RUNTIME_RECYCLE_COOLDOWN_MS = 15L * 60_000L

private val RADIO_FAILURE_CLASSES =
    setOf(
        G7FailureClass.DIRECT_NO_CALLBACK,
        G7FailureClass.DIRECT_GATT_133,
        G7FailureClass.DIRECT_OTHER_GATT_ERROR,
        G7FailureClass.SCAN_RADIO_FAILURE,
        G7FailureClass.SENSOR_NOT_ADVERTISING,
        G7FailureClass.SENSOR_UNREACHABLE,
    )

internal fun shouldRecycleG7Runtime(
    cycle: app.aapswear.g7.CollectorCycleTiming?,
    radioFailureStreak: Int,
    lastRecycleAtEpochMs: Long?,
    nowEpochMs: Long,
): Boolean =
    radioFailureStreak >= RADIO_DEGRADED_CLUSTER_THRESHOLD &&
        cycle?.directConnectResult == app.aapswear.g7.DirectConnectResult.NO_CALLBACK &&
        cycle.fallbackScanUsed &&
        (cycle.scanExactAddressResults ?: 0) == 0 &&
        (lastRecycleAtEpochMs == null || nowEpochMs - lastRecycleAtEpochMs >= G7_RUNTIME_RECYCLE_COOLDOWN_MS)

internal fun nextRadioFailureStreak(previous: G7CollectorHealth, current: G7FailureClass): Int =
    if (current in RADIO_FAILURE_CLASSES && previous.lastFailureClass in RADIO_FAILURE_CLASSES) previous.consecutiveFailures + 1 else 1

internal fun consecutiveRadioFailures(attempts: List<CollectorDiagnosticAttempt>, currentAttemptId: Long): Int =
    attempts
        .asSequence()
        .filter { it.attemptId < currentAttemptId }
        .sortedByDescending { it.attemptId }
        .takeWhile { isCompleteRadioFailure(it.classification, it.cycle) }
        .count()

internal fun isCompleteRadioFailure(
    classification: CollectorCycleClassification?,
    cycle: app.aapswear.g7.CollectorCycleTiming?,
): Boolean =
    classification == CollectorCycleClassification.FALLBACK_SCAN_FAILED ||
        (classification == CollectorCycleClassification.GATT_CONNECT_FAILED && cycle?.fallbackScanUsed == true)

internal fun directConnectDiagnosticCode(result: app.aapswear.g7.DirectConnectResult): String =
    when (result) {
        app.aapswear.g7.DirectConnectResult.NO_CALLBACK -> "DIRECT_CONNECT_NO_CALLBACK"
        app.aapswear.g7.DirectConnectResult.STATUS_133 -> "DIRECT_CONNECT_STATUS_133"
        app.aapswear.g7.DirectConnectResult.STATUS_19 -> "DIRECT_CONNECT_STATUS_19"
        app.aapswear.g7.DirectConnectResult.OTHER_STATUS -> "DIRECT_CONNECT_OTHER_STATUS"
        app.aapswear.g7.DirectConnectResult.TIMEOUT -> "DIRECT_CONNECT_TIMEOUT"
        app.aapswear.g7.DirectConnectResult.DISCONNECTED_EARLY -> "DIRECT_CONNECT_DISCONNECTED_EARLY"
        app.aapswear.g7.DirectConnectResult.DEVICE_UNAVAILABLE -> "DIRECT_CONNECT_DEVICE_UNAVAILABLE"
        app.aapswear.g7.DirectConnectResult.SECURITY_ERROR -> "DIRECT_CONNECT_SECURITY_ERROR"
        app.aapswear.g7.DirectConnectResult.SUCCESS -> "DIRECT_CONNECT_SUCCESS"
    }
