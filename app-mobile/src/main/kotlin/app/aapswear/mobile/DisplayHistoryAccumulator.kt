package app.aapswear.mobile

import app.aapswear.model.GlucoseSample
import app.aapswear.model.CanonicalCgmHistory
import app.aapswear.model.DataSourceId
import app.aapswear.model.GlucoseState
import app.aapswear.model.TargetSample
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.TherapyHistorySample
import app.aapswear.storage.PersistentPredictionCache

/** Keeps a bounded graph cache inside the single latest display state. */
internal object DisplayHistoryAccumulator {
    const val WINDOW_MS = 24 * 60 * 60_000L
    const val MAX_POINTS = 300
    const val GAP_THRESHOLD_MS = 7 * 60_000L + 30_000L

    /** True when two persisted CGM points are farther apart than a normal 5-minute cycle. */
    fun hasGap(history: List<GlucoseSample>): Boolean =
        history.groupBy { it.sensorId to it.sessionId }.values.any { stream ->
            stream.sortedBy { it.measuredAtEpochMs }.zipWithNext().any { (first, second) ->
                second.measuredAtEpochMs - first.measuredAtEpochMs > GAP_THRESHOLD_MS
            }
        }

    fun merge(
        previous: TherapyDisplayState?,
        current: TherapyDisplayState,
        nowEpochMs: Long,
    ): TherapyDisplayState {
        val glucose = mergeGlucose(
            buildList {
                addAll(previous?.glucoseHistory.orEmpty())
                previous?.glucose?.let { add(it.toSample(previous.source)) }
                addAll(current.glucoseHistory)
                current.glucose?.let { add(it.toSample(current.source)) }
            },
            nowEpochMs,
            current.source,
        )

        val earliest = nowEpochMs - WINDOW_MS
        val latest = nowEpochMs + 5 * 60_000L
        val therapy = buildList {
            addAll(previous?.therapyHistory.orEmpty())
            val timestamp = current.glucose?.measuredAtEpochMs ?: current.receivedAtEpochMs
            val sample = TherapyHistorySample(
                measuredAtEpochMs = timestamp,
                totalIob = current.insulin?.totalIob,
                cobGrams = current.carbs?.cobGrams,
                basalUnitsPerHour = current.basal?.tempAbsoluteUnitsPerHour
                    ?: current.basal?.currentUnitsPerHour,
                baseBasalUnitsPerHour = current.basal?.currentUnitsPerHour,
                tempBasalUnitsPerHour = current.basal?.tempAbsoluteUnitsPerHour,
            )
            if (sample.totalIob != null || sample.cobGrams != null || sample.basalUnitsPerHour != null) add(sample)
            val loop = current.loop
            loop?.smbUnits?.takeIf { it.isFinite() && it > 0.0 }?.let { units ->
                add(
                    TherapyHistorySample(
                        measuredAtEpochMs = loop.smbAtEpochMs
                            ?: loop.enactedAtEpochMs
                            ?: current.receivedAtEpochMs,
                        smbUnits = units,
                    ),
                )
            }
        }.filter { it.measuredAtEpochMs in earliest..latest }
            .groupBy { it.measuredAtEpochMs }
            .map { (timestamp, samples) -> samples.reduce { first, second -> first.merge(second, timestamp) } }
            .sortedBy { it.measuredAtEpochMs }
            .takeLast(MAX_POINTS)
            .withEstimatedInsulinActivity()

        return PersistentPredictionCache.merge(
            previous = previous,
            incoming =
                current.copy(
                    glucoseHistory = glucose,
                    therapyHistory = therapy,
                    targetHistory = mergeTargetHistory(previous?.targetHistory.orEmpty(), current.targetHistory, nowEpochMs),
                ),
            nowEpochMs = nowEpochMs,
        )
    }

    fun mergeExternalHistory(
        current: TherapyDisplayState,
        external: List<GlucoseSample>,
        nowEpochMs: Long,
    ): TherapyDisplayState = current.copy(
        glucoseHistory = mergeGlucose(
            buildList {
                addAll(current.glucoseHistory)
                current.glucose?.let { add(it.toSample(current.source)) }
                addAll(external)
            },
            nowEpochMs,
            current.source,
        ),
    )

