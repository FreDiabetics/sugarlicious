package app.aapswear.mobile

import android.content.Context
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColorStore

internal enum class WidgetColorRole(
    val preferenceKey: String,
    val label: String,
) {
    HIGH("high", "Hoch"),
    IN_RANGE("in_range", "Im Ziel"),
    LOW("low", "Tief"),
    URGENT_LOW("urgent_low", "Dringend tief"),
    VERY_HIGH("very_high", "Sehr hoch"),
    BACKGROUND("background", "Hintergrund"),
    GRAPH_BACKGROUND("graph_background", "Graph-Hintergrund"),
    RANGE_HIGH("range_high", "Hoch-Bereich"),
    RANGE_IN_RANGE("range_in_range", "Zielbereich"),
    RANGE_LOW("range_low", "Tief-Bereich"),
    HIGH_LINE("high_line", "Hoch-Grenzlinie"),
    LOW_LINE("low_line", "Tief-Grenzlinie"),
    DOT_OUTLINE("dot_outline", "Punkt-Kontur"),
    DIVIDER("divider", "Jetzt-Trennlinie"),
    AXIS("axis", "Achsentext"),
    TEXT("text", "Text"),
    TREND("trend", "Trend"),
}

internal data class WidgetPalette(private val values: Map<WidgetColorRole, Int>) {
    fun argb(role: WidgetColorRole): Int = values.getValue(role)
    fun with(role: WidgetColorRole, argb: Int): WidgetPalette = WidgetPalette(values + (role to argb))
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
                            WidgetColorRole.HIGH_LINE -> SugarliciousColorRole.RANGE_HIGH
                            WidgetColorRole.LOW_LINE -> SugarliciousColorRole.RANGE_LOW
                            WidgetColorRole.DOT_OUTLINE -> SugarliciousColorRole.GRAPH_CURRENT_OUTLINE
                            WidgetColorRole.DIVIDER -> SugarliciousColorRole.GRAPH_DIVIDER
                            WidgetColorRole.AXIS -> SugarliciousColorRole.GRAPH_LABEL
                            WidgetColorRole.TEXT -> SugarliciousColorRole.GRAPH_LABEL
                            WidgetColorRole.TREND -> SugarliciousColorRole.TEXT_PRIMARY
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
                WidgetColorRole.HIGH_LINE -> SugarliciousColorRole.RANGE_HIGH
                WidgetColorRole.LOW_LINE -> SugarliciousColorRole.RANGE_LOW
                WidgetColorRole.DOT_OUTLINE -> SugarliciousColorRole.GRAPH_CURRENT_OUTLINE
                WidgetColorRole.DIVIDER -> SugarliciousColorRole.GRAPH_DIVIDER
                WidgetColorRole.AXIS -> SugarliciousColorRole.GRAPH_LABEL
                WidgetColorRole.TEXT -> SugarliciousColorRole.TEXT_PRIMARY
                WidgetColorRole.TREND -> SugarliciousColorRole.PRIMARY
            },
        )
}

internal fun widgetGlucoseColorRole(
    valueMgDl: Double,
    lowMgDl: Double,
    highMgDl: Double,
): WidgetColorRole =
    widgetGlucoseColorRole(valueMgDl, app.aapswear.model.CgmThresholds(
        veryHighMgDl = maxOf(250.0, highMgDl + 1.0), highMgDl = highMgDl,
        lowMgDl = lowMgDl, veryLowMgDl = minOf(50.0, lowMgDl - 1.0),
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
