package app.aapswear.datasource.dexcom

import android.os.Bundle
import app.aapswear.model.CanonicalCgmHistory
import app.aapswear.model.CanonicalDataField
import app.aapswear.model.CanonicalTrendPolicy
import app.aapswear.model.DataCapability
import app.aapswear.model.DataSourceId
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
import app.aapswear.model.TrendRateProfile
import app.aapswear.model.ValueProvenance

/** Public local-broadcast contract inspected in the supplied patched G7 APK. */
object DexcomBroadcastAdapter {
    const val ACTION = "com.dexcom.cgm.EXTERNAL_BROADCAST"
    const val PERMISSION = "com.dexcom.cgm.EXTERNAL_PERMISSION"
    const val PACKAGE = "com.dexcom.g7"

    fun parse(extras: Bundle, nowEpochMs: Long): TherapyDisplayState? =
        runCatching {
            if (extras.getString("sensorType") != "G7" || extras.getString("packageName") != PACKAGE) return null
            val insertion = extras.getLong("sensorInsertionTime", 0L).takeIf { it > 0L }?.let { Math.multiplyExact(it, 1000L) }
            if (insertion != null && insertion > nowEpochMs + 300_000L) return null
            val values = extras.getBundle("glucoseValues") ?: return null
            if (values.size() > 500) return null
            val readings =
                values.keySet().mapNotNull { key ->
                    runCatching {
                        val record = values.getBundle(key) ?: return@runCatching null
                        DexcomReading(record.getInt("glucoseValue").toDouble(), Math.multiplyExact(record.getLong("timestamp"), 1000L), dexcomTrend(record.getString("trendArrow")))
                    }.getOrNull()
                }
            dexcomBatch(readings.filter { insertion == null || it.timestamp >= insertion }, DataSourceId.DEXCOM_G7_APP, "DEXCOM_G7_BROADCAST_V1", nowEpochMs, insertion?.let { "g7-app:$it" })
        }.getOrNull()
}

internal data class DexcomReading(
    val value: Double,
    val timestamp: Long,
    val trend: Trend,
)

internal fun dexcomTrend(raw: String?): Trend =
    when (raw?.replace("_", "")?.replace(" ", "")?.lowercase()) {
        "doubleup", "risingquickly" -> Trend.DOUBLE_UP
        "singleup", "rising" -> Trend.SINGLE_UP
        "fortyfiveup", "risingslightly" -> Trend.FORTY_FIVE_UP
        "flat", "steady" -> Trend.FLAT
        "fortyfivedown", "fallingslightly" -> Trend.FORTY_FIVE_DOWN
        "singledown", "falling" -> Trend.SINGLE_DOWN
        "doubledown", "fallingquickly" -> Trend.DOUBLE_DOWN
        else -> Trend.UNKNOWN
    }

internal fun dexcomBatch(readings: List<DexcomReading>, source: DataSourceId, contract: String, now: Long, session: String? = null): TherapyDisplayState? {
    val valid = readings.filter { it.value.isFinite() && it.value in 20.0..400.0 && it.timestamp in (now - CanonicalCgmHistory.DEFAULT_WINDOW_MS)..(now + 300_000L) }
    val unique = mutableListOf<DexcomReading>()
    valid.sortedByDescending { it.timestamp }.forEach { candidate ->
        if (unique.none { it.value == candidate.value && kotlin.math.abs(it.timestamp - candidate.timestamp) <= 5_000L }) unique += candidate
    }
    val history = CanonicalCgmHistory.merge(unique.map { GlucoseSample(it.value, it.timestamp, source, sessionId = session, receivedAtEpochMs = now) }, now, source, maxPoints = 2000)
    val latest = history.lastOrNull() ?: return null
    val derived = CanonicalTrendPolicy.derive(latest, history, TrendRateProfile.SUGARLICIOUS)
    val trend = derived?.trend ?: valid.lastOrNull { it.timestamp == latest.measuredAtEpochMs }?.trend ?: Trend.UNKNOWN
    val origin =
        if (derived != null) {
            ValueProvenance.DERIVED
        } else if (trend != Trend.UNKNOWN) {
            ValueProvenance.SOURCE
        } else {
            ValueProvenance.UNAVAILABLE
        }
    return TherapyDisplayState(
        source = source,
        sourceContract = contract,
        receivedAtEpochMs = now,
        glucose =
            GlucoseState(
                latest.valueMgDl,
                GlucoseUnit.MG_DL,
                trend,
                latest.measuredAtEpochMs,
                deltaMgDl = derived?.deltaMgDl,
                source = source,
                sessionId = session,
                receivedAtEpochMs = now,
                trendRateMgDlPerMinute = derived?.rateMgDlPerMinute,
                trendOrigin = origin,
            ),
        glucoseHistory = history,
        capabilities = setOf(DataCapability.GLUCOSE) + (if (trend != Trend.UNKNOWN) setOf(DataCapability.TREND) else emptySet()) + (if (derived != null) setOf(DataCapability.DELTA) else emptySet()),
        fieldProvenance = mapOf(CanonicalDataField.GLUCOSE to ValueProvenance.SOURCE, CanonicalDataField.TREND to origin),
        fieldSources = mapOf(CanonicalDataField.GLUCOSE to source, CanonicalDataField.TREND to source) + if (derived != null) mapOf(CanonicalDataField.DELTA to source) else emptyMap(),
    )
}
