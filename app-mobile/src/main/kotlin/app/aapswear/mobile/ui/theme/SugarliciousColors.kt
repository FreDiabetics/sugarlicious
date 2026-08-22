package app.aapswear.mobile.ui.theme

import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit

enum class SugarliciousColorGroup(val label: String) {
    APP("App-Oberfläche"),
    GLUCOSE("Glukose & Zielbereich"),
    THERAPY("Therapie-Akzente"),
    GRAPH("Graphen & Prognosen"),
}

enum class SugarliciousColorRole(
    val preferenceKey: String,
    val label: String,
    val group: SugarliciousColorGroup,
    val defaultArgb: Int,
    val lightArgb: Int = defaultArgb,
    val configurable: Boolean = false,
) {
    BRAND_GREEN("brand_green", "Marken-Grün", SugarliciousColorGroup.APP, 0xFF5FC479.toInt()),
    PRIMARY("primary", "Primär / Hauptakzent", SugarliciousColorGroup.APP, 0xFF6DE892.toInt()),
    ON_PRIMARY("on_primary", "Text auf Primärfarbe", SugarliciousColorGroup.APP, 0xFF181818.toInt(), 0xFF102114.toInt()),
    SECONDARY("secondary", "Sekundär / Cyan", SugarliciousColorGroup.APP, 0xFF19D7E8.toInt()),
    ON_SECONDARY("on_secondary", "Text auf Sekundärfarbe", SugarliciousColorGroup.APP, 0xFF181818.toInt()),
    BACKGROUND("background", "App-Hintergrund", SugarliciousColorGroup.APP, 0xFF181818.toInt(), 0xFFF2F2F2.toInt(), true),
    SURFACE("surface", "Tile-Hintergrund", SugarliciousColorGroup.APP, 0xFF242424.toInt(), 0xFFFDFDFD.toInt(), true),
    SURFACE_HIGH("surface_high", "Erhöhte Fläche", SugarliciousColorGroup.APP, 0xFF303030.toInt(), 0xFFE7E7E7.toInt()),
    SURFACE_RAISED("surface_raised", "Progress-/Raised-Fläche", SugarliciousColorGroup.APP, 0xFF363636.toInt(), 0xFFDDDDDD.toInt()),
    SURFACE_SELECTED("surface_selected", "Ausgewählte Fläche", SugarliciousColorGroup.APP, 0xFF3A3A3A.toInt(), 0xFFDCE8DF.toInt()),
    BORDER("border", "Tile-Kontur / Rahmen", SugarliciousColorGroup.APP, 0xFF404040.toInt(), 0xFFD0D0D0.toInt(), configurable = true),
    TEXT_PRIMARY("text_primary", "Haupttext", SugarliciousColorGroup.APP, 0xFFF5F5F5.toInt(), 0xFF252525.toInt()),
    TEXT_SECONDARY("text_secondary", "Sekundärtext", SugarliciousColorGroup.APP, 0xFFB5B5B5.toInt(), 0xFF666666.toInt()),

    GLUCOSE_LOW("glucose_low", "Zuckerwert · tief", SugarliciousColorGroup.GLUCOSE, 0xFFFF5C69.toInt(), configurable = true),
    GLUCOSE_IN_RANGE("glucose_in_range", "Zuckerwert · im Ziel", SugarliciousColorGroup.GLUCOSE, 0xFFF5F5F5.toInt(), 0xFF202020.toInt(), true),
    GLUCOSE_HIGH("glucose_high", "Zuckerwert · hoch", SugarliciousColorGroup.GLUCOSE, 0xFFFFD040.toInt(), 0xFFD47D00.toInt(), true),
    RANGE_LOW("range_low", "Bereich · tief", SugarliciousColorGroup.GLUCOSE, 0xFFFF5C69.toInt(), configurable = true),
    RANGE_IN_RANGE("range_in_range", "Bereich · im Ziel", SugarliciousColorGroup.GLUCOSE, 0xFF54DF30.toInt(), 0xFF2E9C45.toInt(), true),
    RANGE_HIGH("range_high", "Bereich · hoch", SugarliciousColorGroup.GLUCOSE, 0xFFFFD040.toInt(), 0xFFD47D00.toInt(), true),
    PROGRESS_BELOW("progress_below", "Progressbar · unter Ziel", SugarliciousColorGroup.GLUCOSE, 0xFFFF5C69.toInt(), configurable = true),
    PROGRESS_IN_RANGE("progress_in_range", "Progressbar · im Ziel", SugarliciousColorGroup.GLUCOSE, 0xFF54DF30.toInt(), 0xFF2E9C45.toInt(), true),
    PROGRESS_ABOVE("progress_above", "Progressbar · über Ziel", SugarliciousColorGroup.GLUCOSE, 0xFFFFD040.toInt(), 0xFFD47D00.toInt(), true),
    CGM_DOT_LOW("cgm_dot_low", "CGM-Punkte · tief", SugarliciousColorGroup.GLUCOSE, 0xFFFF5C69.toInt(), configurable = true),
    CGM_DOT_IN_RANGE("cgm_dot_in_range", "CGM-Punkte · im Ziel", SugarliciousColorGroup.GLUCOSE, 0xFF54DF30.toInt(), 0xFF2E9C45.toInt(), true),
    CGM_DOT_HIGH("cgm_dot_high", "CGM-Punkte · hoch", SugarliciousColorGroup.GLUCOSE, 0xFFFFD040.toInt(), 0xFFD47D00.toInt(), true),
    /**
     * Compatibility enum slot: the target range itself is rendered via RANGE_IN_RANGE since #51.
     * This role now owns the independent target_value preference for effective target line/text.
     * Old target_band preferences stay untouched and are not reinterpreted.
     */
    TARGET_BAND(
        "target_value",
        "Zielwert im Graph",
        SugarliciousColorGroup.GLUCOSE,
        0xFF5AF034.toInt(),
        0xFF47F06A.toInt(),
        true,
    ),

    GREEN("green", "Grün / Status", SugarliciousColorGroup.THERAPY, 0xFF54DF30.toInt()),
    BLUE("blue", "Blau / IOB", SugarliciousColorGroup.THERAPY, 0xFF64BFFF.toInt()),
    ORANGE("orange", "Orange / COB", SugarliciousColorGroup.THERAPY, 0xFFFF9D18.toInt()),
    YELLOW("yellow", "Gelb / Warnung", SugarliciousColorGroup.THERAPY, 0xFFF4DE00.toInt()),
    PURPLE("purple", "Violett", SugarliciousColorGroup.THERAPY, 0xFFD69AFF.toInt()),
    RED("red", "Rot / Fehler", SugarliciousColorGroup.THERAPY, 0xFFFF5C69.toInt()),

    PREDICTION_IOB("prediction_iob", "Prognose IOB", SugarliciousColorGroup.GRAPH, 0xFF52C1FF.toInt()),
    PREDICTION_COB("prediction_cob", "Prognose COB / ACOB", SugarliciousColorGroup.GRAPH, 0xFFF4DE00.toInt()),
    PREDICTION_UAM("prediction_uam", "Prognose UAM", SugarliciousColorGroup.GRAPH, 0xFFFFAE1F.toInt()),
    PREDICTION_ZERO_TEMP("prediction_zero_temp", "Prognose Zero Temp", SugarliciousColorGroup.GRAPH, 0xFF30DBDE.toInt()),
    GRAPH_BACKGROUND("graph_background", "Graph-Hintergrund", SugarliciousColorGroup.GRAPH, 0xFF202020.toInt(), 0xFFF8F8F8.toInt(), true),
    GRAPH_IOB("graph_iob", "IOB", SugarliciousColorGroup.GRAPH, 0xFF64BFFF.toInt(), 0xFF2479B7.toInt(), true),
    GRAPH_COB("graph_cob", "COB", SugarliciousColorGroup.GRAPH, 0xFFFF9D18.toInt(), 0xFFBD6500.toInt(), true),
    GRAPH_GRID("graph_grid", "Graph-Gitter", SugarliciousColorGroup.GRAPH, 0xFF464646.toInt(), 0xFFD5D5D5.toInt()),
    GRAPH_LABEL("graph_label", "Achsenbeschriftung", SugarliciousColorGroup.GRAPH, 0xFFD2D2D2.toInt(), 0xFF575757.toInt()),
    GRAPH_MUTED("graph_muted", "Graph-Hinweise / Trennlinie", SugarliciousColorGroup.GRAPH, 0xFF969696.toInt(), 0xFF777777.toInt()),
    GRAPH_DIVIDER("graph_divider", "Trennlinie", SugarliciousColorGroup.GRAPH, 0xFF969696.toInt(), 0xFF747474.toInt(), true),
    GRAPH_SIGNAL_LOSS("graph_signal_loss", "Signalverlust", SugarliciousColorGroup.GRAPH, 0x46FF5C69, 0x38D11A2A, true),
    GRAPH_CURRENT_OUTLINE("graph_current_outline", "Aktueller Punkt · Kontur", SugarliciousColorGroup.GRAPH, 0xFF000000.toInt());

    companion object {
        /** Semantic alias used by graph/settings without adding another enum entry. */
        val TARGET_VALUE: SugarliciousColorRole
            get() = TARGET_BAND
    }
}

