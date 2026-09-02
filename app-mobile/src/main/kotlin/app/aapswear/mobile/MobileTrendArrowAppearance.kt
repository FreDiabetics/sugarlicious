package app.aapswear.mobile

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColorStore
import app.aapswear.model.AppearanceMode
import app.aapswear.model.TrendArrowStyle
import app.aapswear.storage.TrendArrowStylePreferences

internal object MobileTrendArrowAppearance {
    var style by mutableStateOf(TrendArrowStyle.defaults(AppearanceMode.DARK, 0xFFF5F5F5.toInt()))
        private set

    fun load(preferences: SharedPreferences, mode: AppearanceMode = SugarliciousColorStore.activeMode(preferences)): TrendArrowStyle =
        TrendArrowStylePreferences.read(
            preferences,
            mode,
            SugarliciousColorStore.load(preferences, mode).argb(SugarliciousColorRole.GLUCOSE_IN_RANGE),
            legacyScaleKey = DashboardUiPreferences.MOBILE_TREND_SCALE_KEY,
        )

    fun apply(preferences: SharedPreferences) {
        style = load(preferences)
    }

    fun save(preferences: SharedPreferences, mode: AppearanceMode, value: TrendArrowStyle) {
        TrendArrowStylePreferences.write(preferences, mode, value)
        style = value.normalized()
    }
}
