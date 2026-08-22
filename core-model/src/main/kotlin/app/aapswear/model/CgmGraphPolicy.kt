package app.aapswear.model

enum class RangeExcursion {
    LOW,
    HIGH,
}

/**
 * Visual range status is deliberately conservative: one out-of-range point is not enough, while
 * two consecutive, validated points from one compatible stream activate the corresponding tint.
 */
object CgmGraphPolicy {
    private const val REQUIRED_POINTS = 2
    private const val MAX_GAP_MS = 8L * 60_000L

    fun rangeExcursion(
        samples: List<GlucoseSample>,
        lowMgDl: Double,
        highMgDl: Double,
    ): RangeExcursion? {
        if (!lowMgDl.isFinite() || !highMgDl.isFinite() || lowMgDl >= highMgDl) return null

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
            recent.all { it.valueMgDl < lowMgDl } -> RangeExcursion.LOW
            recent.all { it.valueMgDl > highMgDl } -> RangeExcursion.HIGH
            else -> null
        }
    }
}