data class SugarliciousPalette(
    private val values: Map<SugarliciousColorRole, Int>,
    val isLight: Boolean = false,
) {
    fun argb(role: SugarliciousColorRole): Int =
        values[role] ?: if (isLight) role.lightArgb else role.defaultArgb

    fun compose(role: SugarliciousColorRole): Color =
        Color(argb(role))

    companion object {
        fun defaults(): SugarliciousPalette =
            SugarliciousPalette(
                values =
                    SugarliciousColorRole.entries.associateWith {
                        it.defaultArgb
                    },
                isLight = false,
            )
    }
}

/** Keeps the target line in the in-range hue while making it visibly brighter. */
fun derivedTargetValueArgb(inRangeArgb: Int): Int {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(inRangeArgb, hsv)
    hsv[2] = hsv[2].coerceAtLeast(0.94f)
    return AndroidColor.HSVToColor(255, hsv)
}

object SugarliciousColorStore {
    private const val LEGACY_PREFIX = "color."
    private const val DARK_PREFIX = "color.dark."
    private const val LIGHT_PREFIX = "color.light."
    private const val OVERRIDE_PREFIX = "color.override."

    private fun systemIsLight(): Boolean =
        (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
            Configuration.UI_MODE_NIGHT_YES

    private fun isLight(preferences: SharedPreferences): Boolean =
        when (preferences.getString("themeMode", "SYSTEM")) {
            "LIGHT" -> true
            "DARK" -> false
            else -> systemIsLight()
        }

    fun load(preferences: SharedPreferences): SugarliciousPalette {
        val light = isLight(preferences)
        val prefix = if (light) LIGHT_PREFIX else DARK_PREFIX

        val values =
            SugarliciousColorRole.entries.associateWith { role ->
                    val modeKey = prefix + role.preferenceKey
                    val overrideKey = OVERRIDE_PREFIX + role.preferenceKey
                    val legacyKey =
                        LEGACY_PREFIX + role.preferenceKey

                    when {
                        preferences.contains(overrideKey) ->
                            preferences.getInt(
                                overrideKey,
                                if (light) role.lightArgb else role.defaultArgb,
                            )

                        preferences.contains(modeKey) ->
                            preferences.getInt(
                                modeKey,
                                if (light) {
                                    role.lightArgb
                                } else {
                                    role.defaultArgb
                                },
                            )

                        !light &&
                            preferences.contains(legacyKey) ->
                            preferences.getInt(
                                legacyKey,
                                role.defaultArgb,
                            )

                        light ->
                            role.lightArgb

                        else ->
                            role.defaultArgb
                    }
                }.toMutableMap()
        val targetRole = SugarliciousColorRole.TARGET_VALUE
        val targetHasExplicitColor =
            preferences.contains(OVERRIDE_PREFIX + targetRole.preferenceKey) ||
                preferences.contains(prefix + targetRole.preferenceKey) ||
                (!light && preferences.contains(LEGACY_PREFIX + targetRole.preferenceKey))
        if (!targetHasExplicitColor) {
            values[targetRole] = derivedTargetValueArgb(values.getValue(SugarliciousColorRole.RANGE_IN_RANGE))
        }

        return SugarliciousPalette(
            values = values,
            isLight = light,
        )
    }

    fun save(
        preferences: SharedPreferences,
        role: SugarliciousColorRole,
        argb: Int,
    ) {
        preferences.edit {
            putInt(
                OVERRIDE_PREFIX +
                    role.preferenceKey,
                argb,
            )
        }
    }

    fun reset(
        preferences: SharedPreferences,
        role: SugarliciousColorRole,
    ) {
        val light = isLight(preferences)
        preferences.edit {
            remove(OVERRIDE_PREFIX + role.preferenceKey)
            remove(
                (if (light) LIGHT_PREFIX else DARK_PREFIX) +
                    role.preferenceKey,
            )

            if (!light) {
                remove(
                    LEGACY_PREFIX +
                        role.preferenceKey,
                )
            }
        }
    }

    fun resetAll(preferences: SharedPreferences) {
        preferences.edit {
            SugarliciousColorRole.entries.forEach { role ->
                remove(
                    LEGACY_PREFIX +
                        role.preferenceKey,
                )
                remove(
                    DARK_PREFIX +
                        role.preferenceKey,
                )
                remove(
                    LIGHT_PREFIX +
                        role.preferenceKey,
                )
                remove(
                    OVERRIDE_PREFIX +
                        role.preferenceKey,
                )
            }
        }
    }

    fun hasUserOverride(
        preferences: SharedPreferences,
        role: SugarliciousColorRole,
    ): Boolean =
        preferences.contains(OVERRIDE_PREFIX + role.preferenceKey) ||
            preferences.contains((if (isLight(preferences)) LIGHT_PREFIX else DARK_PREFIX) + role.preferenceKey) ||
            (!isLight(preferences) && preferences.contains(LEGACY_PREFIX + role.preferenceKey))
}

/**
 * Runtime palette shared by classic Views, custom Canvas charts and Compose.
 *
 * The backing palette is Compose state so open Compose screens recompose
 * immediately when a color is saved in Settings.
 */
object SugarliciousColors {
    var palette by mutableStateOf(
        SugarliciousPalette.defaults(),
    )
        private set

