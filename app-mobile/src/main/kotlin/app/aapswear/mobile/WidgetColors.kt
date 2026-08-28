package app.aapswear.mobile

import android.content.Context
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColorStore
import app.aapswear.model.AppearanceTerminology

internal enum class WidgetColorRole(
    val preferenceKey: String,
    val label: String,
) {
    HIGH("high", AppearanceTerminology.GLUCOSE_HIGH),
    IN_RANGE("in_range", AppearanceTerminology.GLUCOSE_IN_RANGE),
    LOW("low", AppearanceTerminology.GLUCOSE_LOW),
    URGENT_LOW("urgent_low", AppearanceTerminology.GLUCOSE_VERY_LOW),
    VERY_HIGH("very_high", AppearanceTerminology.GLUCOSE_VERY_HIGH),
    BACKGROUND("background", "Hintergrund"),
    GRAPH_BACKGROUND("graph_background", AppearanceTerminology.GRAPH_BACKGROUND),
    RANGE_HIGH("range_high", AppearanceTerminology.GRAPH_HIGH_AREA),
    RANGE_IN_RANGE("range_in_range", AppearanceTerminology.GRAPH_TARGET_AREA),
    RANGE_LOW("range_low", AppearanceTerminology.GRAPH_LOW_AREA),
    HIGH_LINE("high_line", AppearanceTerminology.GRAPH_HIGH_LINE),
    LOW_LINE("low_line", AppearanceTerminology.GRAPH_LOW_LINE),
    DOT_OUTLINE("dot_outline", AppearanceTerminology.GRAPH_DOT_OUTLINE),
    DOT_HIGH("dot_high", AppearanceTerminology.GRAPH_DOT_HIGH),
    DOT_IN_RANGE("dot_in_range", AppearanceTerminology.GRAPH_DOT_IN_RANGE),
    DOT_LOW("dot_low", AppearanceTerminology.GRAPH_DOT_LOW),
    DIVIDER("divider", AppearanceTerminology.GRAPH_NOW_LINE),
    AXIS("axis", AppearanceTerminology.GRAPH_AXIS_TEXT),
    AXIS_TICK("axis_tick", AppearanceTerminology.GRAPH_AXIS_TICK),
    TEXT("text", "Text"),
    TREND("trend", AppearanceTerminology.TREND_ARROW),
    TREND_HIGH("trend_high", "Trend Hoch"),
    TREND_IN_RANGE("trend_in_range", "Trend im Ziel"),
    TREND_LOW("trend_low", "Trend Tief"),
}

internal data class WidgetPalette(private val values: Map<WidgetColorRole, Int>) {
    fun argb(role: WidgetColorRole): Int = values.getValue(role)
    fun with(role: WidgetColorRole, argb: Int): WidgetPalette = WidgetPalette(values + (role to argb))
    fun with(overrides: Map<WidgetColorRole, Int>): WidgetPalette = WidgetPalette(values + overrides)
}

internal object WidgetColorStore {
    private const val PREFERENCES = "dashboard_ui"
    private const val PREFIX = "widget.color.override."

    fun load(context: Context): WidgetPalette {
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val mobile = SugarliciousColorStore.load(preferences)
        return WidgetPalette(
            WidgetColorRole.entries.associateWith { role ->
                preferences.getInt(key(role), mobileDefault(role, mobile::argb))
            },
        )
    }

    fun save(context: Context, role: WidgetColorRole, argb: Int) {
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putInt(key(role), argb)
            .apply()
    }

    fun reset(context: Context, role: WidgetColorRole) {
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(key(role))
            .apply()
    }

    fun resetAll(context: Context) {
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .apply {
                WidgetColorRole.entries.forEach { remove(key(it)) }
            }
            .apply()
    }

    /** Takes a point-in-time copy. Later Mobile graph changes no longer mutate widget colors. */
    fun copyFromMobileGraph(context: Context) {
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val mobile = SugarliciousColorStore.load(preferences)
        preferences.edit()
            .apply {
                WidgetColorRole.entries.forEach { role ->
                    val sourceRole =
                        when (role) {
                            WidgetColorRole.HIGH -> SugarliciousColorRole.CGM_DOT_HIGH
                            WidgetColorRole.IN_RANGE -> SugarliciousColorRole.CGM_DOT_IN_RANGE
                            WidgetColorRole.LOW -> SugarliciousColorRole.CGM_DOT_LOW
                            WidgetColorRole.URGENT_LOW -> SugarliciousColorRole.RANGE_LOW
                            WidgetColorRole.VERY_HIGH -> SugarliciousColorRole.RANGE_HIGH
                            WidgetColorRole.BACKGROUND -> SugarliciousColorRole.GRAPH_BACKGROUND
                            WidgetColorRole.GRAPH_BACKGROUND -> SugarliciousColorRole.GRAPH_BACKGROUND
                            WidgetColorRole.RANGE_HIGH -> SugarliciousColorRole.RANGE_HIGH
                            WidgetColorRole.RANGE_IN_RANGE -> SugarliciousColorRole.RANGE_IN_RANGE
                            WidgetColorRole.RANGE_LOW -> SugarliciousColorRole.RANGE_LOW
                            WidgetColorRole.HIGH_LINE -> SugarliciousColorRole.GRAPH_HIGH_LINE
                            WidgetColorRole.LOW_LINE -> SugarliciousColorRole.GRAPH_LOW_LINE
                            WidgetColorRole.DOT_OUTLINE -> SugarliciousColorRole.GRAPH_CURRENT_OUTLINE
                            WidgetColorRole.DOT_HIGH -> SugarliciousColorRole.CGM_DOT_HIGH
                            WidgetColorRole.DOT_IN_RANGE -> SugarliciousColorRole.CGM_DOT_IN_RANGE
                            WidgetColorRole.DOT_LOW -> SugarliciousColorRole.CGM_DOT_LOW
                            WidgetColorRole.DIVIDER -> SugarliciousColorRole.GRAPH_NOW_LINE
                            WidgetColorRole.AXIS -> SugarliciousColorRole.GRAPH_LABEL
                            WidgetColorRole.AXIS_TICK -> SugarliciousColorRole.GRAPH_AXIS_TICK
                            WidgetColorRole.TEXT -> SugarliciousColorRole.GRAPH_LABEL
                            WidgetColorRole.TREND -> SugarliciousColorRole.TEXT_PRIMARY
                            WidgetColorRole.TREND_HIGH -> SugarliciousColorRole.GLUCOSE_HIGH
                            WidgetColorRole.TREND_IN_RANGE -> SugarliciousColorRole.GLUCOSE_IN_RANGE
                            WidgetColorRole.TREND_LOW -> SugarliciousColorRole.GLUCOSE_LOW
                        }
                    putInt(key(role), mobile.argb(sourceRole))
                }
            }
            .apply()
    }

