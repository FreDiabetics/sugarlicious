package app.aapswear.mobile

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import app.aapswear.g7.CgmReading
import app.aapswear.model.DataCapability
import app.aapswear.model.DataSourceId
import app.aapswear.model.Trend
import app.aapswear.model.TherapyDisplayState
import app.aapswear.storage.PhoneTherapyStateStore
import app.aapswear.storage.TherapyStateStore
import kotlinx.coroutines.flow.first

/**
 * Direct G7 Watch readings are local-Watch CGM data. They are deliberately not backfilled into
 * Sugarlicious Mobile CGM history. The DataStore delegate is retained only so existing installs can
 * delete the former backfill payload once during migration.
 */
private val Context.mobileG7HistoryDataStore by preferencesDataStore("mobile_g7_backfill")

/** Compatibility shim for pre-migration callers/tests. New Watch CGM is never persisted on Mobile. */
internal class MobileG7BackfillStore(private val context: Context) {
    suspend fun snapshot(): List<CgmReading> = emptyList()

    suspend fun merge(
        incoming: List<CgmReading>,
        nowEpochMs: Long,
    ): Set<String> {
        // Explicitly discard legacy Watch-CGM input. Keep parameters to preserve binary/source
        // compatibility while older Wear clients phase out their former sync requests.
        if (incoming.isNotEmpty() || nowEpochMs > 0L) clear()
        return emptySet()
    }

    suspend fun clear() {
        context.mobileG7HistoryDataStore.edit { it.clear() }
    }
}

internal object MobileWatchCgmMigration {
    private const val PREFS = "mobile_watch_cgm_migration"
    private const val KEY_VERSION = "version"
    private const val VERSION = 1

    suspend fun runOnce(context: Context): Boolean {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, 0) >= VERSION) return false

        MobileG7BackfillStore(app).clear()
        app.getSharedPreferences("mobile_canonical_cgm_resolver", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        val phoneStore = PhoneTherapyStateStore(app)
        val phone = phoneStore.state.first()?.withoutDirectWatchCgm()
        phone?.let { phoneStore.save(it) }

        val displayStore = TherapyStateStore(app)
        val current = displayStore.state.first()
        val replacement = when {
            phone != null -> phone
            current != null -> current.withoutDirectWatchCgm()
            else -> null
        }
        replacement?.let { displayStore.save(it) }

        prefs.edit().putInt(KEY_VERSION, VERSION).apply()
        app.recordMobileDiagnostic(
            module = "G7",
            code = "G7-MIGRATE-200",
            message = "Removed legacy direct Watch G7 CGM from Mobile state/history",
            metadata = mapOf("migrationVersion" to VERSION),
        )
        return true
    }
}

/** Mobile canonical CGM is intentionally phone-only. Watch-direct resolution lives on Wear. */
internal object MobileCanonicalCgmResolver {
    suspend fun resolve(
        context: Context,
        phoneState: TherapyDisplayState?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TherapyDisplayState? {
        MobileWatchCgmMigration.runOnce(context)
        if (nowEpochMs < 0L) return null
        return phoneState?.withoutDirectWatchCgm()
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
            "Direct Watch G7 input is not a Mobile CGM source"
        }

        val phoneStore = PhoneTherapyStateStore(context)
        val priorPhone =
            phoneStore.state.first()
                ?: TherapyStateStore(context).state.first()?.withoutDirectWatchCgm()

        var mergedPhone = DisplayHistoryAccumulator.merge(priorPhone, incoming, nowEpochMs)
            .withoutDirectWatchCgm()
        val glucose = mergedPhone.glucose
        if (glucose != null && glucose.trend == Trend.UNKNOWN) {
            mergedPhone =
                mergedPhone.copy(
                    glucose =
                        glucose.copy(
                            trend =
                                TrendArrowResolver.resolve(
                                    glucose.trend,
                                    mergedPhone.glucoseHistory,
                                    glucose.measuredAtEpochMs,
                                ),
                        ),
                )
        }

        phoneStore.save(mergedPhone)
        TherapyStateStore(context).save(mergedPhone)
        return mergedPhone to mergedPhone
    }

    /**
     * Legacy compatibility entry point. It no longer reads Watch backfill; it only republishes a
     * sanitized phone state and may return null when no phone state exists.
     */
    suspend fun refreshFromWatchBackfill(
        context: Context,
        nowEpochMs: Long,
    ): TherapyDisplayState? {
        MobileWatchCgmMigration.runOnce(context)
        if (nowEpochMs < 0L) return null
        val phone =
            PhoneTherapyStateStore(context).state.first()
                ?: TherapyStateStore(context).state.first()?.withoutDirectWatchCgm()
        val sanitized = phone?.withoutDirectWatchCgm() ?: return null
        TherapyStateStore(context).save(sanitized)
        return sanitized
    }
}

internal fun TherapyDisplayState.withoutDirectWatchCgm(): TherapyDisplayState {
    val filteredHistory = glucoseHistory.filter { sample -> sample.source != DataSourceId.DEXCOM_G7_WATCH }
    val currentIsWatch =
        source == DataSourceId.DEXCOM_G7_WATCH ||
            glucose?.source == DataSourceId.DEXCOM_G7_WATCH
    val safeGlucose = glucose?.takeUnless { it.source == DataSourceId.DEXCOM_G7_WATCH || source == DataSourceId.DEXCOM_G7_WATCH }
    val safeSource = if (source == DataSourceId.DEXCOM_G7_WATCH) DataSourceId.OTHER else source
    val safeCapabilities =
        if (safeGlucose == null && currentIsWatch) {
            capabilities - setOf(
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
