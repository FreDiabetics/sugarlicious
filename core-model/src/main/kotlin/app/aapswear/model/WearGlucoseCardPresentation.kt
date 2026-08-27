package app.aapswear.model

import kotlin.math.roundToInt

/** Canonical content and geometry for the glucose cards inside both Wear applications. */
data class WearGlucoseCardInput(
    val valueMgDl: Double?,
    val displayUnit: GlucoseUnit,
    val deltaMgDl: Double?,
    val trend: Trend,
    val measuredAtEpochMs: Long?,
    val quality: CgmQuality = CgmQuality.VALID,
    val sourceLabel: String = "",
)

data class WearGlucoseCardPresentation(
    val value: String,
    val primaryMeta: String,
    val secondaryMeta: String,
    val trend: Trend?,
    val freshness: Freshness,
    val rangeClass: CgmRangeClass?,
    val displayable: Boolean,
)

object WearGlucoseCardStyle {
    const val CARD_RADIUS_DP = 26f
    const val VALUE_TEXT_SP = 44f
    const val META_TEXT_SP = 14f
    const val CARD_HEIGHT_DP = 110
    const val HORIZONTAL_PADDING_DP = 14
    const val VERTICAL_PADDING_DP = 8
    const val TREND_SIZE_DP = 27
    const val TREND_GAP_DP = 6
}

fun wearGlucoseCardPresentation(
    input: WearGlucoseCardInput,
    thresholds: CgmThresholds,
    nowEpochMs: Long,
): WearGlucoseCardPresentation {
    val value = input.valueMgDl
    val freshness = when {
        input.quality == CgmQuality.SENSOR_ERROR -> Freshness.ERROR
        input.quality != CgmQuality.VALID || value == null || !value.isFinite() || value !in 20.0..1_000.0 -> Freshness.NO_DATA
        else -> FreshnessPolicy.classify(input.measuredAtEpochMs, nowEpochMs)
    }
    val displayable = freshness == Freshness.CURRENT || freshness == Freshness.DELAYED
    val unit = if (input.displayUnit == GlucoseUnit.MMOL_L) "mmol/L" else "mg/dL"
    val formattedValue = if (!displayable || value == null) {
        "—"
    } else if (input.displayUnit == GlucoseUnit.MMOL_L) {
        String.format(java.util.Locale.US, "%.1f", value / 18.0)
    } else {
        value.roundToInt().toString()
    }
    val delta = TherapyDisplayFormatter.signedDelta(input.deltaMgDl, input.displayUnit).ifBlank { "—" }
    val age = TherapyDisplayFormatter.ageMinutesValue(input.measuredAtEpochMs, nowEpochMs)?.let { "$it min" }.orEmpty()
    val stateText = when (freshness) {
        Freshness.CURRENT -> age
        Freshness.DELAYED -> listOf("Verzögert", age).filter(String::isNotBlank).joinToString(" · ")
        Freshness.STALE -> "Keine aktuellen CGM-Daten"
        Freshness.ERROR -> "Sensorfehler"
        Freshness.NO_DATA -> "Keine CGM-Daten"
    }
    return WearGlucoseCardPresentation(
        value = formattedValue,
        primaryMeta = if (displayable) "Δ $delta · $unit" else unit,
        secondaryMeta = listOf(stateText, input.sourceLabel).filter(String::isNotBlank).joinToString(" · "),
        trend = input.trend.takeIf { displayable && it != Trend.UNKNOWN },
        freshness = freshness,
        rangeClass = value?.takeIf { displayable }?.let(thresholds::classify),
        displayable = displayable,
    )
}
