package app.aapswear.complications

import android.content.Context
import android.net.Uri
import app.aapswear.model.CgmCanonicalSource
import app.aapswear.model.CgmQuality
import app.aapswear.model.CgmResolverMemory
import app.aapswear.model.CgmSourceCandidate
import app.aapswear.model.CgmSourceMode
import app.aapswear.model.CgmSourcePolicy
import app.aapswear.model.CgmSourceState
import app.aapswear.model.CanonicalCgmHistory
import app.aapswear.model.CanonicalCgmSourceResolver
import app.aapswear.model.DataCapability
import app.aapswear.model.DataSourceId
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
import app.aapswear.protocol.WatchDataSource

/**
 * Canonical Wear-side CGM resolver.
 *
 * The standalone G7 collector is an independent input. This resolver only chooses which already
 * available CGM stream is canonical. It never starts, stops, scans, pairs, bonds, or authenticates
 * the G7 collector. In AUTOMATIC mode Mobile is primary, Watch Direct is fallback, Mobile timeout
 * is 15 minutes, and returning Mobile data must pass recovery hysteresis.
 */
object G7LocalReadingResolver {
    private val readingsUri = Uri.parse("content://app.aapswear.g7watch.readings/readings")
    private const val HISTORY_WINDOW_MS = 24 * 60 * 60_000L
    private const val MAX_HISTORY_POINTS = 300
    private const val PREFS = "canonical_cgm_resolver"
    private const val KEY_STATE = "state"
    private const val KEY_RECOVERY_COUNT = "recovery_count"
    private const val KEY_RECOVERY_MOBILE_TIMESTAMP = "recovery_mobile_timestamp"

    val defaultPolicy = CgmSourcePolicy()

    @Synchronized
    fun resolve(
        context: Context,
        fallback: TherapyDisplayState?,
        nowEpochMs: Long = System.currentTimeMillis(),
        dataSource: WatchDataSource? = null,
    ): TherapyDisplayState? {
        val selectedSource =
            dataSource ?: runCatching {
                WatchDataSource.valueOf(
                    context
                        .getSharedPreferences("watch_display", Context.MODE_PRIVATE)
                        .getString("data_source", WatchDataSource.AUTOMATIC.name)!!,
                )
            }.getOrDefault(WatchDataSource.AUTOMATIC)

        val directRows = readDirectRows(context)
        val latestDirectEvent = directRows.maxByOrNull(LocalReading::measuredAt)
        val latestDirect = latestDirectEvent?.takeIf { it.quality == CgmQuality.VALID }
        val mobileCandidate = fallback?.glucose?.takeIf { it.quality == CgmQuality.VALID }?.let { glucose ->
            CgmSourceCandidate(
                source = CgmCanonicalSource.MOBILE_AAPS,
                glucoseMgDl = glucose.valueMgDl,
                measuredAtEpochMs = glucose.measuredAtEpochMs,
                receivedAtEpochMs = glucose.receivedAtEpochMs ?: fallback.receivedAtEpochMs,
                sensorId = glucose.sensorId,
                sessionId = glucose.sessionId,
                sequenceNumber = glucose.sequenceNumber,
            )
        }
        val watchCandidate = latestDirect?.toCandidate()

        if (fallback == null && latestDirectEvent == null) return null

        val previous = readMemory(context)
        val resolution =
            CanonicalCgmSourceResolver.resolve(
                mobile = mobileCandidate,
                watch = watchCandidate,
                nowEpochMs = nowEpochMs,
                previous = previous,
                mode = selectedSource.toResolverMode(),
                policy = defaultPolicy,
            )
        writeMemory(context, resolution.memory)

        val directSensorError =
            latestDirectEvent
                ?.takeIf {
                    selectedSource != WatchDataSource.PHONE &&
                        it.quality == CgmQuality.SENSOR_ERROR &&
                        it.isRecent(nowEpochMs, defaultPolicy)
                }
                ?.toGlucoseState()
        val fallbackSensorError =
            fallback
                ?.glucose
                ?.takeIf { it.quality == CgmQuality.SENSOR_ERROR }
        val chosenGlucose =
            when (resolution.canonicalSource) {
                CgmCanonicalSource.MOBILE_AAPS -> fallback?.glucose
                CgmCanonicalSource.WATCH_G7_DIRECT -> latestDirect?.toGlucoseState()
                CgmCanonicalSource.NONE -> directSensorError ?: fallbackSensorError
            }

        val chosenSource =
            when (resolution.canonicalSource) {
                CgmCanonicalSource.MOBILE_AAPS -> fallback?.source ?: DataSourceId.ANDROID_APS
                CgmCanonicalSource.WATCH_G7_DIRECT -> DataSourceId.DEXCOM_G7_WATCH
                CgmCanonicalSource.NONE -> chosenGlucose?.source ?: fallback?.source ?: DataSourceId.OTHER
            }

        val sourceVersion =
            when (resolution.canonicalSource) {
                CgmCanonicalSource.WATCH_G7_DIRECT -> "G7 Watch Collector"
                CgmCanonicalSource.MOBILE_AAPS -> fallback?.sourceVersion
                CgmCanonicalSource.NONE ->
                    if (directSensorError != null) "G7 Watch Collector" else fallback?.sourceVersion
            }

        val capabilities =
            if (chosenGlucose?.quality == CgmQuality.VALID) {
                fallback?.capabilities.orEmpty() +
                    setOf(
                        DataCapability.GLUCOSE,
                        DataCapability.TREND,
                        DataCapability.DELTA,
                    )
            } else {
                fallback?.capabilities.orEmpty() -
                    setOf(
                        DataCapability.GLUCOSE,
                        DataCapability.TREND,
                        DataCapability.DELTA,
                        DataCapability.AVERAGE_DELTA,
                    )
            }

        val mergedHistory =
            mergeHistory(
                phone = fallback?.glucoseHistory.orEmpty(),
                direct = directRows,
                nowEpochMs = nowEpochMs,
                preferredSource =
                    if (selectedSource == WatchDataSource.DEXCOM_G7_WATCH) {
                        DataSourceId.DEXCOM_G7_WATCH
                    } else {
                        fallback?.source ?: chosenSource
                    },
            )

        return TherapyDisplayState(
            source = chosenSource,
            sourceVersion = sourceVersion,
            sourceContract =
                "CANONICAL_CGM_V2:${resolution.state.name}:${resolution.reason}",
            receivedAtEpochMs =
                resolution.reading?.receivedAtEpochMs
                    ?: chosenGlucose?.receivedAtEpochMs
                    ?: fallback?.receivedAtEpochMs
                    ?: nowEpochMs,
            glucose = chosenGlucose,
            glucoseHistory = mergedHistory,
            glucosePredictions = fallback?.glucosePredictions.orEmpty(),
            therapyHistory = fallback?.therapyHistory.orEmpty(),
            targetHistory = fallback?.targetHistory.orEmpty(),
            insulin = fallback?.insulin,
            carbs = fallback?.carbs,
            basal = fallback?.basal,
            target = fallback?.target,
            loop = fallback?.loop,
            pump = fallback?.pump,
            device = fallback?.device,
            profile = fallback?.profile,
            capabilities = capabilities,
        )
    }

