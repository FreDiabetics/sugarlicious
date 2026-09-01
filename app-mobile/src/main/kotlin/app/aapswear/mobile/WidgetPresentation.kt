package app.aapswear.mobile

import app.aapswear.model.CgmGraphPolicy
import app.aapswear.model.CgmThresholds
import app.aapswear.model.GlucoseSample
import app.aapswear.model.RangeExcursion
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.TherapyDisplayFormatter

internal enum class WidgetWidthClass { VERY_NARROW, NARROW, REGULAR, WIDE, VERY_WIDE }
internal enum class WidgetHeightClass { LOW, REGULAR, HIGH }

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

internal fun responsiveWidgetLayout(widthDp: Float, heightDp: Float): ResponsiveWidgetLayout {
    val widthClass = when {
        widthDp < 120f -> WidgetWidthClass.VERY_NARROW
        widthDp < 180f -> WidgetWidthClass.NARROW
        widthDp < 300f -> WidgetWidthClass.REGULAR
        widthDp < 480f -> WidgetWidthClass.WIDE
        else -> WidgetWidthClass.VERY_WIDE
    }
    val heightClass = when {
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
    val role = when (excursion) {
        RangeExcursion.HIGH -> WidgetColorRole.HIGH
        RangeExcursion.LOW -> WidgetColorRole.LOW
        null -> WidgetColorRole.IN_RANGE
    }
    return WidgetRangePresentation(excursion, role)
}
