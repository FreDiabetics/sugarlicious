package app.aapswear.mobile

import app.aapswear.model.CgmGraphScaleMode
import app.aapswear.model.CgmGraphYScale
import app.aapswear.model.CgmThresholds
import app.aapswear.model.isDynamic
import app.aapswear.model.isLogarithmic
import kotlin.math.ceil
import kotlin.math.floor

/** Mobile display policy; predictions never participate in the observation-based bounds. */
internal fun resolveMobileCgmScale(
    mode: CgmGraphScaleMode,
    measuredValues: List<Double>,
    configuredMinimum: Double,
    configuredMaximum: Double,
    thresholds: CgmThresholds,
): CgmGraphYScale {
    val resolved =
        CgmGraphYScale.resolve(
            mode = mode,
            visibleValuesMgDl = measuredValues,
            staticMinimumMgDl = if (mode.isDynamic) CgmGraphYScale.SENSOR_FLOOR_MG_DL else configuredMinimum,
            staticMaximumMgDl = configuredMaximum,
            requiredValuesMgDl = listOf(thresholds.lowMgDl, thresholds.highMgDl),
        )
    return resolved.copy(
        minimumMgDl = if (mode.isDynamic) floor(resolved.minimumMgDl / 5.0) * 5.0 else resolved.minimumMgDl,
        maximumMgDl = if (mode.isDynamic) ceil(resolved.maximumMgDl / 5.0) * 5.0 else resolved.maximumMgDl,
        logarithmicOffsetMgDl = if (mode.isLogarithmic) 200.0 else 0.0,
    )
}