    fun sourceState(state: TherapyDisplayState?): CgmSourceState? =
        state
            ?.sourceContract
            ?.takeIf { it.startsWith("CANONICAL_CGM_V2:") }
            ?.substringAfter("CANONICAL_CGM_V2:")
            ?.substringBefore(':')
            ?.let { runCatching { CgmSourceState.valueOf(it) }.getOrNull() }

    private fun readDirectRows(context: Context): List<LocalReading> =
        runCatching {
            context.contentResolver.query(readingsUri, null, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val quality =
                            when (cursor.getString(cursor.getColumnIndexOrThrow("status"))) {
                                "VALID" -> CgmQuality.VALID
                                "SENSOR_ERROR" -> CgmQuality.SENSOR_ERROR
                                else -> continue
                            }
                        val value = cursor.getDouble(cursor.getColumnIndexOrThrow("glucose"))
                        if (quality == CgmQuality.VALID && (!value.isFinite() || value !in 20.0..1_000.0)) continue
                        add(
                            LocalReading(
                                sensorId = cursor.getString(cursor.getColumnIndexOrThrow("sensor_id")),
                                sessionId = cursor.getString(cursor.getColumnIndexOrThrow("session_id")),
                                sequenceNumber =
                                    cursor.getColumnIndexOrThrow("sequence_number").let {
                                        if (cursor.isNull(it)) null else cursor.getLong(it)
                                    },
                                value = value,
                                measuredAt = cursor.getLong(cursor.getColumnIndexOrThrow("measured_at")),
                                receivedAt = cursor.getLong(cursor.getColumnIndexOrThrow("received_at")),
                                delta =
                                    cursor.getColumnIndexOrThrow("delta").let {
                                        if (cursor.isNull(it)) null else cursor.getDouble(it)
                                    },
                                trend =
                                    runCatching {
                                        Trend.valueOf(
                                            cursor.getString(cursor.getColumnIndexOrThrow("trend")),
                                        )
                                    }.getOrDefault(Trend.UNKNOWN),
                                quality = quality,
                            ),
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())

    private fun mergeHistory(
        phone: List<GlucoseSample>,
        direct: List<LocalReading>,
        nowEpochMs: Long,
        preferredSource: DataSourceId,
    ): List<GlucoseSample> {
        val directSamples =
            direct.map {
                GlucoseSample(
                    valueMgDl = it.value,
                    measuredAtEpochMs = it.measuredAt,
                    source = DataSourceId.DEXCOM_G7_WATCH,
                    sensorId = it.sensorId,
                    sessionId = it.sessionId,
                    sequenceNumber = it.sequenceNumber,
                    receivedAtEpochMs = it.receivedAt,
                    quality = it.quality,
                )
            }
        return CanonicalCgmHistory.merge(
            samples = directSamples + phone,
            nowEpochMs = nowEpochMs,
            preferredSource = preferredSource,
            windowMs = HISTORY_WINDOW_MS,
            futureToleranceMs = defaultPolicy.futureToleranceMs,
            maxPoints = MAX_HISTORY_POINTS,
        )
    }

    private fun readMemory(context: Context): CgmResolverMemory {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state =
            prefs
                .getString(KEY_STATE, CgmSourceState.NO_SOURCE.name)
                ?.let { runCatching { CgmSourceState.valueOf(it) }.getOrNull() }
                ?: CgmSourceState.NO_SOURCE
        val lastRecoveryTimestamp =
            prefs
                .getLong(KEY_RECOVERY_MOBILE_TIMESTAMP, Long.MIN_VALUE)
                .takeUnless { it == Long.MIN_VALUE }
        return CgmResolverMemory(
            state = state,
            recoveryReadingCount = prefs.getInt(KEY_RECOVERY_COUNT, 0),
            lastRecoveryMobileTimestampEpochMs = lastRecoveryTimestamp,
        )
    }

    private fun writeMemory(context: Context, memory: CgmResolverMemory) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATE, memory.state.name)
            .putInt(KEY_RECOVERY_COUNT, memory.recoveryReadingCount)
            .apply {
                val timestamp = memory.lastRecoveryMobileTimestampEpochMs
                if (timestamp == null) {
                    remove(KEY_RECOVERY_MOBILE_TIMESTAMP)
                } else {
                    putLong(KEY_RECOVERY_MOBILE_TIMESTAMP, timestamp)
                }
            }.apply()
    }

