package app.aapswear.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import app.aapswear.datasource.aaps.AapsCapabilityDetector
import app.aapswear.datasource.aaps.AapsPayloadAdapter
import app.aapswear.model.DiagnosticSeverity
import app.aapswear.model.TherapyDisplayState
import app.aapswear.protocol.WearProtocol
import app.aapswear.storage.TherapyStateStore
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

internal const val G7_SOURCE_FALLBACK_MIGRATION_KEY = "g7SetupAutomaticFallbackMigratedV1"

internal fun migrateLegacyForcedG7Source(
    current: DataSourcePreference,
    migrationDone: Boolean,
): DataSourcePreference = DataSourcePreference.ANDROID_APS

class AapsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != AapsPayloadAdapter.ACTION) return
        val pending = goAsync()
        val app = context.applicationContext
        // A valid AAPS delivery is also a recovery signal. Keep the state bridge alive even when
        // the Activity was swiped away or Android recreated the process in the background.
        PersistentBridgeService.start(app)

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val now = System.currentTimeMillis()
                val sourcePreferences = app.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE)
                val configuredSource =
                    runCatching {
                        DataSourcePreference.valueOf(
                            sourcePreferences.getString("dataSource", DataSourcePreference.AUTOMATIC.name)!!,
                        )
                    }.getOrDefault(DataSourcePreference.AUTOMATIC)
                val migrationDone = sourcePreferences.getBoolean(G7_SOURCE_FALLBACK_MIGRATION_KEY, false)
                val sourcePreference = migrateLegacyForcedG7Source(configuredSource, migrationDone)
                if (!migrationDone) {
                    sourcePreferences.edit {
                        if (sourcePreference != configuredSource) {
                            putString("dataSource", sourcePreference.name)
                        }
                        putBoolean(G7_SOURCE_FALLBACK_MIGRATION_KEY, true)
                    }
                    if (sourcePreference != configuredSource) {
                        app.recordMobileDiagnostic(
                            "SOURCE",
                            "SRC-G7-104",
                            "Legacy G7-only source migrated to automatic AAPS fallback",
                        )
                    }
                }
                val parsedState = intent.extras?.let { AapsPayloadAdapter.parse(it, now) }
                if (parsedState == null) {
                    app.diagnostics().edit {
                        putLong("invalidReceived", now)
                        putString("lastSyncStatus", "invalid_payload")
                    }
                    app.recordMobileDiagnostic("SOURCE", "SRC-AAPS-401", "AAPS payload could not be decoded", DiagnosticSeverity.WARNING)
                    return@launch
                }
                val installation = AapsCapabilityDetector.detectInstallation(app)
                val state = parsedState.copy(sourceVersion = installation?.versionName)
                val store = TherapyStateStore(app)
                val previous = store.state.first()
                val (_, displayState) = MobileCanonicalStateCoordinator.savePhoneInput(app, state, now)
                app.recordMobileDiagnostic(
                    "PREDICTION",
                    if (state.glucosePredictions.isEmpty() &&
                        displayState.glucosePredictions.isNotEmpty()
                    ) {
                        "PRED-CACHE-201"
                    } else {
                        "PRED-DATA-200"
                    },
                    if (state.glucosePredictions.isEmpty() &&
                        displayState.glucosePredictions.isNotEmpty()
                    ) {
                        "Cached predictions retained after an empty AAPS update"
                    } else {
                        "AAPS state merged"
                    },
                    metadata =
                        mapOf(
                            "incomingPredictions" to state.glucosePredictions.size,
                            "displayPredictions" to displayState.glucosePredictions.size,
                            "historyCount" to displayState.glucoseHistory.size,
                        ),
                )

                val stateChanged = previous?.copy(receivedAtEpochMs = displayState.receivedAtEpochMs) != displayState
                if (stateChanged) {
                    runCatching { HealthConnectIntegration.exportCgmReading(app, displayState) }
                }
                app.diagnostics().edit {
                    putLong("received", now)
                    putLong("measurement", displayState.glucose?.measuredAtEpochMs ?: 0L)
                    putString("contract", displayState.sourceContract)
                    putString("sourceVersion", displayState.sourceVersion)
                    putString("sourcePackage", installation?.packageName)
                    putLong("sourceVersionCode", installation?.versionCode ?: 0L)
                    putString("lastSyncStatus", if (stateChanged) "pending" else "unchanged_refresh")
                }

                app.diagnostics().edit {
                    putLong("lastSyncAt", System.currentTimeMillis())
                    putString("lastSyncStatus", "dispatched")
                    remove("lastSyncError")
                }
            } finally {
                pending.finish()
            }
        }
    }
}

suspend fun publishState(
    context: Context,
    state: TherapyDisplayState,
) {
    val payload = WearProtocol.encodeStateForTransport(state)
    val request =
        PutDataRequest
            .create(WearProtocol.STATE_PATH)
            .setData(payload)
            .setUrgent()

    // Keep the DataItem as the durable source of truth. It survives a temporarily disconnected
    // Watch and will synchronize when the Wear network becomes available again.
    val immediatePushes =
        supervisorScope {
            // DataClient durability and MessageClient latency are independent guarantees. Waiting for
            // Play services to persist/synchronize the DataItem before even starting the message path
            // created an avoidable head-of-line delay.
            val durable = async { Wearable.getDataClient(context).putDataItem(request).await() }
            val immediate =
                async {
                    withTimeoutOrNull(IMMEDIATE_WATCH_PUSH_TIMEOUT_MS) {
                        val nodeIds = runCatching { refreshReachableWatchNodeIds(context) }.getOrDefault(emptyList())
                        nodeIds.count { nodeId ->
                            runCatching {
                                Wearable
                                    .getMessageClient(context)
                                    .sendMessage(nodeId, WearProtocol.STATE_PATH, payload)
                                    .await()
                            }.isSuccess
                        }
                    } ?: 0
                }
            val count = immediate.await()
            durable.await()
            count
        }

    context.recordMobileDiagnostic(
        "SYNC",
        if (immediatePushes > 0) "SYNC-PUSH-200" else "SYNC-PUSH-204",
        if (immediatePushes > 0) "Immediate Watch state push queued" else "Durable Watch state queued; no immediate push completed",
        metadata = mapOf("immediatePushes" to immediatePushes),
    )
}

private const val IMMEDIATE_WATCH_PUSH_TIMEOUT_MS = 1_500L

private fun Context.diagnostics() = getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
