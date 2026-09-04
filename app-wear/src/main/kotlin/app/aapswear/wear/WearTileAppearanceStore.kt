package app.aapswear.wear

import android.content.Context
import app.aapswear.protocol.WatchUiColors
import app.aapswear.model.AppearanceMode

enum class WearTileKind(internal val preferenceName: String) {
    GLUCOSE("wear_tile_glucose_appearance"),
    THERAPY("wear_tile_therapy_appearance"),
}

enum class WearTileContent(val label: String) {
    GLUCOSE("Glukose"),
    GRAPH("Graph"),
    IOB("IOB"),
    COB("COB"),
    BASAL("Basal"),
    PUMP("Pumpe"),
}

/** The two system tile slots keep their own content choice; no global fake tile state. */
internal object WearTileContentStore {
    private const val PREFERENCES = "wear_tile_content"

    fun read(context: Context, kind: WearTileKind): WearTileContent {
        val fallback = if (kind == WearTileKind.GLUCOSE) WearTileContent.GLUCOSE else WearTileContent.IOB
        val raw = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(kind.name, fallback.name)
        return WearTileContent.entries.firstOrNull { it.name == raw } ?: fallback
    }

    fun write(context: Context, kind: WearTileKind, content: WearTileContent) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(kind.name, content.name)
            .apply()
    }
}

/** Independent appearance state for each Wear OS tile. */
internal object WearTileAppearanceStore {
    private const val PREFIX = "color."

    fun read(context: Context, kind: WearTileKind): WatchUiColors =
        read(context, kind, WearDisplayPreferences.activeAppearanceMode(context))

    fun read(context: Context, kind: WearTileKind, mode: AppearanceMode): WatchUiColors {
        val defaults = WatchUiColors()
        val preferences = context.getSharedPreferences(kind.preferenceName, Context.MODE_PRIVATE)
        migrateLegacy(preferences)
        val prefix = "$PREFIX${mode.storageKey}."
        return WatchUiColors(
            background = preferences.getInt(prefix + "background", defaults.background),
            tileBackground = preferences.getInt(prefix + "tile_background", defaults.tileBackground),
            tileBorder = preferences.getInt(prefix + "tile_border", defaults.tileBorder),
            textPrimary = preferences.getInt(prefix + "text_primary", defaults.textPrimary),
            textSecondary = preferences.getInt(prefix + "text_secondary", defaults.textSecondary),
            accent = preferences.getInt(prefix + "accent", defaults.accent),
            glucoseLow = preferences.getInt(prefix + "glucose_low", defaults.glucoseLow),
            glucoseInRange = preferences.getInt(prefix + "glucose_in_range", defaults.glucoseInRange),
            glucoseHigh = preferences.getInt(prefix + "glucose_high", defaults.glucoseHigh),
            glucoseVeryLow = preferences.getInt(prefix + "glucose_very_low", defaults.glucoseVeryLow),
            glucoseVeryHigh = preferences.getInt(prefix + "glucose_very_high", defaults.glucoseVeryHigh),
            iob = preferences.getInt(prefix + "iob", defaults.iob),
            cob = preferences.getInt(prefix + "cob", defaults.cob),
            basal = preferences.getInt(prefix + "basal", defaults.basal),
        )
    }

    fun write(context: Context, kind: WearTileKind, colors: WatchUiColors) =
        write(context, kind, WearDisplayPreferences.activeAppearanceMode(context), colors)

    fun write(context: Context, kind: WearTileKind, mode: AppearanceMode, colors: WatchUiColors) {
        val preferences = context.getSharedPreferences(kind.preferenceName, Context.MODE_PRIVATE)
        migrateLegacy(preferences)
        val prefix = "$PREFIX${mode.storageKey}."
        preferences.edit()
            .putInt(prefix + "background", colors.background)
            .putInt(prefix + "tile_background", colors.tileBackground)
            .putInt(prefix + "tile_border", colors.tileBorder)
            .putInt(prefix + "text_primary", colors.textPrimary)
            .putInt(prefix + "text_secondary", colors.textSecondary)
            .putInt(prefix + "accent", colors.accent)
            .putInt(prefix + "glucose_low", colors.glucoseLow)
            .putInt(prefix + "glucose_in_range", colors.glucoseInRange)
            .putInt(prefix + "glucose_high", colors.glucoseHigh)
            .putInt(prefix + "glucose_very_low", colors.glucoseVeryLow)
            .putInt(prefix + "glucose_very_high", colors.glucoseVeryHigh)
            .putInt(prefix + "iob", colors.iob)
            .putInt(prefix + "cob", colors.cob)
            .putInt(prefix + "basal", colors.basal)
            .apply()
    }

    private fun migrateLegacy(preferences: android.content.SharedPreferences) {
        if (preferences.getBoolean("appearance_profiles_v1", false)) return
        preferences.edit().apply {
            preferences.all.forEach { (key, raw) ->
                if (!key.startsWith(PREFIX) || raw !is Int || key.startsWith("${PREFIX}light.") || key.startsWith("${PREFIX}dark.")) return@forEach
                val suffix = key.removePrefix(PREFIX)
                AppearanceMode.entries.forEach { mode ->
                    val target = "$PREFIX${mode.storageKey}.$suffix"
                    if (!preferences.contains(target)) putInt(target, raw)
                }
            }
            putBoolean("appearance_profiles_v1", true)
        }.apply()
    }
}