    fun hasOverride(context: Context, role: WidgetColorRole): Boolean =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).contains(key(role))

    internal fun key(role: WidgetColorRole): String = PREFIX + role.preferenceKey

    private fun mobileDefault(role: WidgetColorRole, color: (SugarliciousColorRole) -> Int): Int =
        color(
            when (role) {
                WidgetColorRole.HIGH -> SugarliciousColorRole.GLUCOSE_HIGH
                WidgetColorRole.IN_RANGE -> SugarliciousColorRole.GLUCOSE_IN_RANGE
                WidgetColorRole.LOW -> SugarliciousColorRole.GLUCOSE_LOW
                WidgetColorRole.URGENT_LOW -> SugarliciousColorRole.RED
                WidgetColorRole.VERY_HIGH -> SugarliciousColorRole.YELLOW
                WidgetColorRole.BACKGROUND -> SugarliciousColorRole.SURFACE
                WidgetColorRole.GRAPH_BACKGROUND -> SugarliciousColorRole.GRAPH_BACKGROUND
                WidgetColorRole.RANGE_HIGH -> SugarliciousColorRole.RANGE_HIGH
                WidgetColorRole.RANGE_IN_RANGE -> SugarliciousColorRole.RANGE_IN_RANGE
                WidgetColorRole.RANGE_LOW -> SugarliciousColorRole.RANGE_LOW
                WidgetColorRole.HIGH_LINE -> SugarliciousColorRole.GRAPH_HIGH_LINE
                WidgetColorRole.LOW_LINE -> SugarliciousColorRole.GRAPH_LOW_LINE
                WidgetColorRole.DOT_OUTLINE -> SugarliciousColorRole.GRAPH_CURRENT_OUTLINE
                WidgetColorRole.DOT_HIGH -> SugarliciousColorRole.CGM_DOT_HIGH
                WidgetColorRole.DOT_IN_RANGE -> SugarliciousColorRole.CGM_DOT_IN_RANGE
                WidgetColorRole.DOT_LOW -> SugarliciousColorRole.CGM_DOT_LOW
                WidgetColorRole.DIVIDER -> SugarliciousColorRole.GRAPH_NOW_LINE
                WidgetColorRole.AXIS -> SugarliciousColorRole.GRAPH_LABEL
                WidgetColorRole.AXIS_TICK -> SugarliciousColorRole.GRAPH_AXIS_TICK
                WidgetColorRole.TEXT -> SugarliciousColorRole.TEXT_PRIMARY
                WidgetColorRole.TREND -> SugarliciousColorRole.PRIMARY
                WidgetColorRole.TREND_HIGH -> SugarliciousColorRole.GLUCOSE_HIGH
                WidgetColorRole.TREND_IN_RANGE -> SugarliciousColorRole.GLUCOSE_IN_RANGE
                WidgetColorRole.TREND_LOW -> SugarliciousColorRole.GLUCOSE_LOW
            },
        )
}

internal fun widgetGlucoseColorRole(
    valueMgDl: Double,
    lowMgDl: Double,
    highMgDl: Double,
): WidgetColorRole =
    widgetGlucoseColorRole(valueMgDl, app.aapswear.model.CgmThresholds(
        veryHighMgDl = maxOf(app.aapswear.model.CgmThresholds.DEFAULT_VERY_HIGH_MG_DL, highMgDl + 1.0),
        highMgDl = highMgDl,
        lowMgDl = lowMgDl,
        veryLowMgDl = minOf(app.aapswear.model.CgmThresholds.DEFAULT_VERY_LOW_MG_DL, lowMgDl - 1.0),
    ))

internal fun widgetGlucoseColorRole(valueMgDl: Double, thresholds: app.aapswear.model.CgmThresholds): WidgetColorRole =
    when (thresholds.classify(valueMgDl)) {
        app.aapswear.model.CgmRangeClass.VERY_LOW -> WidgetColorRole.URGENT_LOW
        app.aapswear.model.CgmRangeClass.LOW -> WidgetColorRole.LOW
        app.aapswear.model.CgmRangeClass.IN_RANGE -> WidgetColorRole.IN_RANGE
        app.aapswear.model.CgmRangeClass.HIGH -> WidgetColorRole.HIGH
        app.aapswear.model.CgmRangeClass.VERY_HIGH -> WidgetColorRole.VERY_HIGH
        null -> WidgetColorRole.IN_RANGE
    }
