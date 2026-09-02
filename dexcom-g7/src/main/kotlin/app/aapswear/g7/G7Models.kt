package app.aapswear.g7

import app.aapswear.model.DataSourceId
import app.aapswear.model.Trend
import kotlinx.serialization.Serializable

@Serializable
data class CgmReading(
    val id: String,
    val source: DataSourceId,
    val sensorId: String,
    val sessionId: String,
    val glucoseMgDl: Double,
    val timestampEpochMs: Long,
    val receivedAtEpochMs: Long,
    val deltaMgDl: Double? = null,
    val trend: Trend = Trend.UNKNOWN,
    val trendRateMgDlPerMinute: Double? = null,
    val predictedMgDl: Double? = null,
    val sensorAgeSeconds: Long? = null,
    val status: CgmReadingStatus = CgmReadingStatus.VALID,
    val sequenceNumber: Long? = null,
    val displayOnly: Boolean = false,
    val rawSourceTimestamp: Long? = null,
    val sensorStartEpochMs: Long? = null,
    val sensorEndEpochMs: Long? = null,
    val graceEndEpochMs: Long? = null,
    val protocolStatusCode: Int? = null,
    val calibrationStateCode: Int? = null,
    val reservedField: Int? = null,
)

@Serializable enum class CgmReadingStatus { VALID, SENSOR_ERROR, INVALID }
@Serializable enum class G7Trend { DOUBLE_DOWN, SINGLE_DOWN, FORTY_FIVE_DOWN, FLAT, FORTY_FIVE_UP, SINGLE_UP, DOUBLE_UP, UNKNOWN }
@Serializable enum class G7SensorState { UNKNOWN, WARMUP, ACTIVE, GRACE_PERIOD, ENDED, ERROR }
@Serializable enum class G7ConnectionState { DISCONNECTED, SCANNING, CONNECTING, DISCOVERING, CONNECTED }
@Serializable
enum class G7ProtocolState {
    UNINITIALIZED,
    IDLE,
    SCANNING,
    SENSOR_FOUND,
    CONNECTING,
    DISCOVERING,
    DISCOVERING_SERVICES,
    ENABLING_NOTIFICATIONS,
    AUTHENTICATION_START,
    AUTHENTICATION_ROUND_1,
    AUTHENTICATION_ROUND_2,
    AUTHENTICATION_ROUND_3,
    CHALLENGE,
    CERTIFICATE_EXCHANGE,
    KEY_EXCHANGE,
    BONDING,
    AUTHENTICATING,
    AUTHENTICATED,
    REQUESTING_GLUCOSE,
    RECEIVING_GLUCOSE,
    BACKFILL,
    WAITING_FOR_NEXT_READING,
    DISCONNECTED,
    RECOVERING,
    ERROR,
}
@Serializable enum class G7AuthenticationState { UNKNOWN, REQUIRED, AUTHENTICATING, AUTHENTICATED, FAILED }

@Serializable
enum class G7SessionState {
    UNINITIALIZED,
    INITIAL_SETUP,
    AUTHENTICATING,
    AUTHENTICATED,
    READY_FOR_RECONNECT,
    REAUTHENTICATING,
    ACTIVE,
    WAITING_FOR_NEXT_READING,
    RECOVERING,
    REQUIRES_REBOND,
    REQUIRES_FULL_HANDSHAKE,
    USER_INTERVENTION_REQUIRED,
}

@Serializable enum class G7CollectorState { DISABLED, STARTING, SCANNING, CONNECTING, AUTHENTICATING, CONNECTED, RECEIVING, WAITING, RECOVERING, ERROR, USER_ACTION_REQUIRED }
@Serializable enum class CollectorOwner { PHONE, WATCH, TRANSITION_TO_PHONE, TRANSITION_TO_WATCH, UNKNOWN }
@Serializable enum class G7RecoveryStep { NORMAL_RECONNECT, AUTH_RETRY, SHORT_RETRY, BLE_RESCAN, DEVICE_ADDRESS_REFRESH, SESSION_REAUTH, SESSION_RESET, REBOND, FULL_HANDSHAKE, USER_INTERVENTION_REQUIRED }