    private fun mergeGlucose(
        values: List<GlucoseSample>,
        nowEpochMs: Long,
        preferredSource: DataSourceId,
    ): List<GlucoseSample> =
        CanonicalCgmHistory.merge(
            samples = values,
            nowEpochMs = nowEpochMs,
            preferredSource = preferredSource,
            windowMs = WINDOW_MS,
            maxPoints = MAX_POINTS,
        )

    private fun mergeTargetHistory(
        previous: List<TargetSample>,
        incoming: List<TargetSample>,
        nowEpochMs: Long,
    ): List<TargetSample> {
        val earliest = nowEpochMs - WINDOW_MS
        val latest = nowEpochMs + 5 * 60_000L
        val normalized =
            (previous + incoming)
                .asSequence()
                .filter {
                    it.valueMgDl.isFinite() &&
                        it.valueMgDl in 20.0..1_000.0 &&
                        it.startedAtEpochMs <= latest &&
                        it.endsAtEpochMs >= earliest
                }
                .map {
                    it.copy(
                        startedAtEpochMs = it.startedAtEpochMs.coerceAtLeast(earliest),
                        endsAtEpochMs = it.endsAtEpochMs.coerceIn(it.startedAtEpochMs, latest),
                    )
                }
                .sortedBy(TargetSample::startedAtEpochMs)
                .toList()

        val result = mutableListOf<TargetSample>()
        normalized.forEach { sample ->
            val prior = result.lastOrNull()
            if (prior == null) {
                result += sample
                return@forEach
            }

            if (
                prior.valueMgDl == sample.valueMgDl &&
                prior.temporary == sample.temporary &&
                sample.startedAtEpochMs - prior.endsAtEpochMs <= 15L * 60_000L
            ) {
                result[result.lastIndex] =
                    prior.copy(endsAtEpochMs = maxOf(prior.endsAtEpochMs, sample.endsAtEpochMs, sample.startedAtEpochMs))
            } else {
                if (!prior.temporary && prior.endsAtEpochMs < sample.startedAtEpochMs) {
                    result[result.lastIndex] = prior.copy(endsAtEpochMs = sample.startedAtEpochMs)
                }
                result += sample
            }
        }
        return result.takeLast(MAX_POINTS)
    }

    private fun GlucoseState.toSample(fallbackSource: DataSourceId): GlucoseSample =
        GlucoseSample(
            valueMgDl = valueMgDl,
            measuredAtEpochMs = measuredAtEpochMs,
            source = fallbackSource,
            sensorId = sensorId,
            sessionId = sessionId,
            sequenceNumber = sequenceNumber,
            receivedAtEpochMs = receivedAtEpochMs,
            quality = quality,
        )

    private fun TherapyHistorySample.merge(other: TherapyHistorySample, timestamp: Long) =
        TherapyHistorySample(
            measuredAtEpochMs = timestamp,
            totalIob = other.totalIob ?: totalIob,
            cobGrams = other.cobGrams ?: cobGrams,
            basalUnitsPerHour = other.basalUnitsPerHour ?: basalUnitsPerHour,
            baseBasalUnitsPerHour = other.baseBasalUnitsPerHour ?: baseBasalUnitsPerHour,
            tempBasalUnitsPerHour = other.tempBasalUnitsPerHour ?: tempBasalUnitsPerHour,
            insulinActivityUnitsPerMinute = other.insulinActivityUnitsPerMinute ?: insulinActivityUnitsPerMinute,
            smbUnits = other.smbUnits ?: smbUnits,
        )

    private fun List<TherapyHistorySample>.withEstimatedInsulinActivity(): List<TherapyHistorySample> {
        var previousIobSample: TherapyHistorySample? = null
        return map { sample ->
            if (sample.totalIob == null) return@map sample
            if (sample.insulinActivityUnitsPerMinute != null) {
                previousIobSample = sample
                return@map sample
            }
            val previous = previousIobSample.also { previousIobSample = sample } ?: return@map sample
            val minutes = (sample.measuredAtEpochMs - previous.measuredAtEpochMs) / 60_000.0
            val priorIob = previous.totalIob
            val currentIob = sample.totalIob
            val decay = if (priorIob != null && currentIob != null && minutes in 2.0..15.0) {
                (priorIob - currentIob).coerceIn(0.0, 1.5) / minutes
            } else {
                null
            }
            sample.copy(insulinActivityUnitsPerMinute = decay?.takeIf { it > 0.0001 })
        }
    }
}
