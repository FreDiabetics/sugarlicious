package app.aapswear.wear

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import app.aapswear.complications.ComplicationUpdatePlanner
import app.aapswear.complications.G7LocalReadingResolver
import app.aapswear.model.DiagnosticSeverity
import app.aapswear.storage.TherapyStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal fun g7ReadingUpdateApplicationContext(context: Context): Context = context.applicationContext

/** Refreshes local Watch CGM consumers immediately after the standalone G7 app stores a reading. */
class G7ReadingUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_G7_READING_UPDATED) return

        // Android wraps manifest receivers in a restricted Context that must not bind services.
        // Tile refresh can bind to System UI, so all asynchronous receiver work uses the
        // unrestricted process-wide application Context instead.
        val appContext = g7ReadingUpdateApplicationContext(context)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching {
                    ComplicationUpdatePlanner.allManagedProviders.forEach { provider ->
                        ComplicationDataSourceUpdateRequester
                            .create(appContext, ComponentName(appContext, provider))
                            .requestUpdateAll()
                    }
                }.onFailure { error ->
                    appContext.recordWatchDiagnostic(
                        module = "UI",
                        code = "COMP-REFRESH-503",
                        message = "Complication refresh after direct G7 reading failed",
                        severity = DiagnosticSeverity.WARNING,
                        metadata = mapOf("error" to error.javaClass.simpleName),
                    )
                }
                runCatching { requestSugarliciousTileUpdates(appContext) }
                    .onFailure { error ->
                        appContext.recordWatchDiagnostic(
                            module = "UI",
                            code = "TILE-REFRESH-503",
                            message = "Tile refresh after direct G7 reading failed",
                            severity = DiagnosticSeverity.WARNING,
                            metadata = mapOf("error" to error.javaClass.simpleName),
                        )
                    }

                // TherapyStateStore remains the phone-fed source store. The direct G7 DB is a
                // separate Watch-local input to the canonical resolver; it is never copied into
                // Mobile CGM history.
                val phoneState = TherapyStateStore(appContext).state.first()
                val source = WearDisplayPreferences.read(appContext).dataSource
                val resolved =
                    G7LocalReadingResolver.resolve(
                        context = appContext,
                        fallback = phoneState,
                        dataSource = source,
                    )
                val sourceState = G7LocalReadingResolver.sourceState(resolved)
                publishG7AlertMode(appContext, source, resolved)
                appContext.recordWatchDiagnostic(
                    module = "SOURCE",
                    code = "SRC-RESOLVE-200",
                    message = "Canonical Watch CGM source resolved after local direct G7 reading",
                    metadata =
                        mapOf(
                            "state" to sourceState?.name,
                            "canonicalSource" to resolved?.source?.name,
                            "mobileBackfill" to false,
                        ),
                )
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val ACTION_G7_READING_UPDATED = "app.aapswear.g7watch.READING_UPDATED"
    }
}
