package app.aapswear.mobile

import android.content.SharedPreferences
import app.aapswear.mobile.ui.theme.SugarliciousColorStore
import app.aapswear.model.AppearanceMode
import app.aapswear.protocol.WatchGraphStyle

internal fun activeGraphAppearanceMode(preferences: SharedPreferences): AppearanceMode =
    SugarliciousColorStore.activeMode(preferences)

internal fun readMobileGraphStyle(
    preferences: SharedPreferences,
    mode: AppearanceMode = activeGraphAppearanceMode(preferences),
): WatchGraphStyle {
    return WatchGraphStyle(
        cgmDotRadiusDp = preferences.getFloat(graphAppearanceKey(mode, "dotRadiusDp"), preferences.getFloat("cgm.dotRadiusDp", 2.4f)).coerceIn(1.5f, 6f),
        cgmDotOutlineEnabled = preferences.getBoolean(graphAppearanceKey(mode, "dotOutlineEnabled"), preferences.getBoolean("cgm.dotOutlineEnabled", true)),
        cgmDotOutlineWidthDp = preferences.getFloat(graphAppearanceKey(mode, "dotOutlineWidthDp"), preferences.getFloat("cgm.dotOutlineWidthDp", 0.95f)).coerceIn(0.25f, 3f),
    )
}

internal fun readMobilePredictionDotRadius(
    preferences: SharedPreferences,
    mode: AppearanceMode = activeGraphAppearanceMode(preferences),
): Float = preferences.getFloat(
    graphAppearanceKey(mode, "prediction.dotRadiusDp"),
    preferences.getFloat("cgm.prediction.dotRadiusDp", 1.75f),
).coerceIn(1.0f, 6.0f)

internal fun readMobilePredictionDotOutlineWidth(
    preferences: SharedPreferences,
    mode: AppearanceMode = activeGraphAppearanceMode(preferences),
): Float = preferences.getFloat(
    graphAppearanceKey(mode, "prediction.dotOutlineWidthDp"),
    preferences.getFloat("cgm.prediction.dotOutlineWidthDp", 0.70f),
).coerceIn(0.0f, 3.0f)
