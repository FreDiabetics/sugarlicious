package app.aapswear.wear

import android.content.Context
import app.aapswear.protocol.WatchUiColors

enum class WearTileKind(internal val preferenceName: String) {
    GLUCOSE("wear_tile_glucose_appearance"),
    THERAPY("wear_tile_therapy_appearance"),
}

/** Independent appearance state for each Wear OS tile. */
internal object WearTileAppearanceStore {
    private const val PREFIX = "color."

    fun read(context: Context, kind: WearTileKind): WatchUiColors {
        val defaults = WatchUiColors()
        val preferences = context.getSharedPreferences(kind.preferenceName, Context.MODE_PRIVATE)
        return WatchUiColors(
            background = preferences.getInt(PREFIX + "background", defaults.background),
            tileBackground = preferences.getInt(PREFIX + "tile_background", defaults.tileBackground),
            tileBorder = preferences.getInt(PREFIX + "tile_border", defaults.tileBorder),
            textPrimary = preferences.getInt(PREFIX + "text_primary", defaults.textPrimary),
            textSecondary = preferences.getInt(PREFIX + "text_secondary", defaults.textSecondary),
            accent = preferences.getInt(PREFIX + "accent", defaults.accent),
            glucoseLow = preferences.getInt(PREFIX + "glucose_low", defaults.glucoseLow),
            glucoseInRange = preferences.getInt(PREFIX + "glucose_in_range", defaults.glucoseInRange),
            glucoseHigh = preferences.getInt(PREFIX + "glucose_high", defaults.glucoseHigh),
            glucoseVeryLow = preferences.getInt(PREFIX + "glucose_very_low", defaults.glucoseVeryLow),
            glucoseVeryHigh = preferences.getInt(PREFIX + "glucose_very_high", defaults.glucoseVeryHigh),
            iob = preferences.getInt(PREFIX + "iob", defaults.iob),
            cob = preferences.getInt(PREFIX + "cob", defaults.cob),
            basal = preferences.getInt(PREFIX + "basal", defaults.basal),
        )
    }

    fun write(context: Context, kind: WearTileKind, colors: WatchUiColors) {
        context.getSharedPreferences(kind.preferenceName, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREFIX + "background", colors.background)
            .putInt(PREFIX + "tile_background", colors.tileBackground)
            .putInt(PREFIX + "tile_border", colors.tileBorder)
            .putInt(PREFIX + "text_primary", colors.textPrimary)
            .putInt(PREFIX + "text_secondary", colors.textSecondary)
            .putInt(PREFIX + "accent", colors.accent)
            .putInt(PREFIX + "glucose_low", colors.glucoseLow)
            .putInt(PREFIX + "glucose_in_range", colors.glucoseInRange)
            .putInt(PREFIX + "glucose_high", colors.glucoseHigh)
            .putInt(PREFIX + "glucose_very_low", colors.glucoseVeryLow)
            .putInt(PREFIX + "glucose_very_high", colors.glucoseVeryHigh)
            .putInt(PREFIX + "iob", colors.iob)
            .putInt(PREFIX + "cob", colors.cob)
            .putInt(PREFIX + "basal", colors.basal)
            .apply()
    }
}
