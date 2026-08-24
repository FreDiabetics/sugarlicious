package app.aapswear.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import app.aapswear.datasource.aaps.AapsCapabilityDetector
import app.aapswear.datasource.aaps.AapsPayloadAdapter
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.DiagnosticSeverity
import app.aapswear.protocol.WearProtocol
import app.aapswear.storage.TherapyStateStore
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

internal const val G7_SOURCE_FALLBACK_MIGRATION_KEY = "g7SetupAutomaticFallbackMigratedV1"

internal fun migrateLegacyForcedG7Source(
    current: DataSourcePreference,
    migrationDone: Boolean,
): DataSourcePreference =
    if (!migrationDone && current == DataSourcePreference.DEXCOM_G7_WATCH) {
        DataSourcePreference.AUTOMATIC
    } else {
        current
    }

class AapsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AapsPayloadAdapter.ACTION) return
        val pending = goAsync()
        val app = context.applicationContext

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val now = System.currentTimeMillis()
                val sourcePreferences = app.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE)
                val configuredSource = runCatching {
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
                if (sourcePreference == DataSourcePreference.XDRIP_PLUS) {
                    app.recordMobileDiagnostic("SOURCE", "SRC-AAPS-102", "AAPS payload ignored by explicit source selection")
                    return@launch
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
                val (phoneState, displayState) = MobileCanonicalStateCoordinator.savePhoneInput(app, state, now)
                app.recordMobileDiagnostic(
                    "PREDICTION",
                    if (state.glucosePredictions.isEmpty() && displayState.glucosePredictions.isNotEmpty()) "PRED-CACHE-201" else "PRED-DATA-200",
                    if (state.glucosePredictions.isEmpty() && displayState.glucosePredictions.isNotEmpty()) "Cached predictions retained after an empty AAPS update" else "AAPS state merged",
                    metadata = mapOf(
                        "incomingPredictions" to state.glucosePredictions.size,
                        "displayPredictions" to displayState.glucosePredictions.size,
                        "historyCount" to displayState.glucoseHistory.size,
                    ),
                )

                if (previous?.copy(receivedAtEpochMs = displayState.receivedAtEpochMs) == displayState) {
                    app.diagnostics().edit {
                        putLong("received", now)
                        putString("lastSyncStatus", "unchanged")
                    }
                    return@launch
                }

                runCatching { HealthConnectIntegration.exportCgmReading(app, displayState) }
                SugarliciousWidgets.update(app)
                app.diagnostics().edit {
                    putLong("received", now)
                    putLong("measurement", displayState.glucose?.measuredAtEpochMs ?: 0L)
                    putString("contract", displayState.sourceContract)
                    putString("sourceVersion", displayState.sourceVersion)
                    putString("sourcePackage", installation?.packageName)
                    putLong("sourceVersionCode", installation?.versionCode ?: 0L)
                    putString("lastSyncStatus", "pending")
                }

                runCatching {
                    // The Watch receives the independent phone input. Mobile's canonical state may
                    // contain Watch backfill and must not be reflected back as a fake phone source.
                    withTimeout(4.seconds) { publishState(app, phoneState) }
                }.onSuccess {
                    app.diagnostics().edit {
                        putLong("lastSyncAt", System.currentTimeMillis())
                        putString("lastSyncStatus", "ok")
                        remove("lastSyncError")
                    }
                    app.recordMobileDiagnostic("SYNC", "SYNC-WATCH-200", "State published to Watch")
                }.onFailure { error ->
                    app.diagnostics().edit {
                        putString("lastSyncStatus", "unavailable")
                        putString("lastSyncError", error.javaClass.simpleName)
                    }
                    app.recordMobileDiagnostic(
                        "SYNC",
                        "SYNC-WATCH-503",
                        "State could not be published to Watch",
                        DiagnosticSeverity.WARNING,
                        mapOf("error" to error.javaClass.simpleName),
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}

suspend fun publishState(context: Context, state: TherapyDisplayState) {
    val payload = WearProtocol.encode(state)
    val request = PutDataRequest.create(WearProtocol.STATE_PATH)
        .setData(payload)
        .setUrgent()

    // Keep the DataItem as the durable source of truth. It survives a temporarily disconnected
    // Watch and will synchronize when the Wear network becomes available again.
    Wearable.getDataClient(context).putDataItem(request).await()

    // A DataItem is synchronized by Google Play services and may still arrive later than desired
    // for a five-minute CGM stream. When a Watch node is reachable, send the same payload over the
    // low-latency MessageClient path as well. Failure here must never invalidate the durable item.
    val immediatePushes = withTimeoutOrNull(IMMEDIATE_WATCH_PUSH_TIMEOUT_MS) {
        val nodeIds = runCatching { refreshReachableWatchNodeIds(context) }.getOrDefault(emptyList())
        nodeIds.count { nodeId ->
            runCatching {
                Wearable.getMessageClient(context)
                    .sendMessage(nodeId, WearProtocol.STATE_PATH, payload)
                    .await()
            }.isSuccess
        }
    } ?: 0

    context.recordMobileDiagnostic(
        "SYNC",
        if (immediatePushes > 0) "SYNC-PUSH-200" else "SYNC-PUSH-204",
        if (immediatePushes > 0) "Immediate Watch state push queued" else "Durable Watch state queued; no immediate push completed",
        metadata = mapOf("immediatePushes" to immediatePushes),
    )
}

private const val IMMEDIATE_WATCH_PUSH_TIMEOUT_MS = 1_500L

private fun Context.diagnostics() = getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