    private fun WatchDataSource.toResolverMode(): CgmSourceMode =
        when (this) {
            WatchDataSource.AUTOMATIC -> CgmSourceMode.AUTOMATIC
            WatchDataSource.PHONE -> CgmSourceMode.MOBILE_ONLY
            WatchDataSource.DEXCOM_G7_WATCH -> CgmSourceMode.WATCH_ONLY
        }

    private fun LocalReading.toCandidate(): CgmSourceCandidate =
        CgmSourceCandidate(
            source = CgmCanonicalSource.WATCH_G7_DIRECT,
            glucoseMgDl = value,
            measuredAtEpochMs = measuredAt,
            receivedAtEpochMs = receivedAt,
            sensorId = sensorId,
            sessionId = sessionId,
            sequenceNumber = sequenceNumber,
        )

    private fun LocalReading.isRecent(nowEpochMs: Long, policy: CgmSourcePolicy): Boolean =
        measuredAt <= nowEpochMs + policy.futureToleranceMs &&
            receivedAt >= measuredAt - policy.futureToleranceMs &&
            receivedAt <= nowEpochMs + policy.futureToleranceMs &&
            (nowEpochMs - measuredAt).coerceAtLeast(0L) <= policy.watchFreshAfterMs

    private fun LocalReading.toGlucoseState(): GlucoseState =
        GlucoseState(
            valueMgDl = value,
            displayUnit = GlucoseUnit.MG_DL,
            trend = trend,
            measuredAtEpochMs = measuredAt,
            deltaMgDl = delta,
            source = DataSourceId.DEXCOM_G7_WATCH,
            sensorId = sensorId,
            sessionId = sessionId,
            sequenceNumber = sequenceNumber,
            receivedAtEpochMs = receivedAt,
            quality = quality,
        )

    private data class LocalReading(
        val sensorId: String,
        val sessionId: String,
        val sequenceNumber: Long?,
        val value: Double,
        val measuredAt: Long,
        val receivedAt: Long,
        val delta: Double?,
        val trend: Trend,
        val quality: CgmQuality,
    )
}
