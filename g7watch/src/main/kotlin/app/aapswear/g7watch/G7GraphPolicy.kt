package app.aapswear.g7watch

import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingStatus

enum class G7RangeExcursion { NONE, HIGH, LOW }

internal object G7GraphPolicy {
    const val MAX_CONTIGUOUS_GAP_MS = 8L * 60_000L
    const val STALE_AFTER_MS = 11L * 60_000L

    fun displayReadings(
        readings: List<CgmReading>,
        startEpochMs: Long,
        endEpochMs: Long,
    ): List<CgmReading> =
        readings
            .asSequence()
            .filter(::isValidReading)
            .filter { it.timestampEpochMs in startEpochMs..endEpochMs }
            .sortedBy { it.timestampEpochMs }
            .distinctBy { Triple(it.sensorId, it.sessionId, it.timestampEpochMs) }
            .toList()

    fun rangeExcursion(
        readings: List<CgmReading>,
        lowMgDl: Double,
        highMgDl: Double,
        nowEpochMs: Long,
    ): G7RangeExcursion {
        if (!lowMgDl.isFinite() || !highMgDl.isFinite() || lowMgDl >= highMgDl) return G7RangeExcursion.NONE

        val sequence = mutableListOf<CgmReading>()
        val seenIds = mutableSetOf<String>()
        val seenKeys = mutableSetOf<Triple<String, String, Long>>()
        var previousMeasuredAt: Long? = null

        readings
            .sortedBy { it.receivedAtEpochMs }
            .forEach { reading ->
                if (!isValidReading(reading)) {
                    // Non-CGM events do not create or reset semantic range state.
                    return@forEach
                }

                val key = Triple(reading.sensorId, reading.sessionId, reading.timestampEpochMs)
                if (!seenIds.add(reading.id) || !seenKeys.add(key)) {
                    // A duplicate is not a new CGM value and cannot advance the sequence.
                    return@forEach
                }

                val previous = sequence.lastOrNull()
                if (previous != null) {
                    if (
                        previous.sensorId != reading.sensorId ||
                        previous.sessionId != reading.sessionId
                    ) {
                        sequence.clear()
                    } else {
                        val gap = reading.timestampEpochMs - previous.timestampEpochMs
                        if (gap !in 1L..MAX_CONTIGUOUS_GAP_MS) {
                            sequence.clear()
                        }
                    }
                }

                val priorMeasured = previousMeasuredAt
                if (priorMeasured != null && reading.timestampEpochMs <= priorMeasured) {
                    // Backfill/out-of-order data is not a new live semantic measurement.
                    return@forEach
                }

                sequence += reading
                previousMeasuredAt = reading.timestampEpochMs
                if (sequence.size > 2) sequence.removeAt(0)
            }

        sequence.lastOrNull() ?: return G7RangeExcursion.NONE
        if (sequence.size < 2) return G7RangeExcursion.NONE

        return when {
            sequence.all { it.glucoseMgDl > highMgDl } -> G7RangeExcursion.HIGH
            sequence.all { it.glucoseMgDl < lowMgDl } -> G7RangeExcursion.LOW
            else -> G7RangeExcursion.NONE
        }
    }

    private fun isValidReading(reading: CgmReading): Boolean =
        reading.status == CgmReadingStatus.VALID &&
            reading.glucoseMgDl.isFinite() &&
            reading.glucoseMgDl in 20.0..1_000.0 &&
            reading.timestampEpochMs > 0L &&
            reading.receivedAtEpochMs > 0L &&
            reading.sensorId.isNotBlank() &&
            reading.sessionId.isNotBlank()
}
