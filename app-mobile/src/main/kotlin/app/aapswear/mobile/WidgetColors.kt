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
    TEXT("text", "Text"),
    TREND("trend", "Trend"),
}

internal data class WidgetPalette(private val values: Map<WidgetColorRole, Int>) {
    fun argb(role: WidgetColorRole): Int = values.getValue(role)
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
    when {
        valueMgDl <= 40.0 -> WidgetColorRole.URGENT_LOW
        valueMgDl < lowMgDl -> WidgetColorRole.LOW
        valueMgDl >= 400.0 -> WidgetColorRole.VERY_HIGH
        valueMgDl > highMgDl -> WidgetColorRole.HIGH
        else -> WidgetColorRole.IN_RANGE
    }
