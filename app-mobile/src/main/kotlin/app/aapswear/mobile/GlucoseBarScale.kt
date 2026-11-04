package app.aapswear.mobile

import app.aapswear.model.CgmRangeClass
import app.aapswear.model.CgmThresholds

internal enum class GlucoseBarRange { LOW, TARGET, HIGH }

internal data class GlucoseBarState(
    val range: GlucoseBarRange,
    val position: Float,
)

/** Presentation-only clamping; measurements and their quality are never modified. */
internal fun glucoseBarState(value: Double?, thresholds: CgmThresholds): GlucoseBarState? {
    val range =
        when (value?.let(thresholds::classify)) {
            CgmRangeClass.VERY_LOW, CgmRangeClass.LOW -> GlucoseBarRange.LOW
            CgmRangeClass.IN_RANGE -> GlucoseBarRange.TARGET
            CgmRangeClass.HIGH, CgmRangeClass.VERY_HIGH -> GlucoseBarRange.HIGH
            null -> return null
        }
    if (thresholds.lowMgDl <= GLUCOSE_BAR_MINIMUM || thresholds.highMgDl >= GLUCOSE_BAR_MAXIMUM) return null
    val (minimum, maximum) =
        when (range) {
            GlucoseBarRange.LOW -> GLUCOSE_BAR_MINIMUM to thresholds.lowMgDl
            GlucoseBarRange.TARGET -> thresholds.lowMgDl to thresholds.highMgDl
            GlucoseBarRange.HIGH -> thresholds.highMgDl to GLUCOSE_BAR_MAXIMUM
        }
    return GlucoseBarState(range, ((requireNotNull(value) - minimum) / (maximum - minimum)).coerceIn(0.0, 1.0).toFloat())
}

private const val GLUCOSE_BAR_MINIMUM = 40.0
private const val GLUCOSE_BAR_MAXIMUM = 400.0
