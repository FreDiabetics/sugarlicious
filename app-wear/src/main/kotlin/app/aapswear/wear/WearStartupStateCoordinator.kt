package app.aapswear.wear

import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import app.aapswear.complications.ComplicationUpdatePlanner
import app.aapswear.complications.G7LocalReadingResolver
import app.aapswear.model.Freshness
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import app.aapswear.storage.TherapyStateStore
import kotlinx.coroutines.flow.first

/**
 * Rehydrates every Watch consumer from the already persisted canonical inputs.
 *
 * This is deliberately an invalidation operation, not a measurement event: it never saves a
 * state, advances receivedAt, touches resolver counters, or invokes alarm delivery.
 */
internal object WearStartupStateCoordinator {
    suspend fun rehydrate(context: Context): TherapyDisplayState? {
        val app = context.applicationContext
        val phoneState = TherapyStateStore(app).state.first()
        val resolved = G7LocalReadingResolver.resolve(
            context = app,
            fallback = phoneState,
            nowEpochMs = System.currentTimeMillis(),
            dataSource = WearDisplayPreferences.read(app).dataSource,
        )

        val snapshot = prepareStartupSnapshot(resolved, System.currentTimeMillis())
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
        freshness = TherapyDisplayFormatter.freshness(persisted, nowEpochMs),
    )
