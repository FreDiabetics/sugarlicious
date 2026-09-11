package app.aapswear.g7watch

import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.model.CgmGraphPolicy
import app.aapswear.model.CgmQuality
import app.aapswear.model.CgmThresholds
import app.aapswear.model.GlucoseSample
import app.aapswear.model.RangeExcursion

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

        val thresholds = CgmThresholds(
            veryHighMgDl = maxOf(CgmThresholds.DEFAULT_VERY_HIGH_MG_DL, highMgDl + 1.0),
            highMgDl = highMgDl,
            lowMgDl = lowMgDl,
            veryLowMgDl = minOf(CgmThresholds.DEFAULT_VERY_LOW_MG_DL, lowMgDl - 1.0),
        )
        val excursion = CgmGraphPolicy.rangeExcursion(
            readings.map { reading ->
                GlucoseSample(
                    valueMgDl = reading.glucoseMgDl,
                    measuredAtEpochMs = reading.timestampEpochMs,
                    source = reading.source,
                    sensorId = reading.sensorId,
                    sessionId = reading.sessionId,
                    sequenceNumber = reading.sequenceNumber,
                    receivedAtEpochMs = reading.receivedAtEpochMs,
                    quality = if (isValidReading(reading)) CgmQuality.VALID else CgmQuality.INVALID,
                )
            },
            thresholds,
        )
        return when (excursion) {
            RangeExcursion.HIGH -> G7RangeExcursion.HIGH
            RangeExcursion.LOW -> G7RangeExcursion.LOW
            null -> G7RangeExcursion.NONE
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
