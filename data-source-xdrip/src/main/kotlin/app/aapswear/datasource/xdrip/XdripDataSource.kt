package app.aapswear.datasource.xdrip

import app.aapswear.datasource.HealthDataSource
import app.aapswear.model.DataCapability
import app.aapswear.model.DataSourceId
import app.aapswear.model.FreshnessPolicy
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
import java.util.concurrent.atomic.AtomicReference

object XdripContract {
    const val ACTION = "com.eveningoutpost.dexdrip.BgEstimate"
    const val PERMISSION = "com.eveningoutpost.dexdrip.permissions.RECEIVE_BG_ESTIMATE"
    const val EXTRA_BG = "com.eveningoutpost.dexdrip.Extras.BgEstimate"
    const val EXTRA_SLOPE = "com.eveningoutpost.dexdrip.Extras.BgSlope"
    const val EXTRA_SLOPE_NAME = "com.eveningoutpost.dexdrip.Extras.BgSlopeName"
    const val EXTRA_TIME = "com.eveningoutpost.dexdrip.Extras.Time"
    const val EXTRA_UNITS = "com.eveningoutpost.dexdrip.Extras.Display.Units"
    const val EXTRA_SOURCE = "com.eveningoutpost.dexdrip.Extras.SourceDesc"
    const val EXTRA_VERSION = "com.eveningoutpost.dexdrip.Extras.Version"
}

class XdripPayloadValidator {
    fun isValid(values: Map<String, Any?>, receivedAtEpochMs: Long): Boolean {
        val glucose = values.number(XdripContract.EXTRA_BG) ?: return false
        val timestamp = values.number(XdripContract.EXTRA_TIME)?.toLong() ?: return false
        return glucose in 20.0..1000.0 && timestamp > 0L &&
            timestamp <= receivedAtEpochMs + FreshnessPolicy.FUTURE_TOLERANCE_MS
    }
}

class XdripPayloadAdapter(
    private val validator: XdripPayloadValidator = XdripPayloadValidator(),
) {
    fun parse(values: Map<String, Any?>, receivedAtEpochMs: Long): TherapyDisplayState? {
        if (!validator.isValid(values, receivedAtEpochMs)) return null
        val glucose = values.number(XdripContract.EXTRA_BG) ?: return null
        val measuredAt = values.number(XdripContract.EXTRA_TIME)?.toLong() ?: return null
        val unit = values[XdripContract.EXTRA_UNITS]?.toString()?.lowercase().let {
            if (it?.contains("mmol") == true) GlucoseUnit.MMOL_L else GlucoseUnit.MG_DL
        }
        return TherapyDisplayState(
            source = DataSourceId.XDRIP_PLUS,
            sourceVersion = values[XdripContract.EXTRA_VERSION]?.toString(),
            sourceContract = "XDRIP_BROADCAST_V1",
            receivedAtEpochMs = receivedAtEpochMs,
            glucose = GlucoseState(
                valueMgDl = glucose,
                displayUnit = unit,
                trend = mapTrend(values[XdripContract.EXTRA_SLOPE_NAME]?.toString()),
                measuredAtEpochMs = measuredAt,
                source = DataSourceId.XDRIP_PLUS,
                receivedAtEpochMs = receivedAtEpochMs,
            ),
            capabilities = setOf(DataCapability.GLUCOSE, DataCapability.TREND),
        )
    }

    private fun mapTrend(raw: String?): Trend = when (raw?.trim()?.lowercase()) {
        "doubleup", "double up" -> Trend.DOUBLE_UP
        "singleup", "single up" -> Trend.SINGLE_UP
        "fortyfiveup", "forty five up" -> Trend.FORTY_FIVE_UP
        "flat" -> Trend.FLAT
        "fortyfivedown", "forty five down" -> Trend.FORTY_FIVE_DOWN
        "singledown", "single down" -> Trend.SINGLE_DOWN
        "doubledown", "double down" -> Trend.DOUBLE_DOWN
        else -> Trend.UNKNOWN
    }
}

class XdripDataSource(
    private val adapter: XdripPayloadAdapter = XdripPayloadAdapter(),
) : HealthDataSource {
    private val current = AtomicReference<TherapyDisplayState?>()

    fun accept(values: Map<String, Any?>, receivedAtEpochMs: Long): TherapyDisplayState? =
        adapter.parse(values, receivedAtEpochMs)?.also(current::set)

    override fun latest(): TherapyDisplayState? = current.get()
}

private fun Map<String, Any?>.number(key: String): Double? = get(key)?.let {
    when (it) {
        is Number -> it.toDouble()
        is String -> it.toDoubleOrNull()
        else -> null
    }
}?.takeIf(Double::isFinite)
