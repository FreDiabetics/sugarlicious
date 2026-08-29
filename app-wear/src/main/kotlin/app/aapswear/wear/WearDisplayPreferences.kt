package app.aapswear.wear

import android.content.Context
import android.content.res.Configuration
import app.aapswear.model.AppearanceMode
import app.aapswear.protocol.WatchConfig
import app.aapswear.protocol.WatchGlucoseUnit
import app.aapswear.protocol.WatchGraphColors
import app.aapswear.protocol.WatchColorSync
import app.aapswear.protocol.WatchGraphStyle
import app.aapswear.protocol.WatchUiColors
import app.aapswear.protocol.WatchAppearanceProfile
import app.aapswear.protocol.WatchDataSource
import app.aapswear.model.CgmThresholds
import app.aapswear.model.GlucoseTrendSizing
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
    val cgmThresholds: CgmThresholds = CgmThresholds.DEFAULT,
    val glucoseScalePercent: Int = GlucoseTrendSizing.DEFAULT_SCALE_PERCENT,
    val trendScalePercent: Int = GlucoseTrendSizing.DEFAULT_SCALE_PERCENT,
) {
    companion object {
        const val PREFS = "watch_display"
        private const val KEY_GRAPH_HOURS = "graph_hours"
        private const val KEY_SHOW_PREDICTIONS = "show_predictions"
        private const val KEY_GLUCOSE_UNIT = "glucose_unit"
        private const val KEY_DATA_SOURCE = "data_source"
        private const val KEY_SHOW_THERAPY_STATS = "show_therapy_stats"
        private const val KEY_GLUCOSE_SCALE = "glucose_scale_percent"
        private const val KEY_TREND_SCALE = "trend_scale_percent"
        private const val KEY_SYNCED_AT = "synced_at"
        private const val KEY_LOCAL_CUSTOMIZED = "local_customized"
        private const val COLOR_PREFIX = "graph_color_"
        private const val UI_PREFIX = "ui_color_"
        private const val STYLE_DOT_RADIUS = "cgm_dot_radius_dp"
        private const val STYLE_OUTLINE_ENABLED = "cgm_dot_outline_enabled"
        private const val STYLE_OUTLINE_WIDTH = "cgm_dot_outline_width_dp"
        private const val THRESHOLD_VERY_HIGH = "threshold_very_high"
        private const val THRESHOLD_HIGH = "threshold_high"
        private const val THRESHOLD_LOW = "threshold_low"
        private const val THRESHOLD_VERY_LOW = "threshold_very_low"
        val allowedGraphHours = listOf(1, 2, 3, 6, 12, 24)

        fun activeAppearanceMode(context: Context): AppearanceMode =
            if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) {
                AppearanceMode.DARK
            } else AppearanceMode.LIGHT

        private fun appearancePrefix(mode: AppearanceMode) = "appearance.${mode.storageKey}."

        fun read(context: Context): WearDisplayPreferences = read(context, activeAppearanceMode(context))

        fun read(context: Context, mode: AppearanceMode): WearDisplayPreferences {
            val preferences =
                context.getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE,
                )
            migrateAppearanceProfiles(preferences)
            val prefix = appearancePrefix(mode)
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
                glucoseScalePercent = preferences.getInt(KEY_GLUCOSE_SCALE, GlucoseTrendSizing.DEFAULT_SCALE_PERCENT)
                    .coerceIn(GlucoseTrendSizing.MIN_SCALE_PERCENT, GlucoseTrendSizing.MAX_SCALE_PERCENT),
                trendScalePercent = preferences.getInt(KEY_TREND_SCALE, GlucoseTrendSizing.DEFAULT_SCALE_PERCENT)
                    .coerceIn(GlucoseTrendSizing.MIN_SCALE_PERCENT, GlucoseTrendSizing.MAX_SCALE_PERCENT),
                syncedAtEpochMs =
                    preferences.getLong(KEY_SYNCED_AT, 0L),
                graphColors =
                    WatchGraphColors(
                        graphBackground = preferences.getInt(prefix + COLOR_PREFIX + "background", graphDefaults.graphBackground),
                        rangeLow = preferences.getInt(prefix + COLOR_PREFIX + "range_low", graphDefaults.rangeLow),
                        rangeInRange = preferences.getInt(prefix + COLOR_PREFIX + "range_in", graphDefaults.rangeInRange),
                        rangeHigh = preferences.getInt(prefix + COLOR_PREFIX + "range_high", graphDefaults.rangeHigh),
                        cgmLow = preferences.getInt(prefix + COLOR_PREFIX + "cgm_low", graphDefaults.cgmLow),
                        cgmInRange = preferences.getInt(prefix + COLOR_PREFIX + "cgm_in", graphDefaults.cgmInRange),
                        cgmHigh = preferences.getInt(prefix + COLOR_PREFIX + "cgm_high", graphDefaults.cgmHigh),
                        cgmVeryLow = preferences.getInt(prefix + COLOR_PREFIX + "cgm_very_low", graphDefaults.cgmVeryLow),
                        cgmVeryHigh = preferences.getInt(prefix + COLOR_PREFIX + "cgm_very_high", graphDefaults.cgmVeryHigh),
                        divider = preferences.getInt(prefix + COLOR_PREFIX + "divider", graphDefaults.divider),
                        highLine = preferences.getInt(prefix + COLOR_PREFIX + "high_line", graphDefaults.highLine),
                        lowLine = preferences.getInt(prefix + COLOR_PREFIX + "low_line", graphDefaults.lowLine),
                        axisLabel = preferences.getInt(prefix + COLOR_PREFIX + "axis_label", graphDefaults.axisLabel),
                        axisTick = preferences.getInt(prefix + COLOR_PREFIX + "axis_tick", graphDefaults.axisTick),
                        nowLine = preferences.getInt(prefix + COLOR_PREFIX + "now_line", graphDefaults.nowLine),
                        outline = preferences.getInt(prefix + COLOR_PREFIX + "outline", graphDefaults.outline),
                        predictionIob = preferences.getInt(prefix + COLOR_PREFIX + "prediction_iob", graphDefaults.predictionIob),
                        predictionCob = preferences.getInt(prefix + COLOR_PREFIX + "prediction_cob", graphDefaults.predictionCob),
                        predictionUam = preferences.getInt(prefix + COLOR_PREFIX + "prediction_uam", graphDefaults.predictionUam),
                        predictionZeroTemp = preferences.getInt(prefix + COLOR_PREFIX + "prediction_zero_temp", graphDefaults.predictionZeroTemp),
                        targetValue = preferences.getInt(prefix + COLOR_PREFIX + "target_value", graphDefaults.targetValue),
                        signalLoss = preferences.getInt(prefix + COLOR_PREFIX + "signal_loss", graphDefaults.signalLoss),
                    ),
                graphStyle =
                    WatchGraphStyle(
                        cgmDotRadiusDp =
                            preferences
                                .getFloat(prefix + STYLE_DOT_RADIUS, styleDefaults.cgmDotRadiusDp)
                                .coerceIn(1.5f, 6.0f),
                        cgmDotOutlineEnabled =
                            preferences.getBoolean(prefix + STYLE_OUTLINE_ENABLED, styleDefaults.cgmDotOutlineEnabled),
                        cgmDotOutlineWidthDp =
                            preferences
                                .getFloat(prefix + STYLE_OUTLINE_WIDTH, styleDefaults.cgmDotOutlineWidthDp)
                                .coerceIn(0.25f, 3.0f),
                    ),
                uiColors =
                    WatchUiColors(
                        background = preferences.getInt(prefix + UI_PREFIX + "background", uiDefaults.background),
                        tileBackground = preferences.getInt(prefix + UI_PREFIX + "tile_background", uiDefaults.tileBackground),
                        tileBorder = preferences.getInt(prefix + UI_PREFIX + "tile_border", uiDefaults.tileBorder),
                        textPrimary = preferences.getInt(prefix + UI_PREFIX + "text_primary", uiDefaults.textPrimary),
                        textSecondary = preferences.getInt(prefix + UI_PREFIX + "text_secondary", uiDefaults.textSecondary),
                        accent = preferences.getInt(prefix + UI_PREFIX + "accent", uiDefaults.accent),
                        glucoseLow = preferences.getInt(prefix + UI_PREFIX + "glucose_low", uiDefaults.glucoseLow),
                        glucoseInRange = preferences.getInt(prefix + UI_PREFIX + "glucose_in_range", uiDefaults.glucoseInRange),
                        glucoseHigh = preferences.getInt(prefix + UI_PREFIX + "glucose_high", uiDefaults.glucoseHigh),
                        glucoseVeryLow = preferences.getInt(prefix + UI_PREFIX + "glucose_very_low", uiDefaults.glucoseVeryLow),
                        glucoseVeryHigh = preferences.getInt(prefix + UI_PREFIX + "glucose_very_high", uiDefaults.glucoseVeryHigh),
                        iob = preferences.getInt(prefix + UI_PREFIX + "iob", uiDefaults.iob),
                        cob = preferences.getInt(prefix + UI_PREFIX + "cob", uiDefaults.cob),
                        basal = preferences.getInt(prefix + UI_PREFIX + "basal", uiDefaults.basal),
                    ),
                cgmThresholds = CgmThresholds(
                    veryHighMgDl = preferences.getFloat(THRESHOLD_VERY_HIGH, 250f).toDouble(),
                    highMgDl = preferences.getFloat(THRESHOLD_HIGH, 180f).toDouble(),
                    lowMgDl = preferences.getFloat(THRESHOLD_LOW, 70f).toDouble(),
                    veryLowMgDl = preferences.getFloat(THRESHOLD_VERY_LOW, 50f).toDouble(),
                ).takeIf(CgmThresholds::isValid) ?: CgmThresholds.DEFAULT,
            )
        }

        /**
         * Records operational sync metadata without changing this app's display settings.
         *
         * Wear appearance, graph scale, units and visibility options are independent local state.
         * A phone may still provide data and select the active source, but routine synchronization
         * must never act as a remote appearance editor.
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

            preferences.edit()
                .putLong(KEY_SYNCED_AT, syncedAt)
                .putString(KEY_DATA_SOURCE, config.dataSource.name)
                .apply()
            notifyG7CollectorSourceTransition(context, previousSource, config.dataSource)
        }

        fun saveLocal(
            context: Context,
            value: WearDisplayPreferences,
        ) = saveLocal(context, activeAppearanceMode(context), value)

        fun saveLocal(
            context: Context,
            mode: AppearanceMode,
            value: WearDisplayPreferences,
        ) {
            write(
                context = context,
                mode = mode,
                value = value,
                markLocal = true,
            )
        }

        /** Explicit Mobile action: graph colors are authoritative even after local Watch edits. */
        fun applySyncedColors(
            context: Context,
            sync: WatchColorSync,
        ) {
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val fallback = WatchAppearanceProfile(graphColors = sync.graphColors)
            preferences.edit().apply {
                putLong(KEY_SYNCED_AT, sync.sentAtEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis())
                putFloat(THRESHOLD_VERY_HIGH, sync.cgmThresholds.veryHighMgDl.toFloat())
                putFloat(THRESHOLD_HIGH, sync.cgmThresholds.highMgDl.toFloat())
                putFloat(THRESHOLD_LOW, sync.cgmThresholds.lowMgDl.toFloat())
                putFloat(THRESHOLD_VERY_LOW, sync.cgmThresholds.veryLowMgDl.toFloat())
                putAppearanceProfile(AppearanceMode.LIGHT, sync.lightProfile ?: fallback)
                putAppearanceProfile(AppearanceMode.DARK, sync.darkProfile ?: fallback)
            }.apply()
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
            mode: AppearanceMode,
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

            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                putInt(KEY_GRAPH_HOURS, graphHours)
                putBoolean(KEY_SHOW_PREDICTIONS, value.showPredictions)
                putString(KEY_GLUCOSE_UNIT, value.glucoseUnit.name)
                putString(KEY_DATA_SOURCE, value.dataSource.name)
                putBoolean(KEY_SHOW_THERAPY_STATS, value.showTherapyStats)
                putInt(KEY_GLUCOSE_SCALE, value.glucoseScalePercent.coerceIn(GlucoseTrendSizing.MIN_SCALE_PERCENT, GlucoseTrendSizing.MAX_SCALE_PERCENT))
                putInt(KEY_TREND_SCALE, value.trendScalePercent.coerceIn(GlucoseTrendSizing.MIN_SCALE_PERCENT, GlucoseTrendSizing.MAX_SCALE_PERCENT))
                putLong(KEY_SYNCED_AT, value.syncedAtEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis())
                putBoolean(KEY_LOCAL_CUSTOMIZED, markLocal)
                putFloat(THRESHOLD_VERY_HIGH, value.cgmThresholds.veryHighMgDl.toFloat())
                putFloat(THRESHOLD_HIGH, value.cgmThresholds.highMgDl.toFloat())
                putFloat(THRESHOLD_LOW, value.cgmThresholds.lowMgDl.toFloat())
                putFloat(THRESHOLD_VERY_LOW, value.cgmThresholds.veryLowMgDl.toFloat())
                putAppearanceProfile(mode, WatchAppearanceProfile(value.graphColors, style, value.uiColors))
            }.apply()
        }

        private fun android.content.SharedPreferences.Editor.putAppearanceProfile(
            mode: AppearanceMode,
            profile: WatchAppearanceProfile,
        ) {
            val prefix = appearancePrefix(mode)
            val colors = profile.graphColors
            putInt(prefix + COLOR_PREFIX + "background", colors.graphBackground)
            putInt(prefix + COLOR_PREFIX + "range_low", colors.rangeLow)
            putInt(prefix + COLOR_PREFIX + "range_in", colors.rangeInRange)
            putInt(prefix + COLOR_PREFIX + "range_high", colors.rangeHigh)
            putInt(prefix + COLOR_PREFIX + "cgm_low", colors.cgmLow)
            putInt(prefix + COLOR_PREFIX + "cgm_in", colors.cgmInRange)
            putInt(prefix + COLOR_PREFIX + "cgm_high", colors.cgmHigh)
            putInt(prefix + COLOR_PREFIX + "cgm_very_low", colors.cgmVeryLow)
            putInt(prefix + COLOR_PREFIX + "cgm_very_high", colors.cgmVeryHigh)
            putInt(prefix + COLOR_PREFIX + "divider", colors.divider)
            putInt(prefix + COLOR_PREFIX + "high_line", colors.highLine)
            putInt(prefix + COLOR_PREFIX + "low_line", colors.lowLine)
            putInt(prefix + COLOR_PREFIX + "axis_label", colors.axisLabel)
            putInt(prefix + COLOR_PREFIX + "axis_tick", colors.axisTick)
            putInt(prefix + COLOR_PREFIX + "now_line", colors.nowLine)
            putInt(prefix + COLOR_PREFIX + "outline", colors.outline)
            putInt(prefix + COLOR_PREFIX + "prediction_iob", colors.predictionIob)
            putInt(prefix + COLOR_PREFIX + "prediction_cob", colors.predictionCob)
            putInt(prefix + COLOR_PREFIX + "prediction_uam", colors.predictionUam)
            putInt(prefix + COLOR_PREFIX + "prediction_zero_temp", colors.predictionZeroTemp)
            putInt(prefix + COLOR_PREFIX + "target_value", colors.targetValue)
            putInt(prefix + COLOR_PREFIX + "signal_loss", colors.signalLoss)
            putFloat(prefix + STYLE_DOT_RADIUS, profile.graphStyle.cgmDotRadiusDp)
            putBoolean(prefix + STYLE_OUTLINE_ENABLED, profile.graphStyle.cgmDotOutlineEnabled)
            putFloat(prefix + STYLE_OUTLINE_WIDTH, profile.graphStyle.cgmDotOutlineWidthDp)
            val ui = profile.uiColors
            putInt(prefix + UI_PREFIX + "background", ui.background)
            putInt(prefix + UI_PREFIX + "tile_background", ui.tileBackground)
            putInt(prefix + UI_PREFIX + "tile_border", ui.tileBorder)
            putInt(prefix + UI_PREFIX + "text_primary", ui.textPrimary)
            putInt(prefix + UI_PREFIX + "text_secondary", ui.textSecondary)
            putInt(prefix + UI_PREFIX + "accent", ui.accent)
            putInt(prefix + UI_PREFIX + "glucose_low", ui.glucoseLow)
            putInt(prefix + UI_PREFIX + "glucose_in_range", ui.glucoseInRange)
            putInt(prefix + UI_PREFIX + "glucose_high", ui.glucoseHigh)
            putInt(prefix + UI_PREFIX + "glucose_very_low", ui.glucoseVeryLow)
            putInt(prefix + UI_PREFIX + "glucose_very_high", ui.glucoseVeryHigh)
            putInt(prefix + UI_PREFIX + "iob", ui.iob)
            putInt(prefix + UI_PREFIX + "cob", ui.cob)
            putInt(prefix + UI_PREFIX + "basal", ui.basal)
        }

        private fun migrateAppearanceProfiles(preferences: android.content.SharedPreferences) {
            if (preferences.getBoolean("appearance_profiles_v1", false)) return
            val prefixes = listOf(COLOR_PREFIX, UI_PREFIX)
            preferences.edit().apply {
                preferences.all.forEach { (key, raw) ->
                    if (prefixes.none(key::startsWith) && key !in setOf(STYLE_DOT_RADIUS, STYLE_OUTLINE_ENABLED, STYLE_OUTLINE_WIDTH)) return@forEach
                    AppearanceMode.entries.forEach { mode ->
                        val target = appearancePrefix(mode) + key
                        if (preferences.contains(target)) return@forEach
                        when (raw) {
                            is Int -> putInt(target, raw)
                            is Float -> putFloat(target, raw)
                            is Boolean -> putBoolean(target, raw)
                        }
                    }
                }
                putBoolean("appearance_profiles_v1", true)
            }.apply()
        }
    }
}
