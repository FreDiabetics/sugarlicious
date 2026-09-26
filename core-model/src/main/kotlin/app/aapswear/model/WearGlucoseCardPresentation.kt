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
    val TREND_SIZE_DP: Int = GlucoseTrendSizing.arrowHeightForGlucoseHeight(VALUE_TEXT_SP).roundToInt()
    const val TREND_GAP_DP = 6
}

enum class SugarWearTypographyRole { GLUCOSE_VALUE, META, TILE_TITLE, STATUS }

data class SugarWearTypographySpec(
    val sizeSp: Float,
    val appBold: Boolean,
    val protoWeight: Int,
    val fontFamily: String,
)

/** Semantic type tokens shared by the SugarWear app and its ProtoLayout tiles. */
object SugarWearTypography {
    /** Android Typeface family used by the in-app canvas/view renderer. */
    const val APP_FONT_FAMILY = "sans-serif"

    /** Empty means ProtoLayout's default system family, matching Android's sans-serif. */
    const val PROTO_FONT_FAMILY = ""

    fun spec(role: SugarWearTypographyRole): SugarWearTypographySpec =
        when (role) {
            SugarWearTypographyRole.GLUCOSE_VALUE -> SugarWearTypographySpec(WearGlucoseCardStyle.VALUE_TEXT_SP, true, 700, PROTO_FONT_FAMILY)
            SugarWearTypographyRole.META -> SugarWearTypographySpec(WearGlucoseCardStyle.META_TEXT_SP, true, 700, PROTO_FONT_FAMILY)
            SugarWearTypographyRole.TILE_TITLE -> SugarWearTypographySpec(11f, true, 700, PROTO_FONT_FAMILY)
            SugarWearTypographyRole.STATUS -> SugarWearTypographySpec(10f, true, 700, PROTO_FONT_FAMILY)
        }

    fun protoWeight(emphasized: Boolean): Int = if (emphasized) 700 else 400
}

fun wearGlucoseCardPresentation(
    input: WearGlucoseCardInput,
    thresholds: CgmThresholds,
    nowEpochMs: Long,
): WearGlucoseCardPresentation {
    val value = input.valueMgDl
    val freshness =
        when {
            input.quality == CgmQuality.SENSOR_ERROR -> Freshness.ERROR
            input.quality != CgmQuality.VALID || value == null || !value.isFinite() || value !in 20.0..1_000.0 -> Freshness.NO_DATA
            else -> FreshnessPolicy.classify(input.measuredAtEpochMs, nowEpochMs)
        }
    val displayable = input.quality == CgmQuality.VALID && value != null && value.isFinite() && value in 20.0..1_000.0
    val fresh = freshness == Freshness.CURRENT || freshness == Freshness.DELAYED
    val unit = if (input.displayUnit == GlucoseUnit.MMOL_L) "mmol/L" else "mg/dL"
    val formattedValue =
        if (!displayable) {
            "—"
        } else if (input.displayUnit == GlucoseUnit.MMOL_L) {
            String.format(java.util.Locale.US, "%.1f", value / 18.0)
        } else {
            value.roundToInt().toString()
        }
    val delta = TherapyDisplayFormatter.signedDelta(input.deltaMgDl, input.displayUnit).ifBlank { "—" }
    val age = TherapyDisplayFormatter.ageMinutesValue(input.measuredAtEpochMs, nowEpochMs)?.let { "${it}m" }.orEmpty()
    val stateText =
        when (freshness) {
            Freshness.CURRENT -> age
            Freshness.DELAYED -> age
            Freshness.STALE -> "Keine aktuellen CGM-Daten"
            Freshness.SIGNAL_LOSS -> "Signalverlust"
            Freshness.ERROR -> "Sensorfehler"
            Freshness.NO_DATA -> "Keine CGM-Daten"
        }
    return WearGlucoseCardPresentation(
        value = formattedValue,
        primaryMeta = if (displayable) listOf("$delta $unit", stateText).filter(String::isNotBlank).joinToString(" · ") else stateText,
        secondaryMeta = "",
        trend = input.trend.takeIf { fresh && it != Trend.UNKNOWN },
        freshness = freshness,
        rangeClass = value?.takeIf { displayable }?.let(thresholds::classify),
        displayable = displayable,
    )
}
