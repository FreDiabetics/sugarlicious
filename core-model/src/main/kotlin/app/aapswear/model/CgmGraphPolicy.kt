package app.aapswear.model

enum class RangeExcursion {
    LOW,
    HIGH,
}

/**
 * Visual range status is deliberately conservative: one out-of-range point is not enough, while
 * two consecutive, validated points from one compatible stream activate the corresponding tint.
 *
 * The canonical threshold policy owns the boundary semantics. VERY_LOW/VERY_HIGH do not create
 * additional graph regions; they collapse into the existing LOW/HIGH background excursion.
 */
object CgmGraphPolicy {
    private const val REQUIRED_POINTS = 2
    private const val MAX_GAP_MS = 8L * 60_000L

    fun rangeExcursion(
        samples: List<GlucoseSample>,
        thresholds: CgmThresholds,
    ): RangeExcursion? {
        if (!thresholds.isValid) return null

        val recent =
            samples
                .asSequence()
                .filter {
                    it.quality == CgmQuality.VALID &&
                        it.valueMgDl.isFinite() &&
                        it.valueMgDl in 20.0..1_000.0
                }
                .sortedBy(GlucoseSample::measuredAtEpochMs)
                .distinctBy {
                    listOf(
                        it.sensorId,
                        it.sessionId,
                        it.sequenceNumber,
                        it.measuredAtEpochMs,
                        it.source,
                    )
                }
                .toList()
                .takeLast(REQUIRED_POINTS)

        if (recent.size < REQUIRED_POINTS) return null
        val first = recent.first()
        val second = recent.last()
        if (second.measuredAtEpochMs - first.measuredAtEpochMs !in 1L..MAX_GAP_MS) return null
        if (first.sensorId != null && second.sensorId != null && first.sensorId != second.sensorId) return null
        if (first.sessionId != null && second.sessionId != null && first.sessionId != second.sessionId) return null
        val sameKnownSensorSession =
            first.sensorId != null &&
                first.sessionId != null &&
                first.sensorId == second.sensorId &&
                first.sessionId == second.sessionId
        if (first.source != second.source && !sameKnownSensorSession) return null

        return when {
            recent.all {
                thresholds.classify(it.valueMgDl) == CgmRangeClass.LOW ||
                    thresholds.classify(it.valueMgDl) == CgmRangeClass.VERY_LOW
            } -> RangeExcursion.LOW
            recent.all {
                thresholds.classify(it.valueMgDl) == CgmRangeClass.HIGH ||
                    thresholds.classify(it.valueMgDl) == CgmRangeClass.VERY_HIGH
            } -> RangeExcursion.HIGH
            else -> null
        }
    }

    /** Compatibility overload for existing callers while they migrate to the central policy. */
    fun rangeExcursion(
        samples: List<GlucoseSample>,
        lowMgDl: Double,
        highMgDl: Double,
    ): RangeExcursion? =
        rangeExcursion(
            samples,
            CgmThresholds(
                veryHighMgDl = maxOf(CgmThresholds.DEFAULT_VERY_HIGH_MG_DL, highMgDl + 1.0),
                highMgDl = highMgDl,
                lowMgDl = lowMgDl,
                veryLowMgDl = minOf(CgmThresholds.DEFAULT_VERY_LOW_MG_DL, lowMgDl - 1.0),
            ),
        )
}
