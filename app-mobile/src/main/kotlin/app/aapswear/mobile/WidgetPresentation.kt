package app.aapswear.mobile

import android.content.Context
import app.aapswear.model.CgmGraphPolicy
import app.aapswear.model.CgmThresholds
import app.aapswear.model.GlucoseSample
import app.aapswear.model.RangeExcursion
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

internal enum class WidgetWidthClass { VERY_NARROW, NARROW, REGULAR, WIDE, VERY_WIDE }

internal enum class WidgetHeightClass { LOW, REGULAR, HIGH }

internal enum class WidgetFamilyLayout { COMPACT_2X1, DETAIL_2X2, WIDE_4X2 }

internal fun widgetFamilyLayout(
    widthDp: Float,
    heightDp: Float,
): WidgetFamilyLayout =
    when {
        widthDp >= 300f -> WidgetFamilyLayout.WIDE_4X2
        heightDp >= 135f -> WidgetFamilyLayout.DETAIL_2X2
        else -> WidgetFamilyLayout.COMPACT_2X1
    }

internal data class WidgetGlucoseFamilyText(
    val age: String? = null,
    val delta: String? = null,
    val tir: String? = null,
)

internal fun widgetGlucoseFamilyText(
    layout: WidgetFamilyLayout,
    age: String,
    delta: String,
    unit: String,
    tirPercent: Int?,
): WidgetGlucoseFamilyText =
    when (layout) {
        WidgetFamilyLayout.COMPACT_2X1 -> WidgetGlucoseFamilyText(age = age, delta = delta)
        WidgetFamilyLayout.DETAIL_2X2,
        WidgetFamilyLayout.WIDE_4X2,
        ->
            WidgetGlucoseFamilyText(
                delta = "$delta $unit",
                age = "$age her",
                tir = "Im Ziel: ${tirPercent?.let { "$it%" } ?: "--"}",
            )
    }

internal fun currentDayTirPercent(
    samples: List<GlucoseSample>,
    targetLowMgDl: Double,
    targetHighMgDl: Double,
    nowEpochMs: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Int? {
    val startOfDay =
        Instant
            .ofEpochMilli(nowEpochMs)
            .atZone(zoneId)
            .toLocalDate()
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
    val valid =
        samples
            .asSequence()
            .filter { it.measuredAtEpochMs in startOfDay..(nowEpochMs + 5 * 60_000L) }
            .filter { it.valueMgDl in 20.0..1000.0 }
            .associateBy { it.measuredAtEpochMs }
            .values
    if (valid.isEmpty()) return null
    return (valid.count { it.valueMgDl in targetLowMgDl..targetHighMgDl } * 100.0 / valid.size).roundToInt()
}

internal const val SAMSUNG_COMPACT_WIDGET_HORIZONTAL_INSET_DP = 8f
internal const val SAMSUNG_COMPACT_WIDGET_VERTICAL_INSET_DP = 9f

internal object WidgetLayoutSizeStore {
    private const val PREFERENCES = "dashboard_ui"
    private const val KEY_SAMSUNG_COMPACT = "widget.layout.samsung_compact"

    fun isSamsungCompact(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_SAMSUNG_COMPACT, true)

    fun setSamsungCompact(
        context: Context,
        enabled: Boolean,
    ) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SAMSUNG_COMPACT, enabled)
            .apply()
    }
}

internal data class ResponsiveWidgetLayout(
    val widthClass: WidgetWidthClass,
    val heightClass: WidgetHeightClass,
    val cornerRadiusDp: Float,
    val paddingDp: Float,
    val glucoseTextSp: Float,
    val graphDotRadiusDp: Float,
    val graphOutlineDp: Float,
    val graphAxisTextSp: Float,
    val graphLineDp: Float,
)

internal fun responsiveWidgetLayout(
    widthDp: Float,
    heightDp: Float,
): ResponsiveWidgetLayout {
    val widthClass =
        when {
            widthDp < 120f -> WidgetWidthClass.VERY_NARROW
            widthDp < 180f -> WidgetWidthClass.NARROW
            widthDp < 300f -> WidgetWidthClass.REGULAR
            widthDp < 480f -> WidgetWidthClass.WIDE
            else -> WidgetWidthClass.VERY_WIDE
        }
    val heightClass =
        when {
            heightDp < 90f -> WidgetHeightClass.LOW
            heightDp < 190f -> WidgetHeightClass.REGULAR
            else -> WidgetHeightClass.HIGH
        }
    val limiting = minOf(widthDp / 3.25f, heightDp * 0.62f)
    return ResponsiveWidgetLayout(
        widthClass = widthClass,
        heightClass = heightClass,
        cornerRadiusDp = (minOf(widthDp, heightDp) * 0.12f).coerceIn(12f, 28f),
        paddingDp = (minOf(widthDp, heightDp) * 0.08f).coerceIn(4f, 18f),
        glucoseTextSp = limiting.coerceIn(27f, 72f),
        // Match the Mobile graph's default visual weight. A larger widget gains plot area; it
        // must not turn the CGM samples, outlines or boundaries into oversized artwork.
        graphDotRadiusDp = 2.4f,
        graphOutlineDp = 0.95f,
        graphAxisTextSp = (heightDp * 0.055f).coerceIn(8f, 11f),
        graphLineDp = 1f,
    )
}

internal data class WidgetRangePresentation(
    val excursion: RangeExcursion?,
    val visibleRole: WidgetColorRole,
)

internal fun widgetRangePresentation(
    state: TherapyDisplayState?,
    samples: List<GlucoseSample>,
    thresholds: CgmThresholds,
    now: Long,
): WidgetRangePresentation {
    if (!TherapyDisplayFormatter.isGlucoseKnown(state)) {
        return WidgetRangePresentation(null, WidgetColorRole.TEXT)
    }
    val excursion = CgmGraphPolicy.rangeExcursion(samples, thresholds)
    // The graph area deliberately waits for two consecutive readings before activating an
    // excursion. The displayed CGM value is an instantaneous measurement and must therefore use
    // the latest value directly instead of inheriting that confirmation delay.
    val currentValue = state?.glucose?.valueMgDl ?: return WidgetRangePresentation(excursion, WidgetColorRole.TEXT)
    val role = widgetGlucoseColorRole(currentValue, thresholds)
    return WidgetRangePresentation(excursion, role)
}
