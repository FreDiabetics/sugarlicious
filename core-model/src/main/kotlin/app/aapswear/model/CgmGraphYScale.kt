package app.aapswear.model

/**
 * STATIC = fixed linear bounds; DYNAMIC = visible-window linear bounds;
 * LOGARITHMIC = fixed logarithmic bounds; LOGARITHMIC_DYNAMIC = visible-window log bounds.
 */
enum class CgmGraphScaleMode { STATIC, DYNAMIC, LOGARITHMIC, LOGARITHMIC_DYNAMIC }

/** One canonical CGM value-to-screen transform shared by every layer in a graph render. */
data class CgmGraphYScale(
    val mode: CgmGraphScaleMode,
    val minimumMgDl: Double,
    val maximumMgDl: Double,
) {
    init {
        require(minimumMgDl > 0.0)
        require(maximumMgDl > minimumMgDl)
    }

    fun ratio(valueMgDl: Double): Double = asAxisScale().ratio(valueMgDl)

    fun inverseRatio(ratio: Double): Double = asAxisScale().inverseRatio(ratio)

    private fun asAxisScale() =
        GraphAxisScale(
            mode = mode,
            bounds = GraphBounds(minimumMgDl, maximumMgDl),
            logarithmicDomain = LogarithmicDomain.POSITIVE,
        )

    companion object {
        const val DEFAULT_MINIMUM_MG_DL = 40.0
        const val DEFAULT_MAXIMUM_MG_DL = 400.0

        fun resolve(
            mode: CgmGraphScaleMode,
            visibleValuesMgDl: Iterable<Double>,
            staticMinimumMgDl: Double = DEFAULT_MINIMUM_MG_DL,
            staticMaximumMgDl: Double = DEFAULT_MAXIMUM_MG_DL,
            requiredValuesMgDl: Iterable<Double> = emptyList(),
        ): CgmGraphYScale {
            val staticMin = staticMinimumMgDl.coerceIn(20.0, 300.0)
            val staticMax = staticMaximumMgDl.coerceIn(staticMin + 20.0, 1_000.0)
            if (mode == CgmGraphScaleMode.STATIC || mode == CgmGraphScaleMode.LOGARITHMIC) {
                return CgmGraphYScale(mode, staticMin, staticMax)
            }
            val values =
                (visibleValuesMgDl + requiredValuesMgDl)
                    .filter { it.isFinite() && it > 0.0 }
            if (values.isEmpty()) return CgmGraphYScale(mode, staticMin, staticMax)
            val rawMin = values.minOrNull()!!.coerceAtLeast(20.0)
            val rawMax = values.maxOrNull()!!.coerceAtLeast(rawMin + 1.0)
            val span = (rawMax - rawMin).coerceAtLeast(20.0)
            val minimum = (rawMin - span * 0.12).coerceAtLeast(20.0)
            val maximum = (rawMax + span * 0.12).coerceAtLeast(minimum + 20.0)
            return CgmGraphYScale(mode, minimum, maximum)
        }
    }
}
