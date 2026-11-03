package app.aapswear.mobile

import app.aapswear.model.CgmThresholds

internal data class GlucoseBarScale(
    val minimum: Double,
    val maximum: Double,
) {
    fun fraction(value: Double): Float? = value.takeIf { it.isFinite() }?.let { ((it - minimum) / (maximum - minimum)).coerceIn(0.0, 1.0).toFloat() }
}

internal fun glucoseBarScale(thresholds: CgmThresholds): GlucoseBarScale =
    GlucoseBarScale(0.0, maxOf(400.0, thresholds.veryHighMgDl + 50.0))

internal data class GlucoseBarDistribution(
    val lowEnd: Float,
    val highStart: Float,
)

internal fun glucoseBarDistribution(stats: MeasuredRangeStats): GlucoseBarDistribution? {
    if (stats.coveredDurationMs <= 0) return null
    val measuredLow = stats.belowDurationMs
    val measuredHigh = stats.aboveDurationMs
    if (measuredLow != null && measuredHigh != null) {
        val total = stats.coveredDurationMs.toDouble()
        return GlucoseBarDistribution((measuredLow / total).toFloat().coerceIn(0f, 1f), (1.0 - measuredHigh / total).toFloat().coerceIn(0f, 1f))
    }
    val low = stats.below ?: return null
    val target = stats.inRange ?: return null
    val high = stats.above ?: return null
    val total = (low + target + high).takeIf { it > 0 } ?: return null
    return GlucoseBarDistribution(low.toFloat() / total, (low + target).toFloat() / total)
}

internal fun glucoseBarMarker(value: Double, thresholds: CgmThresholds, distribution: GlucoseBarDistribution): Float? {
    if (!value.isFinite() || !thresholds.isValid) return null
    val maximum = glucoseBarScale(thresholds).maximum
    return when {
        value <= thresholds.lowMgDl -> (value / thresholds.lowMgDl).toFloat().coerceIn(0f, 1f) * distribution.lowEnd
        value < thresholds.highMgDl -> distribution.lowEnd + ((value - thresholds.lowMgDl) / (thresholds.highMgDl - thresholds.lowMgDl)).toFloat() * (distribution.highStart - distribution.lowEnd)
        else -> distribution.highStart + ((value - thresholds.highMgDl) / (maximum - thresholds.highMgDl)).toFloat().coerceIn(0f, 1f) * (1f - distribution.highStart)
    }
}
