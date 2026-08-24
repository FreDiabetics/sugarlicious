package app.aapswear.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import app.aapswear.datasource.xdrip.XdripContract
import app.aapswear.datasource.xdrip.XdripPayloadAdapter
import app.aapswear.model.DataSourceId
import app.aapswear.model.Freshness
import app.aapswear.model.FreshnessPolicy
import app.aapswear.model.DiagnosticSeverity
import app.aapswear.storage.PhoneTherapyStateStore
import app.aapswear.storage.TherapyStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class DataSourcePreference { AUTOMATIC, ANDROID_APS, XDRIP_PLUS, DEXCOM_G7_WATCH }

class XdripStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != XdripContract.ACTION) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val now = System.currentTimeMillis()
                val values = mapOf(
                    XdripContract.EXTRA_BG to intent.getDoubleExtra(XdripContract.EXTRA_BG, Double.NaN),
                    XdripContract.EXTRA_SLOPE to intent.getDoubleExtra(XdripContract.EXTRA_SLOPE, Double.NaN),
                    XdripContract.EXTRA_SLOPE_NAME to intent.getStringExtra(XdripContract.EXTRA_SLOPE_NAME),
                    XdripContract.EXTRA_TIME to intent.getLongExtra(XdripContract.EXTRA_TIME, 0L),
                    XdripContract.EXTRA_UNITS to intent.getStringExtra(XdripContract.EXTRA_UNITS),
                    XdripContract.EXTRA_SOURCE to intent.getStringExtra(XdripContract.EXTRA_SOURCE),
                    XdripContract.EXTRA_VERSION to intent.getStringExtra(XdripContract.EXTRA_VERSION),
                )
                val parsed = XdripPayloadAdapter().parse(values, now)
                if (parsed == null) {
                    app.recordMobileDiagnostic("SOURCE", "SRC-XDRIP-401", "xDrip payload could not be decoded", DiagnosticSeverity.WARNING)
                    return@launch
                }
                val prefs = app.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE)
                val preference = runCatching {
                    DataSourcePreference.valueOf(prefs.getString("dataSource", "AUTOMATIC")!!)
                }.getOrDefault(DataSourcePreference.AUTOMATIC)
                if (preference == DataSourcePreference.ANDROID_APS) {
                    app.recordMobileDiagnostic("SOURCE", "SRC-XDRIP-102", "xDrip payload ignored by explicit source selection")
                    return@launch
                }

                val store = TherapyStateStore(app)
                val previous = store.state.first()
                val previousPhone = PhoneTherapyStateStore(app).state.first()
                    ?: previous?.takeUnless { it.source == DataSourceId.DEXCOM_G7_WATCH }
                val aapsIsCurrent = previousPhone?.source == DataSourceId.ANDROID_APS &&
                    FreshnessPolicy.classify(previousPhone.glucose?.measuredAtEpochMs, now) != Freshness.STALE &&
                    FreshnessPolicy.classify(previousPhone.glucose?.measuredAtEpochMs, now) != Freshness.NO_DATA
                if (preference == DataSourcePreference.AUTOMATIC && aapsIsCurrent) {
                    app.recordMobileDiagnostic("SOURCE", "SRC-XDRIP-103", "xDrip payload deferred to current AAPS reading")
                    return@launch
                }

                val preserved = parsed.copy(
                    insulin = previousPhone?.insulin,
                    carbs = previousPhone?.carbs,
                    basal = previousPhone?.basal,
                    target = previousPhone?.target,
                    loop = previousPhone?.loop,
                    pump = previousPhone?.pump,
                    device = previousPhone?.device,
                    profile = previousPhone?.profile,
                    therapyHistory = previousPhone?.therapyHistory.orEmpty(),
                    targetHistory = previousPhone?.targetHistory.orEmpty(),
                    capabilities = parsed.capabilities + previousPhone?.capabilities.orEmpty(),
                )
                val (phoneState, state) = MobileCanonicalStateCoordinator.savePhoneInput(app, preserved, now)
                app.recordMobileDiagnostic(
                    "PREDICTION",
                    if (state.glucosePredictions.isNotEmpty()) "PRED-CACHE-202" else "PRED-CACHE-204",
                    if (state.glucosePredictions.isNotEmpty()) "AAPS predictions retained across xDrip glucose fallback" else "xDrip state contains no cached predictions",
                    metadata = mapOf("displayPredictions" to state.glucosePredictions.size, "historyCount" to state.glucoseHistory.size),
                )
                if (previous?.copy(receivedAtEpochMs = state.receivedAtEpochMs) == state) {
                    app.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).edit {
                        putLong("received", now)
                        putString("lastSyncStatus", "unchanged")
                    }
                    return@launch
                }
                runCatching { HealthConnectIntegration.exportCgmReading(app, state) }
                SugarliciousWidgets.update(app)
                runCatching { publishState(app, phoneState) }
                    .onSuccess { app.recordMobileDiagnostic("SYNC", "SYNC-WATCH-200", "xDrip fallback state published to Watch") }
                    .onFailure { error ->
                        app.recordMobileDiagnostic(
                            "SYNC",
                            "SYNC-WATCH-503",
                            "xDrip fallback state could not be published",
                            DiagnosticSeverity.WARNING,
                            mapOf("error" to error.javaClass.simpleName),
                        )
                    }
                app.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).edit {
                    putLong("received", now)
                    putLong("measurement", state.glucose?.measuredAtEpochMs ?: 0L)
                    putString("contract", state.sourceContract)
                    putString("sourceVersion", state.sourceVersion)
                    putString("sourcePackage", "com.eveningoutpost.dexdrip")
                    putString("lastSyncStatus", "ok")
                }
            } finally {
                pending.finish()
            }
        }
    }
}