@Serializable
enum class CollectorDiagnosticStage {
    EXPECTED_WINDOW,
    WATCHDOG,
    IDLE,
    WAITING_FOR_WINDOW,
    ALARM_RECEIVED,
    SERVICE_START,
    WAKE_LOCK,
    SCAN_START,
    SCANNING,
    ADVERTISEMENT_FOUND,
    CONNECT_REQUEST,
    GATT_CONNECTED,
    SERVICE_DISCOVERY,
    SERVICE_READY,
    AUTH_START,
    AUTH_CHALLENGE,
    AUTH_RESPONSE,
    AUTH_SUCCESS,
    AUTH_FAILURE,
    GLUCOSE_REQUEST,
    GLUCOSE_RECEIVED,
    VALIDATION,
    STORE,
    SYNC,
    GATT_CLOSE,
    RETRY,
    RECOVERY,
    COMPLETE,
    ERROR,
}

@Serializable
enum class CollectorDiagnosticResult {
    STARTED,
    INFO,
    SUCCESS,
    RECOVERABLE_ERROR,
    FATAL_ERROR,
    CANCELLED,
}

@Serializable
enum class CollectorAlarmKind { EXACT, INEXACT, NONE }

@Serializable
enum class CollectorCycleClassification {
    SUCCESS_FRESH,
    SUCCESS_AGED,
    ALARM_LATE,
    SERVICE_START_FAILED,
    SCAN_STARTED_LATE,
    NO_ADVERTISEMENT,
    GATT_CONNECT_FAILED,
    AUTH_FAILED,
    GLUCOSE_TIMEOUT,
    INVALID_PACKET,
    STORE_FAILED,
    CANCELLED,
    DIRECT_CONNECT_FAILED,
    FALLBACK_SCAN_FAILED,
    HUNG,
    COALESCED,
    MISSED_SENSOR_WINDOW,
    GATT_NO_CALLBACK,
}

@Serializable
enum class DirectConnectResult {
    NO_CALLBACK,
    STATUS_133,
    STATUS_19,
    OTHER_STATUS,
    TIMEOUT,
    DISCONNECTED_EARLY,
    DEVICE_UNAVAILABLE,
    SECURITY_ERROR,
    SUCCESS,
}

@Serializable
enum class CollectorSlotStrategy {
    DIRECT_ONLY_SUCCESS,
    DIRECT_RETRY_SUCCESS,
    FALLBACK_SCAN_SUCCESS,
    FULL_SLOT_FAILED,
}

