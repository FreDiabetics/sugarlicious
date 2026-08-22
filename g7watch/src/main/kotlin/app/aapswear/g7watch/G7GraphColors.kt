package app.aapswear.g7watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.aapswear.protocol.WatchColorSync
import app.aapswear.protocol.WatchGraphColors
import app.aapswear.protocol.WearProtocol

internal class G7GraphColorStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun read(): WatchGraphColors {
        val defaults = WatchGraphColors()
        return WatchGraphColors(
            graphBackground = preferences.getInt("background", defaults.graphBackground),
            rangeLow = preferences.getInt("range_low", defaults.rangeLow),
            rangeInRange = preferences.getInt("range_in", defaults.rangeInRange),
            rangeHigh = preferences.getInt("range_high", defaults.rangeHigh),
            cgmLow = preferences.getInt("cgm_low", defaults.cgmLow),
            cgmInRange = preferences.getInt("cgm_in", defaults.cgmInRange),
            cgmHigh = preferences.getInt("cgm_high", defaults.cgmHigh),
            divider = preferences.getInt("divider", defaults.divider),
            outline = preferences.getInt("outline", defaults.outline),
            predictionIob = preferences.getInt("prediction_iob", defaults.predictionIob),
            predictionCob = preferences.getInt("prediction_cob", defaults.predictionCob),
            predictionUam = preferences.getInt("prediction_uam", defaults.predictionUam),
            predictionZeroTemp = preferences.getInt("prediction_zero_temp", defaults.predictionZeroTemp),
            targetValue = preferences.getInt("target_value", defaults.targetValue),
            signalLoss = preferences.getInt("signal_loss", defaults.signalLoss),
        )
    }

    fun save(sync: WatchColorSync) {
        val colors = sync.graphColors
        preferences.edit()
            .putInt("background", colors.graphBackground)
            .putInt("range_low", colors.rangeLow)
            .putInt("range_in", colors.rangeInRange)
            .putInt("range_high", colors.rangeHigh)
            .putInt("cgm_low", colors.cgmLow)
            .putInt("cgm_in", colors.cgmInRange)
            .putInt("cgm_high", colors.cgmHigh)
            .putInt("divider", colors.divider)
            .putInt("outline", colors.outline)
            .putInt("prediction_iob", colors.predictionIob)
            .putInt("prediction_cob", colors.predictionCob)
            .putInt("prediction_uam", colors.predictionUam)
            .putInt("prediction_zero_temp", colors.predictionZeroTemp)
            .putInt("target_value", colors.targetValue)
            .putInt("signal_loss", colors.signalLoss)
            .putLong("synced_at", sync.sentAtEpochMs)
            .apply()
    }

    private companion object {
        const val PREFERENCES = "g7_graph_colors"
    }
}

class G7ColorSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_APPLY) return
        val payload = intent.getByteArrayExtra(EXTRA_PAYLOAD) ?: return
        val sync = runCatching { WearProtocol.decodeWatchColorSync(payload) }.getOrNull() ?: return
        G7GraphColorStore(context).save(sync)
        G7CollectorTileService.requestUpdate(context)
    }

    companion object {
        const val ACTION_APPLY = "app.aapswear.g7watch.APPLY_GRAPH_COLORS"
        const val EXTRA_PAYLOAD = "color_payload"
    }
}
