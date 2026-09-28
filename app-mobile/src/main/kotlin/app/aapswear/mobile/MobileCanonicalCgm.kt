package app.aapswear.mobile

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import app.aapswear.model.DataCapability
import app.aapswear.model.DataSourceId
import app.aapswear.model.GlucoseSample
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
import app.aapswear.model.TrendDiagnostics
import app.aapswear.storage.CanonicalStateStore
import app.aapswear.storage.PhoneTherapyStateStore
import app.aapswear.storage.TherapyStateStore
import kotlinx.coroutines.flow.first

/**
 * Legacy Mobile store kept only to delete data written by the short-lived Watch-backfill bridge.
 * SugarWear history is collector-local and must never be a Sugarlicious Mobile input.
 */
private val Context.mobileG7HistoryDataStore by preferencesDataStore("mobile_g7_backfill")

private suspend fun clearLegacyMobileG7History(context: Context) {
    context.mobileG7HistoryDataStore.edit { it.clear() }
}

internal object MobileWatchCgmMigration {
    private const val PREFS = "mobile_watch_cgm_migration"
    private const val KEY_VERSION = "version"
    private const val VERSION = 2

    suspend fun runOnce(context: Context): Boolean {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, 0) >= VERSION) return false

        clearLegacyMobileG7History(app)
        app
            .getSharedPreferences("mobile_canonical_cgm_resolver", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        val phoneStore = PhoneTherapyStateStore(app)
        val phone = phoneStore.state.first()?.withoutDirectWatchCgm()
        val displayStore = TherapyStateStore(app)
        val current = displayStore.state.first()
        val replacement =
            when {
                phone != null -> phone
                current != null -> current.withoutDirectWatchCgm()
                else -> null
            }
        replacement?.let { CanonicalStateStore(app).commit(it) }

        prefs.edit().putInt(KEY_VERSION, VERSION).apply()
        app.recordMobileDiagnostic(
            module = "G7",
            code = "G7-MIGRATE-200",
            message = "Removed SugarWear CGM/history from Sugarlicious Mobile",
            metadata = mapOf("migrationVersion" to VERSION),
        )
        return true
    }
}

/** Sugarlicious Mobile canonical CGM is phone-source only. */
internal object MobileCanonicalCgmResolver {
    suspend fun resolve(
        context: Context,
        phoneState: TherapyDisplayState?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TherapyDisplayState? {
        MobileWatchCgmMigration.runOnce(context)
        return phoneState?.mobileAndroidApsOnly()
    }
}

internal object MobileCanonicalStateCoordinator {
    suspend fun savePhoneInput(
        context: Context,
        incoming: TherapyDisplayState,
        nowEpochMs: Long,
    ): Pair<TherapyDisplayState, TherapyDisplayState> {
        MobileWatchCgmMigration.runOnce(context)
        require(incoming.source != DataSourceId.DEXCOM_G7_WATCH) {
            "SugarWear input is not a Sugarlicious Mobile CGM source"
        }

        val canonicalStore = CanonicalStateStore(context)
        val priorPhone = canonicalStore.reconcile()

        var mergedPhone =
            DisplayHistoryAccumulator
                .merge(priorPhone, incoming, nowEpochMs)
                .withoutDirectWatchCgm()
        val glucose = mergedPhone.glucose
        if (glucose != null && glucose.trend == Trend.UNKNOWN) {
            val resolution =
                TrendArrowResolver.resolveWithRate(
                    glucose.trend,
                    glucose,
                    mergedPhone.glucoseHistory,
                )
            mergedPhone =
                mergedPhone.copy(
                    glucose =
                        glucose.copy(
                            trend = resolution.trend,
                            trendRateMgDlPerMinute = resolution.rateMgDlPerMinute,
                        ),
                )
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                Log.d(
                    "CgmTrendPipeline",
                    TrendDiagnostics.format(
                        glucoseMgDl = glucose.valueMgDl,
                        deltaMgDl = glucose.deltaMgDl,
                        elapsedMinutes =
                            resolution.rateMgDlPerMinute
                                ?.takeIf { it != 0.0 }
                                ?.let { glucose.deltaMgDl?.div(it) },
                        rateMgDlPerMinute = resolution.rateMgDlPerMinute,
                        sourceTrend = glucose.trend,
                        canonicalTrend = resolution.trend,
                        source = glucose.source,
                    ),
                )
            }
        }

        val committed = canonicalStore.commit(mergedPhone)
        dispatchCanonicalDataChanged(context, committed)
        return committed to committed
    }
}

internal fun TherapyDisplayState.withoutDirectWatchCgm(): TherapyDisplayState {
    val filteredHistory = glucoseHistory.filter { sample -> sample.source != DataSourceId.DEXCOM_G7_WATCH }
    val currentIsWatch =
        source == DataSourceId.DEXCOM_G7_WATCH ||
            glucose?.source == DataSourceId.DEXCOM_G7_WATCH
    val safeGlucose =
        glucose?.takeUnless {
            it.source == DataSourceId.DEXCOM_G7_WATCH || source == DataSourceId.DEXCOM_G7_WATCH
        }
    val safeSource = if (source == DataSourceId.DEXCOM_G7_WATCH) DataSourceId.ANDROID_APS else source
    val safeCapabilities =
        if (safeGlucose == null && currentIsWatch) {
            capabilities -
                setOf(
                    DataCapability.GLUCOSE,
                    DataCapability.TREND,
                    DataCapability.DELTA,
                    DataCapability.AVERAGE_DELTA,
                )
        } else {
            capabilities
        }

    return copy(
        source = safeSource,
        sourceVersion = if (currentIsWatch) null else sourceVersion,
        sourceContract = if (currentIsWatch) "MOBILE_PHONE_ONLY:NO_WATCH_CGM" else sourceContract,
        glucose = safeGlucose,
        glucoseHistory = filteredHistory,
        capabilities = safeCapabilities,
    )
}

internal fun TherapyDisplayState.mobileAndroidApsOnly(): TherapyDisplayState =
    withoutDirectWatchCgm().copy(
        sourceContract = "MOBILE_ANDROIDAPS_ONLY",
        glucoseHistory = glucoseHistory.filter { it.source == DataSourceId.ANDROID_APS }.sortedBy(GlucoseSample::measuredAtEpochMs),
    )
