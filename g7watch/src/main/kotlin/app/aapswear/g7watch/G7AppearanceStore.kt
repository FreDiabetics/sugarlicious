package app.aapswear.g7watch

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import app.aapswear.model.AppearanceTerminology
import app.aapswear.model.AppearanceMode
import app.aapswear.model.GlucoseTrendSizing
import app.aapswear.model.SettingsSchemaVersions
import app.aapswear.storage.ensureSettingsSchema

enum class G7AppearanceSection(val label: String) {
    MENU("Menü"),
    GLUCOSE("Zuckerwert"),
    GRAPH("Graph"),
}

enum class G7AppearanceRole(
    val key: String,
    val label: String,
    val section: G7AppearanceSection,
    val defaultArgb: Int,
    val lightArgb: Int = defaultArgb,
) {
    MENU_BACKGROUND("menu_background", AppearanceTerminology.APP_BACKGROUND, G7AppearanceSection.MENU, 0xFF181818.toInt(), 0xFFF2F2F2.toInt()),
    MENU_SURFACE("menu_surface", AppearanceTerminology.SURFACE_BACKGROUND, G7AppearanceSection.MENU, 0xFF242424.toInt(), 0xFFFFFFFF.toInt()),
    MENU_BORDER("menu_border", AppearanceTerminology.SURFACE_BORDER, G7AppearanceSection.MENU, 0xFF404040.toInt(), 0xFFD0D0D0.toInt()),
    MENU_TEXT_PRIMARY("menu_text_primary", AppearanceTerminology.PRIMARY_TEXT, G7AppearanceSection.MENU, 0xFFF5F5F5.toInt(), 0xFF252525.toInt()),
    MENU_TEXT_SECONDARY("menu_text_secondary", AppearanceTerminology.SECONDARY_TEXT, G7AppearanceSection.MENU, 0xFFB5B5B5.toInt(), 0xFF666666.toInt()),
    MENU_PRIMARY("menu_primary", "Primär / Sugarlicious", G7AppearanceSection.MENU, 0xFF6DE892.toInt()),

    GLUCOSE_LOW("glucose_low", AppearanceTerminology.GLUCOSE_LOW, G7AppearanceSection.GLUCOSE, 0xFFFF5C69.toInt()),
    GLUCOSE_VERY_LOW("glucose_very_low", AppearanceTerminology.GLUCOSE_VERY_LOW, G7AppearanceSection.GLUCOSE, 0xFFFF3048.toInt()),
    GLUCOSE_IN_RANGE("glucose_in_range", AppearanceTerminology.GLUCOSE_IN_RANGE, G7AppearanceSection.GLUCOSE, 0xFFFFFFFF.toInt(), 0xFF202020.toInt()),
    GLUCOSE_HIGH("glucose_high", AppearanceTerminology.GLUCOSE_HIGH, G7AppearanceSection.GLUCOSE, 0xFFFFD040.toInt()),
    GLUCOSE_VERY_HIGH("glucose_very_high", AppearanceTerminology.GLUCOSE_VERY_HIGH, G7AppearanceSection.GLUCOSE, 0xFFFF9D18.toInt()),
    GLUCOSE_TREND("glucose_trend", AppearanceTerminology.TREND_ARROW, G7AppearanceSection.GLUCOSE, 0xFFFFFFFF.toInt()),
    GLUCOSE_DELTA("glucose_delta", "Delta / Alter", G7AppearanceSection.GLUCOSE, 0xFFB5B5B5.toInt()),
    GLUCOSE_DELAYED("glucose_delayed", "DELAYED", G7AppearanceSection.GLUCOSE, 0xFFF4DE00.toInt()),
    GLUCOSE_STALE("glucose_stale", "STALE", G7AppearanceSection.GLUCOSE, 0xFFFF9D18.toInt()),
    GLUCOSE_NO_SOURCE("glucose_no_source", "NO_SOURCE", G7AppearanceSection.GLUCOSE, 0xFF969696.toInt()),
    GLUCOSE_ERROR("glucose_error", "ERROR", G7AppearanceSection.GLUCOSE, 0xFFFF5C69.toInt()),

    GRAPH_BACKGROUND("graph_background", AppearanceTerminology.GRAPH_BACKGROUND, G7AppearanceSection.GRAPH, 0xFF202020.toInt(), 0xFFFFFFFF.toInt()),
    GRAPH_TARGET_AREA("graph_target_area", AppearanceTerminology.GRAPH_TARGET_AREA, G7AppearanceSection.GRAPH, 0x665C5C5C),
    GRAPH_HIGH_AREA("graph_high_area", AppearanceTerminology.GRAPH_HIGH_AREA, G7AppearanceSection.GRAPH, 0x45FFD040),
    GRAPH_LOW_AREA("graph_low_area", AppearanceTerminology.GRAPH_LOW_AREA, G7AppearanceSection.GRAPH, 0x45FF5C69),
    GRAPH_HIGH_LINE("graph_high_line", AppearanceTerminology.GRAPH_HIGH_LINE, G7AppearanceSection.GRAPH, 0xFFFFD040.toInt()),
    GRAPH_LOW_LINE("graph_low_line", AppearanceTerminology.GRAPH_LOW_LINE, G7AppearanceSection.GRAPH, 0xFFFF5C69.toInt()),
    GRAPH_DOT_HIGH("graph_dot_high", AppearanceTerminology.GRAPH_DOT_HIGH, G7AppearanceSection.GRAPH, 0xFFFFD040.toInt()),
    GRAPH_DOT_IN_RANGE("graph_dot_in_range", AppearanceTerminology.GRAPH_DOT_IN_RANGE, G7AppearanceSection.GRAPH, 0xFFFFFFFF.toInt(), 0xFF202020.toInt()),
    GRAPH_DOT_LOW("graph_dot_low", AppearanceTerminology.GRAPH_DOT_LOW, G7AppearanceSection.GRAPH, 0xFFFF5C69.toInt()),
    GRAPH_DOT_OUTLINE("graph_dot_outline", AppearanceTerminology.GRAPH_DOT_OUTLINE, G7AppearanceSection.GRAPH, 0xFF000000.toInt()),
    GRAPH_AXIS_TEXT("graph_axis_text", AppearanceTerminology.GRAPH_AXIS_TEXT, G7AppearanceSection.GRAPH, 0xFFD2D2D2.toInt()),
    GRAPH_GRID("graph_grid", "Grid / Divider", G7AppearanceSection.GRAPH, 0xFF464646.toInt()),
    GRAPH_TILE_BORDER("graph_tile_border", "Graph-Tile-Kontur", G7AppearanceSection.GRAPH, 0xFF5C5C5C.toInt()),
    GRAPH_PREDICTION("graph_prediction", "Prediction", G7AppearanceSection.GRAPH, 0xFFF4DE00.toInt()),
}