    fun apply(palette: SugarliciousPalette) {
        this.palette = palette
    }

    fun argb(role: SugarliciousColorRole): Int =
        palette.argb(role)

    fun color(role: SugarliciousColorRole): Color =
        palette.compose(role)

    val Primary get() = color(SugarliciousColorRole.PRIMARY)
    val OnPrimary get() = color(SugarliciousColorRole.ON_PRIMARY)
    val Secondary get() = color(SugarliciousColorRole.SECONDARY)
    val OnSecondary get() = color(SugarliciousColorRole.ON_SECONDARY)

    val Background get() = color(SugarliciousColorRole.BACKGROUND)
    val Surface get() = color(SugarliciousColorRole.SURFACE)
    val SurfaceHigh get() = color(SugarliciousColorRole.SURFACE_HIGH)
    val SurfaceRaised get() = color(SugarliciousColorRole.SURFACE_RAISED)
    val SurfaceSelected get() = color(SugarliciousColorRole.SURFACE_SELECTED)
    val Border get() = color(SugarliciousColorRole.BORDER)

    val TextPrimary get() = color(SugarliciousColorRole.TEXT_PRIMARY)
    val TextSecondary get() = color(SugarliciousColorRole.TEXT_SECONDARY)

    val GlucoseLow get() = color(SugarliciousColorRole.GLUCOSE_LOW)
    val GlucoseInRange get() = color(SugarliciousColorRole.GLUCOSE_IN_RANGE)
    val GlucoseHigh get() = color(SugarliciousColorRole.GLUCOSE_HIGH)
    val TargetBand get() = color(SugarliciousColorRole.RANGE_IN_RANGE)
    val TargetValue get() = color(SugarliciousColorRole.TARGET_VALUE)

    val Green get() = color(SugarliciousColorRole.GREEN)
    val Blue get() = color(SugarliciousColorRole.BLUE)
    val Orange get() = color(SugarliciousColorRole.ORANGE)
    val Yellow get() = color(SugarliciousColorRole.YELLOW)
    val Red get() = color(SugarliciousColorRole.RED)
}
