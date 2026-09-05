package app.aapswear.mobile

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.model.CgmCanonicalSource
import app.aapswear.model.CgmQuality
import app.aapswear.model.CgmResolverMemory
import app.aapswear.model.CgmSourceCandidate
import app.aapswear.model.CgmSourceMode
import app.aapswear.model.CgmSourceState
import app.aapswear.model.CanonicalCgmHistory
import app.aapswear.model.CanonicalCgmSourceResolver
import app.aapswear.model.DataCapability
import app.aapswear.model.DataSourceId
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.Trend
import app.aapswear.model.TherapyDisplayState
import app.aapswear.storage.PhoneTherapyStateStore
import app.aapswear.storage.TherapyStateStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Direct G7 Watch readings are local-Watch CGM data. They are deliberately not backfilled into
 * Sugarlicious Mobile CGM history. The DataStore delegate is retained only so existing installs can
 * delete the former backfill payload once during migration.
 */
private val Context.mobileG7HistoryDataStore by preferencesDataStore("mobile_g7_backfill")

internal class MobileG7BackfillStore(private val context: Context) {
    private val key = stringPreferencesKey("readings_v2")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val serializer = ListSerializer(CgmReading.serializer())

    suspend fun snapshot(): List<CgmReading> = decode(context.mobileG7HistoryDataStore.data.first()[key])

    suspend fun merge(
        incoming: List<CgmReading>,
        nowEpochMs: Long,
    ): Set<String> {
        val accepted = linkedSetOf<String>()
        context.mobileG7HistoryDataStore.edit { preferences ->
            val current = decode(preferences[key]).toMutableList()
            incoming.forEach { candidate ->
                if (!candidate.isValidWatchReading(nowEpochMs)) return@forEach
                accepted += candidate.id
                if (current.none { it.id == candidate.id || it.sameIdentity(candidate) }) current += candidate
            }
            val normalized = current.asSequence()
                .filter { it.isValidWatchReading(nowEpochMs) }
                .filter { nowEpochMs - it.timestampEpochMs <= RETENTION_MS }
                .distinctBy(CgmReading::id)
                .sortedBy(CgmReading::timestampEpochMs)
                .toList().takeLast(MAX_READINGS)
            preferences[key] = json.encodeToString(serializer, normalized)
        }
        return accepted
    }

    suspend fun clear() {
        context.mobileG7HistoryDataStore.edit { it.clear() }
    }

    private fun decode(raw: String?): List<CgmReading> =
        raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    private fun CgmReading.isValidWatchReading(now: Long) =
        source == DataSourceId.DEXCOM_G7_WATCH && status == CgmReadingStatus.VALID &&
            id.isNotBlank() && sensorId.isNotBlank() && sessionId.isNotBlank() &&
            glucoseMgDl.isFinite() && glucoseMgDl in 20.0..1_000.0 &&
            timestampEpochMs <= now + FUTURE_TOLERANCE_MS &&
            receivedAtEpochMs >= timestampEpochMs - FUTURE_TOLERANCE_MS &&
            receivedAtEpochMs <= now + FUTURE_TOLERANCE_MS

    private fun CgmReading.sameIdentity(other: CgmReading) =
        sensorId == other.sensorId && sessionId == other.sessionId &&
            sequenceNumber != null && sequenceNumber == other.sequenceNumber

    private companion object {
        const val MAX_READINGS = 600
        const val RETENTION_MS = 36L * 60L * 60_000L
        const val FUTURE_TOLERANCE_MS = 5L * 60_000L
    }
}

internal object MobileWatchCgmMigration {
    private const val PREFS = "mobile_watch_cgm_migration"
    private const val KEY_VERSION = "version"
    private const val VERSION = 1

    suspend fun runOnce(context: Context): Boolean {
        context.applicationContext
        return false
    }
}