data class G7AppearancePalette(
    private val values: Map<G7AppearanceRole, Int>,
    val mode: AppearanceMode = AppearanceMode.DARK,
) {
    fun argb(role: G7AppearanceRole): Int = values[role] ?: if (mode == AppearanceMode.LIGHT) role.lightArgb else role.defaultArgb
}

class G7AppearanceStore(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    init {
        preferences.ensureSettingsSchema(SettingsSchemaVersions.COLLECTOR)
    }

    fun activeMode(): AppearanceMode =
        preferences.getString(KEY_ACTIVE_MODE, null)
            ?.let { stored -> AppearanceMode.entries.firstOrNull { it.storageKey == stored } }
            ?: systemMode()

    fun setActiveMode(mode: AppearanceMode) {
        // The next activity draw must see the selection immediately, even when Android pauses us.
        preferences.edit().putString(KEY_ACTIVE_MODE, mode.storageKey).commit()
    }

    fun glucoseScalePercent(): Int = preferences.getInt(KEY_GLUCOSE_SCALE, GlucoseTrendSizing.DEFAULT_SCALE_PERCENT)
        .coerceIn(GlucoseTrendSizing.MIN_SCALE_PERCENT, GlucoseTrendSizing.MAX_SCALE_PERCENT)

    fun trendScalePercent(): Int = preferences.getInt(KEY_TREND_SCALE, GlucoseTrendSizing.DEFAULT_SCALE_PERCENT)
        .coerceIn(GlucoseTrendSizing.MIN_SCALE_PERCENT, GlucoseTrendSizing.MAX_SCALE_PERCENT)

    fun setGlucoseScalePercent(value: Int) {
        preferences.edit().putInt(KEY_GLUCOSE_SCALE, value.coerceIn(GlucoseTrendSizing.MIN_SCALE_PERCENT, GlucoseTrendSizing.MAX_SCALE_PERCENT)).apply()
    }

    fun setTrendScalePercent(value: Int) {
        preferences.edit().putInt(KEY_TREND_SCALE, value.coerceIn(GlucoseTrendSizing.MIN_SCALE_PERCENT, GlucoseTrendSizing.MAX_SCALE_PERCENT)).apply()
    }

    private fun systemMode(): AppearanceMode =
        if ((preferencesContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) AppearanceMode.DARK else AppearanceMode.LIGHT

    private val preferencesContext = context.applicationContext

    fun load(): G7AppearancePalette = load(activeMode())

    fun load(mode: AppearanceMode): G7AppearancePalette {
        migrateLegacy()
        return G7AppearancePalette(
            G7AppearanceRole.entries.associateWith { role ->
                preferences.getInt(colorKey(mode, role), if (mode == AppearanceMode.LIGHT) role.lightArgb else role.defaultArgb)
            },
            mode,
        )
    }

    fun save(role: G7AppearanceRole, argb: Int) {
        save(activeMode(), role, argb)
    }

    fun save(mode: AppearanceMode, role: G7AppearanceRole, argb: Int) {
        migrateLegacy()
        preferences.edit().putInt(colorKey(mode, role), argb).apply()
    }

    fun reset(role: G7AppearanceRole) {
        reset(activeMode(), role)
    }

    fun reset(mode: AppearanceMode, role: G7AppearanceRole) {
        preferences.edit().remove(colorKey(mode, role)).apply()
    }

    fun resetAll() {
        preferences.edit().apply {
            G7AppearanceRole.entries.forEach { remove(colorKey(it)) }
            AppearanceMode.entries.forEach { mode -> G7AppearanceRole.entries.forEach { remove(colorKey(mode, it)) } }
        }.apply()
    }

    fun graphHours(): Int =
        preferences.getInt(KEY_GRAPH_HOURS, DEFAULT_GRAPH_HOURS)
            .takeIf { it in ALLOWED_GRAPH_HOURS }
            ?: DEFAULT_GRAPH_HOURS

    fun setGraphHours(hours: Int) {
        preferences.edit().putInt(KEY_GRAPH_HOURS, hours.takeIf { it in ALLOWED_GRAPH_HOURS } ?: DEFAULT_GRAPH_HOURS).apply()
    }

    fun nextGraphHours(): Int {
        val current = graphHours()
        val next = ALLOWED_GRAPH_HOURS[(ALLOWED_GRAPH_HOURS.indexOf(current) + 1) % ALLOWED_GRAPH_HOURS.size]
        setGraphHours(next)
        return next
    }

    private fun colorKey(role: G7AppearanceRole): String = "color.${role.key}"
    private fun colorKey(mode: AppearanceMode, role: G7AppearanceRole): String = "color.${mode.storageKey}.${role.key}"

    private fun migrateLegacy() {
        if (preferences.getBoolean("appearance_profiles_v1", false)) return
        preferences.edit().apply {
            G7AppearanceRole.entries.forEach { role ->
                if (!preferences.contains(colorKey(role))) return@forEach
                val value = preferences.getInt(colorKey(role), role.defaultArgb)
                AppearanceMode.entries.forEach { mode ->
                    if (!preferences.contains(colorKey(mode, role))) putInt(colorKey(mode, role), value)
                }
            }
            putBoolean("appearance_profiles_v1", true)
        }.apply()
    }

    companion object {
        val ALLOWED_GRAPH_HOURS = listOf(1, 2, 3, 6, 12, 24)
        const val DEFAULT_GRAPH_HOURS = 3
        private const val PREFERENCES = "g7_appearance"
        private const val KEY_ACTIVE_MODE = "active_mode"
        private const val KEY_GLUCOSE_SCALE = "glucose_scale_percent"
        private const val KEY_TREND_SCALE = "trend_scale_percent"
        private const val KEY_GRAPH_HOURS = "graph_hours"
    }
}
