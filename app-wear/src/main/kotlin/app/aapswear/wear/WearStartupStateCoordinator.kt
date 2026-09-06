package app.aapswear.wear

import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import app.aapswear.complications.ComplicationUpdatePlanner
import app.aapswear.model.DataCapability
import app.aapswear.model.DataSourceId
import app.aapswear.model.Freshness
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import app.aapswear.storage.TherapyStateStore
import kotlinx.coroutines.flow.first

/**
 * Rehydrates Sugarlicious Wear from its persisted phone-fed state.
 *
 * Direct-to-Watch is a separate application and is not a Sugarlicious CGM input. Legacy
 * DEXCOM_G7_WATCH rows written by the temporary bridge are removed during rehydration so they
 * cannot survive reboot/app update and appear as a second graph series.
 */
internal object WearStartupStateCoordinator {
    suspend fun rehydrate(context: Context): TherapyDisplayState? {
        val app = context.applicationContext
        val store = TherapyStateStore(app)
        val persisted = store.state.first()
        val sanitized = persisted?.withoutDirectToWatchInput()
        if (persisted != null && sanitized != persisted) {
            sanitized?.let { store.save(it) }
        }

        val snapshot = prepareStartupSnapshot(sanitized, System.currentTimeMillis())
        WearCanonicalStateEvents.publishLocalReadingUpdate()
        ComplicationUpdatePlanner.allManagedProviders.forEach { provider ->
            ComplicationDataSourceUpdateRequester
                .create(app, ComponentName(app, provider))
                .requestUpdateAll()
        }
        requestSugarliciousTileUpdates(app)
        return snapshot.state
    }
}

private fun TherapyDisplayState.withoutDirectToWatchInput(): TherapyDisplayState {
    val filteredHistory = glucoseHistory.filter { it.source != DataSourceId.DEXCOM_G7_WATCH }
    val currentIsDirect =
        source == DataSourceId.DEXCOM_G7_WATCH || glucose?.source == DataSourceId.DEXCOM_G7_WATCH
    val safeGlucose = glucose?.takeUnless { currentIsDirect || it.source == DataSourceId.DEXCOM_G7_WATCH }
    val safeCapabilities = if (currentIsDirect && safeGlucose == null) {
        capabilities - setOf(
            DataCapability.GLUCOSE,
            DataCapability.TREND,
            DataCapability.DELTA,
            DataCapability.AVERAGE_DELTA,
        )
    } else capabilities

    return copy(
        source = if (source == DataSourceId.DEXCOM_G7_WATCH) DataSourceId.OTHER else source,
        sourceVersion = if (currentIsDirect) null else sourceVersion,
        sourceContract = if (currentIsDirect) "WEAR_PHONE_ONLY:NO_DIRECT_WATCH_CGM" else sourceContract,
        glucose = safeGlucose,
        glucoseHistory = filteredHistory,
        capabilities = safeCapabilities,
    )
}

internal data class StartupStateSnapshot(
    val state: TherapyDisplayState?,
    val freshness: Freshness,
)

/** Re-evaluates time-derived presentation without mutating measurement identity. */
internal fun prepareStartupSnapshot(
    persisted: TherapyDisplayState?,
    nowEpochMs: Long,
): StartupStateSnapshot =
    StartupStateSnapshot(
        state = persisted,
        freshness =
            persisted?.let { TherapyDisplayFormatter.freshness(it, nowEpochMs) }
                ?: Freshness.NO_DATA,
    )