@Serializable
data class CollectorCycleTiming(
    val expectedWindowId: String? = null,
    val expectedReadingEpoch: Long? = null,
    val requestedReconnectEpoch: Long? = null,
    val alarmKind: CollectorAlarmKind = CollectorAlarmKind.NONE,
    val canScheduleExactAlarms: Boolean? = null,
    val batteryUnrestricted: Boolean? = null,
    val deviceIdleMode: Boolean? = null,
    val isInteractive: Boolean? = null,
    val charging: Boolean? = null,
    val alarmTriggeredAt: Long? = null,
    val receiverReceivedAt: Long? = null,
    val serviceOnStartCommandAt: Long? = null,
    val wakeLockAcquiredAt: Long? = null,
    val scanStartedAt: Long? = null,
    val advertisementFoundAt: Long? = null,
    val advertisementRssi: Int? = null,
    val connectGattStartedAt: Long? = null,
    val gattGeneration: Long? = null,
    val directConnectCallbackAt: Long? = null,
    val directConnectStartedElapsedRealtimeMs: Long? = null,
    val processUptimeAtDirectConnectMs: Long? = null,
    val directConnectResult: DirectConnectResult? = null,
    val directConnectStatus: Int? = null,
    val directConnectNewState: Int? = null,
    val directConnectAttempts: Int = 0,
    val fallbackScanUsed: Boolean = false,
    val scanEndedAt: Long? = null,
    val scanMode: Int? = null,
    val scanCallbackType: Int? = null,
    val scanTotalResults: Int? = null,
    val scanConnectableResults: Int? = null,
    val scanNamedG7Results: Int? = null,
    val scanExactAddressResults: Int? = null,
    val scanDuplicateResults: Int? = null,
    val scanMinRssi: Int? = null,
    val scanMaxRssi: Int? = null,
    val bluetoothAdapterState: Int? = null,
    val radioFailureStreak: Int = 0,
    val radioDegradedCluster: Boolean = false,
    val slotStrategy: CollectorSlotStrategy? = null,
    val gattConnectedAt: Long? = null,
    val serviceDiscoveryAt: Long? = null,
    val authStartedAt: Long? = null,
    val authSucceededAt: Long? = null,
    val glucosePacketReceivedAt: Long? = null,
    val sensorAgeSeconds: Long? = null,
    val measurementTimestamp: Long? = null,
    val sequenceNumber: Long? = null,
    val storeCompletedAt: Long? = null,
    val cycleEndedAt: Long? = null,
) {
    val alarmLatenessMs: Long?
        get() = receiverReceivedAt?.let { received -> requestedReconnectEpoch?.let { received - it } }
    val serviceStartLatenessMs: Long?
        get() = serviceOnStartCommandAt?.let { started -> receiverReceivedAt?.let { started - it } }
    val scanStartLatenessMs: Long?
        get() = scanStartedAt?.let { scan -> requestedReconnectEpoch?.let { scan - it } }
    val advertisementLatencyMs: Long?
        get() = advertisementFoundAt?.let { found -> scanStartedAt?.let { found - it } }
    val gattLatencyMs: Long?
        get() = gattConnectedAt?.let { connected -> connectGattStartedAt?.let { connected - it } }
    val totalCycleLatencyMs: Long?
        get() = cycleEndedAt?.let { ended -> receiverReceivedAt?.let { ended - it } }
}

@Serializable
data class CollectorExpectedWindow(
    val expectedWindowId: String,
    val expectedAt: Long,
    val primaryAlarmScheduledAt: Long? = null,
    val primaryAlarmTriggeredAt: Long? = null,
    val cycleStartedAt: Long? = null,
    val advertisementSeenAt: Long? = null,
    val fallbackScanUsed: Boolean = false,
    val gattStartedAt: Long? = null,
    val gattGeneration: Long? = null,
    val gattResult: DirectConnectResult? = null,
    val gattAttempts: Int = 0,
    val gatt133Count: Int = 0,
    val noCallbackCount: Int = 0,
    val readingReceivedAt: Long? = null,
    val finalResult: CollectorCycleClassification? = null,
    val recoveryRequired: Boolean = false,
    val watchdogScheduledAt: Long? = null,
    val watchdogTriggeredAt: Long? = null,
)

@Serializable
data class CollectorHardwareMetrics(
    val expectedWindows: Int = 0,
    val attemptedWindows: Int = 0,
    val successfulWindows: Int = 0,
    val missedWindows: Int = 0,
    val firstAttemptSuccess: Int = 0,
    val retrySuccess: Int = 0,
    val gatt133Count: Int = 0,
    val noCallbackCount: Int = 0,
    val fallbackScanCount: Int = 0,
    val longestReadingGapMs: Long? = null,
    val availabilityPercent: Double = 0.0,
    val medianReceiveDelayMs: Long? = null,
    val p95ReceiveDelayMs: Long? = null,
)

