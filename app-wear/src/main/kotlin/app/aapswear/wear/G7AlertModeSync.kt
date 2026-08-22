package app.aapswear.wear

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import app.aapswear.complications.G7LocalReadingResolver
import app.aapswear.model.CgmSourceState
import app.aapswear.model.DataSourceId
import app.aapswear.model.TherapyDisplayState
import app.aapswear.protocol.WatchDataSource
import app.aapswear.storage.TherapyStateStore
import kotlinx.coroutines.flow.first

internal fun shouldEnableG7Alerts(
    selectedSource: WatchDataSource,
    canonicalSource: DataSourceId?,
    resolverState: CgmSourceState?,
): Boolean =
    selectedSource == WatchDataSource.DEXCOM_G7_WATCH ||
        (
            selectedSource == WatchDataSource.AUTOMATIC &&
                (canonicalSource == DataSourceId.DEXCOM_G7_WATCH || resolverState == CgmSourceState.NO_SOURCE)
            )

internal fun publishG7AlertMode(
    context: Context,
    selectedSource: WatchDataSource,
    resolvedState: TherapyDisplayState?,
) {
    val resolverState = G7LocalReadingResolver.sourceState(resolvedState)
    val alarmsEnabled = shouldEnableG7Alerts(selectedSource, resolvedState?.source, resolverState)
    val automaticEnableAt =
        resolvedState
            ?.glucose
            ?.measuredAtEpochMs
            ?.plus(G7LocalReadingResolver.defaultPolicy.mobileFailoverAfterMs + 1L)
            ?.takeIf { selectedSource == WatchDataSource.AUTOMATIC && !alarmsEnabled }
    val intent =
        Intent(ACTION_SET_G7_SOURCE)
            .setComponent(ComponentName(G7_PACKAGE, G7_SOURCE_RECEIVER))
            .putExtra(EXTRA_G7_SELECTED, selectedSource == WatchDataSource.DEXCOM_G7_WATCH)
            .putExtra(EXTRA_G7_ALARMS_ENABLED, alarmsEnabled)
            .putExtra(EXTRA_G7_AUTOMATIC_ENABLE_AT, automaticEnableAt ?: 0L)
    context.sendBroadcast(intent, G7_CONFIG_PERMISSION)
}

internal suspend fun resolveAndPublishCurrentG7AlertMode(
    context: Context,
    selectedSource: WatchDataSource = WearDisplayPreferences.read(context).dataSource,
) {
    val phoneState = TherapyStateStore(context).state.first()
    val resolved =
        G7LocalReadingResolver.resolve(
            context = context,
            fallback = phoneState,
            dataSource = selectedSource,
        )
    publishG7AlertMode(context, selectedSource, resolved)
}

private const val ACTION_SET_G7_SOURCE = "app.aapswear.g7watch.SET_SOURCE"
private const val G7_PACKAGE = "app.aapswear.g7watch"
private const val G7_SOURCE_RECEIVER = "app.aapswear.g7watch.G7SourceControlReceiver"
private const val EXTRA_G7_SELECTED = "g7_selected"
private const val EXTRA_G7_ALARMS_ENABLED = "alarms_enabled"
private const val EXTRA_G7_AUTOMATIC_ENABLE_AT = "automatic_enable_at"
private const val G7_CONFIG_PERMISSION = "app.aapswear.g7watch.permission.CONFIGURE_G7"
