package app.aapswear.wear

import android.content.Context
import app.aapswear.protocol.WatchConfig
import app.aapswear.protocol.WatchGlucoseUnit
import app.aapswear.protocol.WatchGraphColors
import app.aapswear.protocol.WatchColorSync
import app.aapswear.protocol.WatchGraphStyle
import app.aapswear.protocol.WatchUiColors
import app.aapswear.protocol.WatchDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal fun shouldApplyG7CollectorSourceTransition(
    previous: WatchDataSource,
    current: WatchDataSource,
): Boolean = previous != current

internal data class WearDisplayPreferences(
    val graphHours: Int = 3,
    val showPredictions: Boolean = false,
    val glucoseUnit: WatchGlucoseUnit = WatchGlucoseUnit.AAPS,
    val dataSource: WatchDataSource = WatchDataSource.AUTOMATIC,
    val showTherapyStats: Boolean = true,
    val syncedAtEpochMs: Long = 0L,
    val graphColors: WatchGraphColors = WatchGraphColors(),
    val graphStyle: WatchGraphStyle = WatchGraphStyle(),
    val uiColors: WatchUiColors = WatchUiColors(),
) {
    companion object {
        const val PREFS = "watch_display"
        private const val KEY_GRAPH_HOURS = "graph_hours"
        private const val KEY_SHOW_PREDICTIONS = "show_predictions"
        private const val KEY_GLUCOSE_UNIT = "glucose_unit"
        private const val KEY_DATA_SOURCE = "data_source"
        private const val KEY_SHOW_THERAPY_STATS = "show_therapy_stats"
        private const val KEY_SYNCED_AT = "synced_at"
        private const val KEY_LOCAL_CUSTOMIZED = "local_customized"
        private const val COLOR_PREFIX = "graph_color_"
        private const val UI_PREFIX = "ui_color_"
        private const val STYLE_DOT_RADIUS = "cgm_dot_radius_dp"
        private const val STYLE_OUTLINE_ENABLED = "cgm_dot_outline_enabled"
        private const val STYLE_OUTLINE_WIDTH = "cgm_dot_outline_width_dp"
        val allowedGraphHours = listOf(1, 2, 3, 6, 12, 24)

        fun read(context: Context): WearDisplayPreferences {
            val preferences =
                context.getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE,
                )
            val graphDefaults = WatchGraphColors()
            val styleDefaults = WatchGraphStyle()
            val uiDefaults = WatchUiColors()

            val unit =
                runCatching {
                    WatchGlucoseUnit.valueOf(
                        preferences.getString(
                            KEY_GLUCOSE_UNIT,
                            WatchGlucoseUnit.AAPS.name,
                        ) ?: WatchGlucoseUnit.AAPS.name,
                    )
                }.getOrDefault(WatchGlucoseUnit.AAPS)

            return WearDisplayPreferences(
                graphHours =
                    preferences
                        .getInt(KEY_GRAPH_HOURS, 3)
                        .takeIf { it in allowedGraphHours }
                        ?: 3,
                showPredictions =
                    preferences.getBoolean(KEY_SHOW_PREDICTIONS, false),
                glucoseUnit = unit,
                dataSource = runCatching {
                    WatchDataSource.valueOf(
                        preferences.getString(KEY_DATA_SOURCE, WatchDataSource.AUTOMATIC.name)!!,
                    )
                }.getOrDefault(WatchDataSource.AUTOMATIC),
                showTherapyStats =
                    preferences.getBoolean(KEY_SHOW_THERAPY_STATS, true),
                syncedAtEpochMs =
                    preferences.getLong(KEY_SYNCED_AT, 0L),
                graphColors =
                    WatchGraphColors(
                        graphBackground = preferences.getInt(COLOR_PREFIX + "background", graphDefaults.graphBackground),
                        rangeLow = preferences.getInt(COLOR_PREFIX + "range_low", graphDefaults.rangeLow),
                        rangeInRange = preferences.getInt(COLOR_PREFIX + "range_in", graphDefaults.rangeInRange),
                        rangeHigh = preferences.getInt(COLOR_PREFIX + "range_high", graphDefaults.rangeHigh),
                        cgmLow = preferences.getInt(COLOR_PREFIX + "cgm_low", graphDefaults.cgmLow),
                        cgmInRange = preferences.getInt(COLOR_PREFIX + "cgm_in", graphDefaults.cgmInRange),
                        cgmHigh = preferences.getInt(COLOR_PREFIX + "cgm_high", graphDefaults.cgmHigh),
                        divider = preferences.getInt(COLOR_PREFIX + "divider", graphDefaults.divider),
                        outline = preferences.getInt(COLOR_PREFIX + "outline", graphDefaults.outline),
                        predictionIob = preferences.getInt(COLOR_PREFIX + "prediction_iob", graphDefaults.predictionIob),
                        predictionCob = preferences.getInt(COLOR_PREFIX + "prediction_cob", graphDefaults.predictionCob),
                        predictionUam = preferences.getInt(COLOR_PREFIX + "prediction_uam", graphDefaults.predictionUam),
                        predictionZeroTemp = preferences.getInt(COLOR_PREFIX + "prediction_zero_temp", graphDefaults.predictionZeroTemp),
                        targetValue = preferences.getInt(COLOR_PREFIX + "target_value", graphDefaults.targetValue),
                        signalLoss = preferences.getInt(COLOR_PREFIX + "signal_loss", graphDefaults.signalLoss),
                    ),
                graphStyle =
                    WatchGraphStyle(
                        cgmDotRadiusDp =
                            preferences
                                .getFloat(STYLE_DOT_RADIUS, styleDefaults.cgmDotRadiusDp)
                                .coerceIn(1.5f, 6.0f),
                        cgmDotOutlineEnabled =
                            preferences.getBoolean(STYLE_OUTLINE_ENABLED, styleDefaults.cgmDotOutlineEnabled),
                        cgmDotOutlineWidthDp =
                            preferences
                                .getFloat(STYLE_OUTLINE_WIDTH, styleDefaults.cgmDotOutlineWidthDp)
                                .coerceIn(0.25f, 3.0f),
                    ),
                uiColors =
                    WatchUiColors(
                        background = preferences.getInt(UI_PREFIX + "background", uiDefaults.background),
                        tileBackground = preferences.getInt(UI_PREFIX + "tile_background", uiDefaults.tileBackground),
                        tileBorder = preferences.getInt(UI_PREFIX + "tile_border", uiDefaults.tileBorder),
                        textPrimary = preferences.getInt(UI_PREFIX + "text_primary", uiDefaults.textPrimary),
                        textSecondary = preferences.getInt(UI_PREFIX + "text_secondary", uiDefaults.textSecondary),
                        accent = preferences.getInt(UI_PREFIX + "accent", uiDefaults.accent),
                        glucoseLow = preferences.getInt(UI_PREFIX + "glucose_low", uiDefaults.glucoseLow),
                        glucoseInRange = preferences.getInt(UI_PREFIX + "glucose_in_range", uiDefaults.glucoseInRange),
                        glucoseHigh = preferences.getInt(UI_PREFIX + "glucose_high", uiDefaults.glucoseHigh),
                        iob = preferences.getInt(UI_PREFIX + "iob", uiDefaults.iob),
                        cob = preferences.getInt(UI_PREFIX + "cob", uiDefaults.cob),
                        basal = preferences.getInt(UI_PREFIX + "basal", uiDefaults.basal),
                    ),
            )
        }

        /**
         * Applies the phone configuration until the user customizes the Watch locally. Once local
         * settings exist, sync still updates the timestamp but no longer silently overwrites the
         * Watch color pickers, graph scale or dot style every time the Watch app requests data.
         */
        fun save(
            context: Context,
            config: WatchConfig,
        ) {
            val previousSource = read(context).dataSource
            val preferences =
                context.getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE,
                )
            val syncedAt =
                config.sentAtEpochMs.takeIf { it > 0L }
                    ?: System.currentTimeMillis()

            if (preferences.getBoolean(KEY_LOCAL_CUSTOMIZED, false)) {
                preferences.edit()
                    .putLong(KEY_SYNCED_AT, syncedAt)
                    .putString(KEY_DATA_SOURCE, config.dataSource.name)
                    .apply()
                notifyG7CollectorSourceTransition(context, previousSource, config.dataSource)
                return
            }

            write(
                context = context,
                value =
                    WearDisplayPreferences(
                        graphHours = config.graphHours,
                        showPredictions = config.showPredictions,
                        glucoseUnit = config.glucoseUnit,
                        dataSource = config.dataSource,
                        showTherapyStats = config.showTherapyStats,
                        syncedAtEpochMs = syncedAt,
                        graphColors = config.graphColors,
                        graphStyle = config.graphStyle,
                        uiColors = config.uiColors,
                    ),
                markLocal = false,
            )
            notifyG7CollectorSourceTransition(context, previousSource, config.dataSource)
        }

        fun saveLocal(
            context: Context,
            value: WearDisplayPreferences,
        ) {
            write(
                context = context,
                value = value,
                markLocal = true,
            )
        }

        /** Explicit Mobile action: graph colors are authoritative even after local Watch edits. */
        fun applySyncedColors(
            context: Context,
            sync: WatchColorSync,
        ) {
            val colors = sync.graphColors
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_SYNCED_AT, sync.sentAtEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis())
                .putInt(COLOR_PREFIX + "background", colors.graphBackground)
                .putInt(COLOR_PREFIX + "range_low", colors.rangeLow)
                .putInt(COLOR_PREFIX + "range_in", colors.rangeInRange)
                .putInt(COLOR_PREFIX + "range_high", colors.rangeHigh)
                .putInt(COLOR_PREFIX + "cgm_low", colors.cgmLow)
                .putInt(COLOR_PREFIX + "cgm_in", colors.cgmInRange)
                .putInt(COLOR_PREFIX + "cgm_high", colors.cgmHigh)
                .putInt(COLOR_PREFIX + "divider", colors.divider)
                .putInt(COLOR_PREFIX + "outline", colors.outline)
                .putInt(COLOR_PREFIX + "prediction_iob", colors.predictionIob)
                .putInt(COLOR_PREFIX + "prediction_cob", colors.predictionCob)
                .putInt(COLOR_PREFIX + "prediction_uam", colors.predictionUam)
                .putInt(COLOR_PREFIX + "prediction_zero_temp", colors.predictionZeroTemp)
                .putInt(COLOR_PREFIX + "target_value", colors.targetValue)
                .putInt(COLOR_PREFIX + "signal_loss", colors.signalLoss)
                .apply()
        }

        private fun notifyG7CollectorSourceTransition(
            context: Context,
            previous: WatchDataSource,
            current: WatchDataSource,
        ) {
            if (!shouldApplyG7CollectorSourceTransition(previous, current)) return
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                resolveAndPublishCurrentG7AlertMode(context.applicationContext, current)
            }
        }

        private fun write(
            context: Context,
            value: WearDisplayPreferences,
            markLocal: Boolean,
        ) {
            val graphHours =
                value.graphHours.takeIf { it in allowedGraphHours } ?: 3
            val style =
                value.graphStyle.copy(
                    cgmDotRadiusDp = value.graphStyle.cgmDotRadiusDp.coerceIn(1.5f, 6.0f),
                    cgmDotOutlineWidthDp = value.graphStyle.cgmDotOutlineWidthDp.coerceIn(0.25f, 3.0f),
                )

            context
                .getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE,
                )
                .edit()
                .putInt(KEY_GRAPH_HOURS, graphHours)
                .putBoolean(KEY_SHOW_PREDICTIONS, value.showPredictions)
                .putString(KEY_GLUCOSE_UNIT, value.glucoseUnit.name)
                .putString(KEY_DATA_SOURCE, value.dataSource.name)
                .putBoolean(KEY_SHOW_THERAPY_STATS, value.showTherapyStats)
                .putLong(
                    KEY_SYNCED_AT,
                    value.syncedAtEpochMs.takeIf { it > 0L }
                        ?: System.currentTimeMillis(),
                )
                .putBoolean(KEY_LOCAL_CUSTOMIZED, markLocal)
                .putInt(COLOR_PREFIX + "background", value.graphColors.graphBackground)
                .putInt(COLOR_PREFIX + "range_low", value.graphColors.rangeLow)
                .putInt(COLOR_PREFIX + "range_in", value.graphColors.rangeInRange)
                .putInt(COLOR_PREFIX + "range_high", value.graphColors.rangeHigh)
                .putInt(COLOR_PREFIX + "cgm_low", value.graphColors.cgmLow)
                .putInt(COLOR_PREFIX + "cgm_in", value.graphColors.cgmInRange)
                .putInt(COLOR_PREFIX + "cgm_high", value.graphColors.cgmHigh)
                .putInt(COLOR_PREFIX + "divider", value.graphColors.divider)
                .putInt(COLOR_PREFIX + "outline", value.graphColors.outline)
                .putInt(COLOR_PREFIX + "prediction_iob", value.graphColors.predictionIob)
                .putInt(COLOR_PREFIX + "prediction_cob", value.graphColors.predictionCob)
                .putInt(COLOR_PREFIX + "prediction_uam", value.graphColors.predictionUam)
                .putInt(COLOR_PREFIX + "prediction_zero_temp", value.graphColors.predictionZeroTemp)
                .putInt(COLOR_PREFIX + "target_value", value.graphColors.targetValue)
                .putInt(COLOR_PREFIX + "signal_loss", value.graphColors.signalLoss)
                .putFloat(STYLE_DOT_RADIUS, style.cgmDotRadiusDp)
                .putBoolean(STYLE_OUTLINE_ENABLED, style.cgmDotOutlineEnabled)
                .putFloat(STYLE_OUTLINE_WIDTH, style.cgmDotOutlineWidthDp)
                .putInt(UI_PREFIX + "background", value.uiColors.background)
                .putInt(UI_PREFIX + "tile_background", value.uiColors.tileBackground)
                .putInt(UI_PREFIX + "tile_border", value.uiColors.tileBorder)
                .putInt(UI_PREFIX + "text_primary", value.uiColors.textPrimary)
                .putInt(UI_PREFIX + "text_secondary", value.uiColors.textSecondary)
                .putInt(UI_PREFIX + "accent", value.uiColors.accent)
                .putInt(UI_PREFIX + "glucose_low", value.uiColors.glucoseLow)
                .putInt(UI_PREFIX + "glucose_in_range", value.uiColors.glucoseInRange)
                .putInt(UI_PREFIX + "glucose_high", value.uiColors.glucoseHigh)
                .putInt(UI_PREFIX + "iob", value.uiColors.iob)
                .putInt(UI_PREFIX + "cob", value.uiColors.cob)
                .putInt(UI_PREFIX + "basal", value.uiColors.basal)
                .apply()
        }
    }
}