@Serializable
data class CollectorSlotSummary(
    val expectedReadingEpoch: Long,
    val attemptId: Long,
    val strategy: CollectorSlotStrategy,
    val directResult: DirectConnectResult? = null,
    val directAttempts: Int = 0,
    val fallbackScanUsed: Boolean = false,
    val scanResultCount: Int? = null,
    val finalClassification: CollectorCycleClassification,
    val readingAgeSeconds: Long? = null,
    val durationMs: Long? = null,
    val radioFailureStreak: Int = 0,
)

@Serializable
data class CollectorDiagnosticEvent(
    val timestampEpochMs: Long,
    val attemptId: Long,
    val stage: CollectorDiagnosticStage,
    val result: CollectorDiagnosticResult,
    val message: String,
    val errorCode: String? = null,
    val sensorId: String? = null,
    val sequence: Long? = null,
    val durationMs: Long? = null,
)

@Serializable
data class CollectorDiagnosticAttempt(
    val attemptId: Long,
    val startedAtEpochMs: Long,
    val lastProgressAtEpochMs: Long = startedAtEpochMs,
    val currentStage: CollectorDiagnosticStage = CollectorDiagnosticStage.IDLE,
    val deadlineEpochMs: Long? = null,
    val manual: Boolean = false,
    val restart: Boolean = false,
    val completedAtEpochMs: Long? = null,
    val result: CollectorDiagnosticResult = CollectorDiagnosticResult.STARTED,
    val summary: String = "Collection-Versuch läuft",
    val events: List<CollectorDiagnosticEvent> = emptyList(),
    val cycle: CollectorCycleTiming? = null,
    val classification: CollectorCycleClassification? = null,
)

@Serializable
data class G7Sensor(
    val sensorId: String,
    val sessionId: String? = null,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val sensorStartEpochMs: Long? = null,
    val sensorEndEpochMs: Long? = null,
    val graceEndEpochMs: Long? = null,
    val state: G7SensorState = G7SensorState.UNKNOWN,
)

@Serializable
data class G7Reading(
    val sensorId: String,
    val sessionId: String,
    val sequenceNumber: Long?,
    val glucoseMgDl: Double,
    val sensorTimestampEpochMs: Long,
    val receivedAtEpochMs: Long,
    val trendRateMgDlPerMinute: Double? = null,
    val predictedMgDl: Double? = null,
    val sensorAgeSeconds: Long? = null,
    val sensorState: G7SensorState = G7SensorState.UNKNOWN,
    val displayOnly: Boolean = false,
    val sensorClockSeconds: Long? = null,
    val sensorStartEpochMs: Long? = null,
    val sensorEndEpochMs: Long? = null,
    val graceEndEpochMs: Long? = null,
    val protocolStatusCode: Int? = null,
    val calibrationStateCode: Int? = null,
    val reservedField: Int? = null,
)

@Serializable
data class G7CollectorError(
    val code: String,
    val recoverable: Boolean,
    val occurredAtEpochMs: Long,
    val safeMessage: String,
)

@Serializable
data class G7PersistedState(
    val sensor: G7Sensor? = null,
    val collectorEnabled: Boolean = false,
    val collectorOwner: CollectorOwner = CollectorOwner.UNKNOWN,
    val connectionState: G7ConnectionState = G7ConnectionState.DISCONNECTED,
    val protocolState: G7ProtocolState = G7ProtocolState.UNINITIALIZED,
    val authenticationState: G7AuthenticationState = G7AuthenticationState.UNKNOWN,
    val sessionState: G7SessionState = G7SessionState.UNINITIALIZED,
    val lastReading: CgmReading? = null,
    val lastSuccessfulConnectionEpochMs: Long? = null,
    val nextReconnectEpochMs: Long? = null,
    val retryCount: Int = 0,
    val lastError: G7CollectorError? = null,
    val activeAttemptId: Long? = null,
    val scanStartedAtEpochMs: Long? = null,
    val scanTimeoutAtEpochMs: Long? = null,
    val lastScanAtEpochMs: Long? = null,
    val lastAttemptCompletedAtEpochMs: Long? = null,
)
