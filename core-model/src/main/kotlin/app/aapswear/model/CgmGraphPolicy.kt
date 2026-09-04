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

        val recent = mutableListOf<GlucoseSample>()
        val seen = mutableSetOf<List<Any?>>()
        var latestMeasuredAt: Long? = null
        samples.sortedBy { it.receivedAtEpochMs ?: it.measuredAtEpochMs }.forEach { sample ->
            if (
                sample.quality != CgmQuality.VALID ||
                !sample.valueMgDl.isFinite() ||
                sample.valueMgDl !in 20.0..1_000.0
            ) return@forEach
            val identity = listOf(sample.sensorId, sample.sessionId, sample.measuredAtEpochMs, sample.source)
            if (!seen.add(identity)) return@forEach

            val previousMeasuredAt = latestMeasuredAt
            if (previousMeasuredAt != null && sample.measuredAtEpochMs <= previousMeasuredAt) {
                // Backfill/out-of-order history is displayable, but is not a new live semantic event.
                return@forEach
            }
            val previous = recent.lastOrNull()
            if (previous != null) {
                val sensorChanged = previous.sensorId != null && sample.sensorId != null && previous.sensorId != sample.sensorId
                val sessionChanged = previous.sessionId != null && sample.sessionId != null && previous.sessionId != sample.sessionId
                val sourceChanged = previous.source != sample.source
                val gap = sample.measuredAtEpochMs - previous.measuredAtEpochMs
                if (sensorChanged || sessionChanged || sourceChanged || gap !in 1L..MAX_GAP_MS) recent.clear()
            }
            recent += sample
            latestMeasuredAt = sample.measuredAtEpochMs
            if (recent.size > REQUIRED_POINTS) recent.removeAt(0)
        }

        if (recent.size < REQUIRED_POINTS) return null
        val first = recent.first()
        val second = recent.last()
        if (second.measuredAtEpochMs - first.measuredAtEpochMs !in 1L..MAX_GAP_MS) return null
        if (first.sensorId != null && second.sensorId != null && first.sensorId != second.sensorId) return null
        if (first.sessionId != null && second.sessionId != null && first.sessionId != second.sessionId) return null
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
