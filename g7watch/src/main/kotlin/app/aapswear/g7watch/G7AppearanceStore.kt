package app.aapswear.g7watch

import android.content.Context
import android.content.SharedPreferences
import app.aapswear.model.AppearanceTerminology

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
) {
    MENU_BACKGROUND("menu_background", AppearanceTerminology.APP_BACKGROUND, G7AppearanceSection.MENU, 0xFF181818.toInt()),
    MENU_SURFACE("menu_surface", AppearanceTerminology.SURFACE_BACKGROUND, G7AppearanceSection.MENU, 0xFF242424.toInt()),
    MENU_BORDER("menu_border", AppearanceTerminology.SURFACE_BORDER, G7AppearanceSection.MENU, 0xFF404040.toInt()),
    MENU_TEXT_PRIMARY("menu_text_primary", AppearanceTerminology.PRIMARY_TEXT, G7AppearanceSection.MENU, 0xFFF5F5F5.toInt()),
    MENU_TEXT_SECONDARY("menu_text_secondary", AppearanceTerminology.SECONDARY_TEXT, G7AppearanceSection.MENU, 0xFFB5B5B5.toInt()),
    MENU_PRIMARY("menu_primary", "Primär / Sugarlicious", G7AppearanceSection.MENU, 0xFF6DE892.toInt()),

    GLUCOSE_LOW("glucose_low", AppearanceTerminology.GLUCOSE_LOW, G7AppearanceSection.GLUCOSE, 0xFFFF5C69.toInt()),
    GLUCOSE_VERY_LOW("glucose_very_low", AppearanceTerminology.GLUCOSE_VERY_LOW, G7AppearanceSection.GLUCOSE, 0xFFFF3048.toInt()),
    GLUCOSE_IN_RANGE("glucose_in_range", AppearanceTerminology.GLUCOSE_IN_RANGE, G7AppearanceSection.GLUCOSE, 0xFFFFFFFF.toInt()),
    GLUCOSE_HIGH("glucose_high", AppearanceTerminology.GLUCOSE_HIGH, G7AppearanceSection.GLUCOSE, 0xFFFFD040.toInt()),
    GLUCOSE_VERY_HIGH("glucose_very_high", AppearanceTerminology.GLUCOSE_VERY_HIGH, G7AppearanceSection.GLUCOSE, 0xFFFF9D18.toInt()),
    GLUCOSE_TREND("glucose_trend", AppearanceTerminology.TREND_ARROW, G7AppearanceSection.GLUCOSE, 0xFFFFFFFF.toInt()),
    GLUCOSE_DELTA("glucose_delta", "Delta / Alter", G7AppearanceSection.GLUCOSE, 0xFFB5B5B5.toInt()),
    GLUCOSE_DELAYED("glucose_delayed", "DELAYED", G7AppearanceSection.GLUCOSE, 0xFFF4DE00.toInt()),
    GLUCOSE_STALE("glucose_stale", "STALE", G7AppearanceSection.GLUCOSE, 0xFFFF9D18.toInt()),
    GLUCOSE_NO_SOURCE("glucose_no_source", "NO_SOURCE", G7AppearanceSection.GLUCOSE, 0xFF969696.toInt()),
    GLUCOSE_ERROR("glucose_error", "ERROR", G7AppearanceSection.GLUCOSE, 0xFFFF5C69.toInt()),

    GRAPH_BACKGROUND("graph_background", AppearanceTerminology.GRAPH_BACKGROUND, G7AppearanceSection.GRAPH, 0xFF202020.toInt()),
    GRAPH_TARGET_AREA("graph_target_area", AppearanceTerminology.GRAPH_TARGET_AREA, G7AppearanceSection.GRAPH, 0x665C5C5C),
    GRAPH_HIGH_AREA("graph_high_area", AppearanceTerminology.GRAPH_HIGH_AREA, G7AppearanceSection.GRAPH, 0x45FFD040),
    GRAPH_LOW_AREA("graph_low_area", AppearanceTerminology.GRAPH_LOW_AREA, G7AppearanceSection.GRAPH, 0x45FF5C69),
    GRAPH_HIGH_LINE("graph_high_line", AppearanceTerminology.GRAPH_HIGH_LINE, G7AppearanceSection.GRAPH, 0xFFFFD040.toInt()),
    GRAPH_LOW_LINE("graph_low_line", AppearanceTerminology.GRAPH_LOW_LINE, G7AppearanceSection.GRAPH, 0xFFFF5C69.toInt()),
    GRAPH_DOT_HIGH("graph_dot_high", AppearanceTerminology.GRAPH_DOT_HIGH, G7AppearanceSection.GRAPH, 0xFFFFD040.toInt()),
    GRAPH_DOT_IN_RANGE("graph_dot_in_range", AppearanceTerminology.GRAPH_DOT_IN_RANGE, G7AppearanceSection.GRAPH, 0xFFFFFFFF.toInt()),
    GRAPH_DOT_LOW("graph_dot_low", AppearanceTerminology.GRAPH_DOT_LOW, G7AppearanceSection.GRAPH, 0xFFFF5C69.toInt()),
    GRAPH_DOT_OUTLINE("graph_dot_outline", AppearanceTerminology.GRAPH_DOT_OUTLINE, G7AppearanceSection.GRAPH, 0xFF000000.toInt()),
    GRAPH_AXIS_TEXT("graph_axis_text", AppearanceTerminology.GRAPH_AXIS_TEXT, G7AppearanceSection.GRAPH, 0xFFD2D2D2.toInt()),
    GRAPH_GRID("graph_grid", "Grid / Divider", G7AppearanceSection.GRAPH, 0xFF464646.toInt()),
    GRAPH_TILE_BORDER("graph_tile_border", "Graph-Tile-Kontur", G7AppearanceSection.GRAPH, 0xFF5C5C5C.toInt()),
    GRAPH_PREDICTION("graph_prediction", "Prediction", G7AppearanceSection.GRAPH, 0xFFF4DE00.toInt()),
}

data class G7AppearancePalette(
    private val values: Map<G7AppearanceRole, Int>,
) {
    fun argb(role: G7AppearanceRole): Int = values[role] ?: role.defaultArgb
}

class G7AppearanceStore(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): G7AppearancePalette =
        G7AppearancePalette(
            G7AppearanceRole.entries.associateWith { role ->
                preferences.getInt(colorKey(role), role.defaultArgb)
            },
        )

    fun save(role: G7AppearanceRole, argb: Int) {
        preferences.edit().putInt(colorKey(role), argb).apply()
    }

    fun reset(role: G7AppearanceRole) {
        preferences.edit().remove(colorKey(role)).apply()
    }

    fun resetAll() {
        preferences.edit().apply {
            G7AppearanceRole.entries.forEach { remove(colorKey(it)) }
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

    companion object {
        val ALLOWED_GRAPH_HOURS = listOf(1, 2, 3, 6, 12, 24)
        const val DEFAULT_GRAPH_HOURS = 3
        private const val PREFERENCES = "g7_appearance"
        private const val KEY_GRAPH_HOURS = "graph_hours"
    }
}
