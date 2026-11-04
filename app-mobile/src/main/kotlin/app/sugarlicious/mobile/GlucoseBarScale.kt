package app.sugarlicious.mobile

import app.sugarlicious.model.CgmRangeClass
import app.sugarlicious.model.CgmThresholds

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
    val (minimum, maximum) =
        when (range) {
            GlucoseBarRange.LOW -> GLUCOSE_BAR_MINIMUM to thresholds.lowMgDl
            GlucoseBarRange.TARGET -> thresholds.lowMgDl to thresholds.highMgDl
            GlucoseBarRange.HIGH -> thresholds.highMgDl to GLUCOSE_BAR_MAXIMUM
        }
    val position =
        if (maximum > minimum) {
            ((requireNotNull(value) - minimum) / (maximum - minimum)).coerceIn(0.0, 1.0).toFloat()
        } else {
            if (range == GlucoseBarRange.LOW) 0f else 1f
        }
    return GlucoseBarState(range, position)
}

private const val GLUCOSE_BAR_MINIMUM = 40.0
private const val GLUCOSE_BAR_MAXIMUM = 400.0