internal object MobileCanonicalCgmResolver {
    private const val MEMORY_PREFS = "mobile_canonical_cgm_resolver"
    suspend fun resolve(
        context: Context,
        phoneState: TherapyDisplayState?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TherapyDisplayState? {
        val watchReadings = MobileG7BackfillStore(context).snapshot()
        val latestWatch = watchReadings.lastOrNull { it.status == CgmReadingStatus.VALID }
        if (phoneState == null && latestWatch == null) return null
        val mobile = phoneState?.glucose?.takeIf { it.quality == CgmQuality.VALID }?.let {
            CgmSourceCandidate(CgmCanonicalSource.MOBILE_AAPS, it.valueMgDl, it.measuredAtEpochMs, it.receivedAtEpochMs ?: phoneState.receivedAtEpochMs, it.sensorId, it.sessionId, it.sequenceNumber)
        }
        val watch = latestWatch?.let {
            CgmSourceCandidate(CgmCanonicalSource.WATCH_G7_DIRECT, it.glucoseMgDl, it.timestampEpochMs, it.receivedAtEpochMs, it.sensorId, it.sessionId, it.sequenceNumber)
        }
        val resolution = CanonicalCgmSourceResolver.resolve(mobile, watch, nowEpochMs, readMemory(context), sourceMode(context))
        writeMemory(context, resolution.memory)
        val chosenGlucose = when (resolution.canonicalSource) {
            CgmCanonicalSource.MOBILE_AAPS -> phoneState?.glucose
            CgmCanonicalSource.WATCH_G7_DIRECT -> latestWatch?.toGlucoseState()
            CgmCanonicalSource.NONE -> phoneState?.glucose?.takeIf { it.quality == CgmQuality.SENSOR_ERROR }
        }
        val chosenSource = when (resolution.canonicalSource) {
            CgmCanonicalSource.MOBILE_AAPS -> phoneState?.source ?: DataSourceId.OTHER
            CgmCanonicalSource.WATCH_G7_DIRECT -> DataSourceId.DEXCOM_G7_WATCH
            CgmCanonicalSource.NONE -> phoneState?.source ?: DataSourceId.OTHER
        }
        val capabilities = if (chosenGlucose?.quality == CgmQuality.VALID) {
            phoneState?.capabilities.orEmpty() + setOf(DataCapability.GLUCOSE, DataCapability.TREND, DataCapability.DELTA)
        } else phoneState?.capabilities.orEmpty() - setOf(DataCapability.GLUCOSE, DataCapability.TREND, DataCapability.DELTA, DataCapability.AVERAGE_DELTA)
        val phoneHistory = buildList {
            addAll(phoneState?.glucoseHistory.orEmpty())
            phoneState?.glucose?.let { add(it.toSample(phoneState.source)) }
        }
        val history = CanonicalCgmHistory.merge(phoneHistory + watchReadings.map { it.toSample() }, nowEpochMs, phoneState?.source ?: chosenSource)
        val base = phoneState
        return TherapyDisplayState(
            source = chosenSource,
            sourceVersion = if (resolution.canonicalSource == CgmCanonicalSource.WATCH_G7_DIRECT) "Direct to Watch" else base?.sourceVersion,
            sourceContract = "CANONICAL_CGM_V3:${resolution.state.name}:${resolution.reason}",
            receivedAtEpochMs = resolution.reading?.receivedAtEpochMs ?: base?.receivedAtEpochMs ?: nowEpochMs,
            glucose = chosenGlucose,
            glucoseHistory = history,
            glucosePredictions = base?.glucosePredictions.orEmpty(), therapyHistory = base?.therapyHistory.orEmpty(),
            therapyEvents = base?.therapyEvents.orEmpty(), targetHistory = base?.targetHistory.orEmpty(),
            insulin = base?.insulin, carbs = base?.carbs, basal = base?.basal, target = base?.target,
            loop = base?.loop, pump = base?.pump, device = base?.device, profile = base?.profile,
            capabilities = capabilities,
        )
    }

    private fun sourceMode(context: Context): CgmSourceMode = when (runCatching {
        DataSourcePreference.valueOf(context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE).getString("dataSource", DataSourcePreference.AUTOMATIC.name)!!)
    }.getOrDefault(DataSourcePreference.AUTOMATIC)) {
        DataSourcePreference.DEXCOM_G7_WATCH -> CgmSourceMode.WATCH_ONLY
        DataSourcePreference.ANDROID_APS, DataSourcePreference.XDRIP_PLUS -> CgmSourceMode.MOBILE_ONLY
        DataSourcePreference.AUTOMATIC -> CgmSourceMode.AUTOMATIC
    }

    private fun CgmReading.toGlucoseState() = GlucoseState(glucoseMgDl, GlucoseUnit.MG_DL, trend, timestampEpochMs, deltaMgDl, source = DataSourceId.DEXCOM_G7_WATCH, sensorId = sensorId, sessionId = sessionId, sequenceNumber = sequenceNumber, receivedAtEpochMs = receivedAtEpochMs, quality = status.toQuality())
    private fun CgmReading.toSample() = GlucoseSample(glucoseMgDl, timestampEpochMs, DataSourceId.DEXCOM_G7_WATCH, sensorId, sessionId, sequenceNumber, receivedAtEpochMs, status.toQuality())
    private fun GlucoseState.toSample(sourceId: DataSourceId) = GlucoseSample(valueMgDl, measuredAtEpochMs, sourceId, sensorId, sessionId, sequenceNumber, receivedAtEpochMs, quality)
    private fun CgmReadingStatus.toQuality() = when (this) { CgmReadingStatus.VALID -> CgmQuality.VALID; CgmReadingStatus.SENSOR_ERROR -> CgmQuality.SENSOR_ERROR; CgmReadingStatus.INVALID -> CgmQuality.INVALID }

