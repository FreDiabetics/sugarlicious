package app.aapswear.mobile

import app.aapswear.model.CgmPresentationPolicy
import app.aapswear.model.CgmPresentationStatus
import app.aapswear.model.FreshnessPolicy
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import java.util.Locale
import kotlin.math.roundToInt

internal enum class NotificationMode { STANDARD_CUSTOM, ANDROID_LIVE, SAMSUNG_LIVE }

internal data class NotificationPresentation(
    val glucoseText: String?,
    val glucoseNumeric: Double?,
    val unit: GlucoseUnit?,
    val unitText: String?,
    val trendSymbol: String?,
    val deltaText: String?,
    val ageText: String?,
    val status: CgmPresentationStatus,
    val statusText: String?,
    val therapyText: String?,
    val basalText: String?,
    val sourceText: String,
    val profileText: String?,
    val androidCapsuleText: String?,
    val compactPrimaryText: String?,
    val drawerPrimaryText: String,
    val revision: String,
)

internal fun notificationMode(
    liveRequested: Boolean,
    sdkInt: Int,
    manufacturer: String,
): NotificationMode =
    when {
        !liveRequested -> NotificationMode.STANDARD_CUSTOM
        manufacturer.equals("samsung", ignoreCase = true) -> NotificationMode.SAMSUNG_LIVE
        sdkInt >= 36 -> NotificationMode.ANDROID_LIVE
        else -> NotificationMode.STANDARD_CUSTOM
    }

internal fun notificationPresentation(
    state: TherapyDisplayState?,
    nowEpochMs: Long,
    selectedUnit: GlucoseUnit? = state?.glucose?.displayUnit,
): NotificationPresentation {
    val status = TherapyDisplayFormatter.presentationStatus(state, nowEpochMs)
    val glucose = state?.glucose
    val displayable =
        glucose != null &&
            TherapyDisplayFormatter.isGlucoseKnown(state) &&
            status in setOf(CgmPresentationStatus.CURRENT, CgmPresentationStatus.AGING)
    val unit = selectedUnit ?: glucose?.displayUnit
    val glucoseText = if (displayable && unit != null) formatGlucose(glucose.valueMgDl, unit) else null
    val glucoseNumeric =
        if (displayable && unit != null) {
            if (unit == GlucoseUnit.MMOL_L) glucose.valueMgDl / 18.0 else glucose.valueMgDl
        } else {
            null
        }
    val trendSymbol =
        if (displayable) {
            TherapyDisplayFormatter.trendArrow(glucose.trend).ifBlank { null }
        } else {
            null
        }
    val deltaText =
        if (displayable && unit != null) {
            TherapyDisplayFormatter.signedDelta(glucose.deltaMgDl, unit).replace('-', '−').ifBlank { null }
        } else {
            null
        }
    val compactPrimary = joinVisible(glucoseText, trendSymbol, deltaText)
    val androidCapsule = joinVisible(glucoseText, trendSymbol)
    val therapyText =
        joinSeparated(
            state?.insulin?.totalIob?.takeIf(Double::isFinite)?.let { "IOB ${formatDecimal(it, 1)} U" },
            state?.carbs?.cobGrams?.takeIf(Double::isFinite)?.let { "COB ${formatDecimal(it, 1)} g" },
        )
    val basalText =
        state?.basal?.currentUnitsPerHour?.takeIf(Double::isFinite)?.let {
            "Basal ${formatDecimal(it, 2)} U/h"
        }
    val statusText =
        when (status) {
            CgmPresentationStatus.CURRENT -> null
            CgmPresentationStatus.AGING -> "Verzögert"
            CgmPresentationStatus.STALE -> "Veraltet"
            CgmPresentationStatus.SIGNAL_LOSS -> "Signalverlust"
            CgmPresentationStatus.SENSOR_ERROR -> "Sensorfehler"
            CgmPresentationStatus.NO_SOURCE -> "Keine Quelle"
        }
    val drawerPrimary =
        if (compactPrimary != null) {
            joinSeparated(compactPrimary, basalText).orEmpty()
        } else {
            statusText ?: "Keine Quelle"
        }
    val sourceText = TherapyDisplayFormatter.sourceName(state?.source)
    val profileText = state?.profile?.name?.takeIf(String::isNotBlank)
    val measuredAt = glucose?.measuredAtEpochMs
    val ageText = measuredAt?.let { TherapyDisplayFormatter.ageMinutes(it, nowEpochMs) }
    val revision =
        listOf(
            measuredAt,
            sourceText,
            glucoseText,
            glucoseNumeric,
            unit,
            trendSymbol,
            deltaText,
            status,
            glucose?.quality,
            therapyText,
            basalText,
            profileText,
            drawerPrimary,
        ).joinToString("\u001f") { it?.toString().orEmpty() }

    return NotificationPresentation(
        glucoseText = glucoseText,
        glucoseNumeric = glucoseNumeric,
        unit = unit,
        unitText = unit?.let { if (it == GlucoseUnit.MMOL_L) "mmol/L" else "mg/dL" },
        trendSymbol = trendSymbol,
        deltaText = deltaText,
        ageText = ageText,
        status = status,
        statusText = statusText,
        therapyText = therapyText,
        basalText = basalText,
        sourceText = sourceText,
        profileText = profileText,
        androidCapsuleText = androidCapsule,
        compactPrimaryText = compactPrimary,
        drawerPrimaryText = drawerPrimary,
        revision = revision,
    )
}

internal fun nextNotificationFreshnessBoundary(
    state: TherapyDisplayState?,
    nowEpochMs: Long,
): Long? {
    val measuredAt = state?.glucose?.measuredAtEpochMs ?: return null
    if (!TherapyDisplayFormatter.isGlucoseKnown(state)) return null
    val boundaries =
        listOf(
            measuredAt + FreshnessPolicy.CURRENT_MAX_MS + 1L,
            measuredAt + FreshnessPolicy.DELAYED_MAX_MS + 1L,
            measuredAt + CgmPresentationPolicy.SIGNAL_LOSS_AFTER_MS,
        )
    return boundaries.firstOrNull { it > nowEpochMs }
}

private fun formatGlucose(
    valueMgDl: Double,
    unit: GlucoseUnit,
): String =
    if (unit == GlucoseUnit.MMOL_L) {
        String.format(Locale.US, "%.1f", valueMgDl / 18.0)
    } else {
        valueMgDl.roundToInt().toString()
    }

private fun formatDecimal(
    value: Double,
    maxDigits: Int,
): String {
    val formatted = String.format(Locale.US, "%.${maxDigits}f", value)
    return formatted.trimEnd('0').trimEnd('.')
}

private fun joinVisible(vararg values: String?): String? =
    values.mapNotNull { it?.takeIf(String::isNotBlank) }.takeIf(List<String>::isNotEmpty)?.joinToString(" ")

private fun joinSeparated(vararg values: String?): String? =
    values.mapNotNull { it?.takeIf(String::isNotBlank) }.takeIf(List<String>::isNotEmpty)?.joinToString(" · ")