    private fun readMemory(context: Context): CgmResolverMemory {
        val prefs = context.getSharedPreferences(MEMORY_PREFS, Context.MODE_PRIVATE)
        return CgmResolverMemory(
            prefs.getString("state", CgmSourceState.NO_SOURCE.name)?.let { runCatching { CgmSourceState.valueOf(it) }.getOrNull() } ?: CgmSourceState.NO_SOURCE,
            prefs.getInt("recovery_count", 0),
            prefs.getLong("recovery_timestamp", Long.MIN_VALUE).takeUnless { it == Long.MIN_VALUE },
        )
    }
    private fun writeMemory(context: Context, memory: CgmResolverMemory) {
        context.getSharedPreferences(MEMORY_PREFS, Context.MODE_PRIVATE).edit()
            .putString("state", memory.state.name).putInt("recovery_count", memory.recoveryReadingCount)
            .apply { memory.lastRecoveryMobileTimestampEpochMs?.let { putLong("recovery_timestamp", it) } ?: remove("recovery_timestamp") }.apply()
    }
}

internal object MobileCanonicalStateCoordinator {
    suspend fun savePhoneInput(
        context: Context,
        incoming: TherapyDisplayState,
        nowEpochMs: Long,
    ): Pair<TherapyDisplayState, TherapyDisplayState> {
        require(incoming.source != DataSourceId.DEXCOM_G7_WATCH) {
            "Direct Watch G7 input is not a Mobile CGM source"
        }

        val phoneStore = PhoneTherapyStateStore(context)
        val priorPhone =
            phoneStore.state.first()
                ?: TherapyStateStore(context).state.first()?.asPhoneInputFallback()

        var mergedPhone = DisplayHistoryAccumulator.merge(priorPhone, incoming, nowEpochMs)
            .withNightscoutTreatments(context)
        val glucose = mergedPhone.glucose
        if (glucose != null && glucose.trend == Trend.UNKNOWN) {
            mergedPhone =
                mergedPhone.copy(
                    glucose =
                        glucose.copy(
                            trend =
                                TrendArrowResolver.resolve(
                                    glucose.trend,
                                    mergedPhone.glucoseHistory,
                                    glucose.measuredAtEpochMs,
                                ),
                        ),
                )
        }

        phoneStore.save(mergedPhone)
        val canonical = requireNotNull(MobileCanonicalCgmResolver.resolve(context, mergedPhone, nowEpochMs))
        TherapyStateStore(context).save(canonical)
        return mergedPhone to canonical
    }

    /**
     * Legacy compatibility entry point. It no longer reads Watch backfill; it only republishes a
     * sanitized phone state and may return null when no phone state exists.
     */
    suspend fun refreshFromWatchBackfill(
        context: Context,
        nowEpochMs: Long,
    ): TherapyDisplayState? {
        val phone =
            PhoneTherapyStateStore(context).state.first()
                ?: TherapyStateStore(context).state.first()?.asPhoneInputFallback()
        val canonical = MobileCanonicalCgmResolver.resolve(context, phone, nowEpochMs) ?: return null
        TherapyStateStore(context).save(canonical)
        return canonical
    }

    private fun TherapyDisplayState.asPhoneInputFallback(): TherapyDisplayState? =
        takeUnless { it.source == DataSourceId.DEXCOM_G7_WATCH }
            ?.copy(glucoseHistory = glucoseHistory.filter { it.source != DataSourceId.DEXCOM_G7_WATCH })
}

internal fun TherapyDisplayState.withoutDirectWatchCgm(): TherapyDisplayState {
    val filteredHistory = glucoseHistory.filter { sample -> sample.source != DataSourceId.DEXCOM_G7_WATCH }
    val currentIsWatch =
        source == DataSourceId.DEXCOM_G7_WATCH ||
            glucose?.source == DataSourceId.DEXCOM_G7_WATCH
    val safeGlucose = glucose?.takeUnless { it.source == DataSourceId.DEXCOM_G7_WATCH || source == DataSourceId.DEXCOM_G7_WATCH }
    val safeSource = if (source == DataSourceId.DEXCOM_G7_WATCH) DataSourceId.OTHER else source
    val safeCapabilities =
        if (safeGlucose == null && currentIsWatch) {
            capabilities - setOf(
                DataCapability.GLUCOSE,
                DataCapability.TREND,
                DataCapability.DELTA,
                DataCapability.AVERAGE_DELTA,
            )
        } else {
            capabilities
        }

    return copy(
        source = safeSource,
        sourceVersion = if (currentIsWatch) null else sourceVersion,
        sourceContract = if (currentIsWatch) "MOBILE_PHONE_ONLY:NO_WATCH_CGM" else sourceContract,
        glucose = safeGlucose,
        glucoseHistory = filteredHistory,
        capabilities = safeCapabilities,
    )
}
