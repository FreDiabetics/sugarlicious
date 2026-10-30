package app.aapswear.mobile

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColors
import app.aapswear.model.AapsDisplayField
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.TherapyIndicatorGeometry
import app.aapswear.model.TherapyIndicatorIcon
import app.aapswear.model.TherapyProgressMetric
import app.aapswear.model.TherapyProgressSemantics
import app.aapswear.model.TherapyRingGeometry
import app.aapswear.model.basalIndicatorIcon
import app.aapswear.model.effectiveBasalPresentation
import app.aapswear.model.latestDeliveredBolus
import app.aapswear.uishared.R as SharedUiR

internal data class TherapyIndicatorPresentation(
    val label: String,
    val value: String,
    val secondary: String? = null,
    val progress: Float? = null,
    @DrawableRes val iconRes: Int,
    val colorRole: SugarliciousColorRole,
)

internal fun therapyIndicatorPresentations(
    state: TherapyDisplayState?,
    iobMaximumUnits: Float,
    nowEpochMs: Long,
    cobMaximumGrams: Float = 300f,
): List<TherapyIndicatorPresentation> {
    val iob = state?.insulin?.totalIob?.takeIf(Double::isFinite)
    val cob = state?.carbs?.cobGrams?.takeIf(Double::isFinite)
    val basal = effectiveBasalPresentation(state, nowEpochMs)
    val safeIobMaximum = iobMaximumUnits.takeIf { it > 0f }?.toDouble()
    val safeCobMaximum = cobMaximumGrams.takeIf { it > 0f }?.toDouble()
    return listOf(
        TherapyIndicatorPresentation(
            label = "IOB",
            value = TherapyDisplayFormatter.aaps(AapsDisplayField.IOB, iob, state),
            secondary = latestBolusPresentation(state, nowEpochMs),
            progress = iob?.let { TherapyProgressSemantics.scaled(it, safeIobMaximum) },
            iconRes = SharedUiR.drawable.ic_iob,
            colorRole = SugarliciousColorRole.THERAPY_IOB_PROGRESS,
        ),
        TherapyIndicatorPresentation(
            label = "COB",
            value = TherapyDisplayFormatter.aaps(AapsDisplayField.COB, cob, state),
            progress = cob?.let { TherapyProgressSemantics.scaled(it, safeCobMaximum) },
            iconRes = SharedUiR.drawable.ic_cob,
            colorRole = SugarliciousColorRole.THERAPY_COB_PROGRESS,
        ),
        TherapyIndicatorPresentation(
            label = "Basal",
            value = TherapyDisplayFormatter.aaps(AapsDisplayField.BASAL, basal?.unitsPerHour, state).removeSuffix("/h"),
            secondary = basal?.percent?.takeIf { it != 100 }?.let { "@$it%" },
            progress = basal?.percent?.let(::basalProgress),
            iconRes = basalIconResource(basal?.percent),
            colorRole = SugarliciousColorRole.THERAPY_BASAL_PROGRESS,
        ),
    )
}

internal fun latestBolusPresentation(
    state: TherapyDisplayState?,
    nowEpochMs: Long,
): String? {
    val bolus = latestDeliveredBolus(state, nowEpochMs) ?: return null
    return formatCompactBolusAge(nowEpochMs - bolus.deliveredAtEpochMs)
}

internal fun formatCompactBolusAge(ageMs: Long): String {
    val totalMinutes = ageMs.coerceAtLeast(0L) / 60_000L
    return "vor ${totalMinutes}m"
}

internal fun basalIconResource(percent: Int?): Int =
    when (basalIndicatorIcon(percent)) {
        TherapyIndicatorIcon.BASAL -> SharedUiR.drawable.ic_basal
        TherapyIndicatorIcon.BASAL_LESS -> SharedUiR.drawable.ic_basalless
        TherapyIndicatorIcon.BASAL_MORE -> SharedUiR.drawable.ic_basalmore
        TherapyIndicatorIcon.IOB, TherapyIndicatorIcon.COB -> error("Basal icon expected")
    }

internal fun basalProgress(percent: Int): Float =
    requireNotNull(TherapyProgressSemantics.basal(percent))

internal fun therapyIndicatorSecondaryFontSizeSp(value: String): Int =
    when {
        value.length >= 14 -> 7
        value.length >= 10 -> 8
        else -> 10
    }

@Composable
internal fun TherapyIndicatorRow(
    indicators: List<TherapyIndicatorPresentation>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.padding(horizontal = 1.dp), contentAlignment = Alignment.Center) {
        val fontScale = LocalDensity.current.fontScale
        val spaceForThree = (maxWidth.value - 12f) / 3f
        val diameter = if (spaceForThree >= 66f * fontScale) minOf(76f * fontScale, spaceForThree) else 76f * fontScale
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            indicators.forEach { indicator -> TherapyCircularIndicator(indicator, Modifier, diameter) }
        }
    }
}

@Composable
internal fun TherapyCircularIndicator(
    indicator: TherapyIndicatorPresentation,
    modifier: Modifier,
    diameterDp: Float,
) {
    val accent = SugarliciousColors.color(indicator.colorRole)
    val density = LocalDensity.current
    val proportionalScale = TherapyIndicatorGeometry.scale(diameterDp)
    val measurer = rememberTextMeasurer()

    fun fittedSize(value: String, baseSp: Float): Float {
        // The measured ring already includes the surface's system scaling.
        val nominal = TherapyIndicatorGeometry.fontSizeSp(baseSp, diameterDp, density.fontScale)
        val measured = measurer.measure(value, TextStyle(fontSize = nominal.sp, fontWeight = FontWeight.Bold), softWrap = false).size.width
        val available = with(density) { (diameterDp * TherapyIndicatorGeometry.VALUE_WIDTH_FRACTION).dp.toPx() }
        return nominal * minOf(1f, available / measured.coerceAtLeast(1))
    }
    Box(
        modifier =
            modifier.semantics {
                contentDescription =
                    buildString {
                        append(indicator.label)
                        append(' ')
                        append(indicator.value)
                        indicator.secondary?.let { append(", ").append(it) }
                    }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(diameterDp.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val geometry = TherapyRingGeometry()
                val stroke = (geometry.strokeWidthDp * proportionalScale).dp.toPx()
                val basal = indicator.label == "Basal"
                val marker = basal && indicator.progress != null
                val outline = (TherapyIndicatorGeometry.MARKER_OUTLINE_DP * proportionalScale).dp.toPx()
                // Keep the arc geometry stable when refining the marker contour.
                val inset = stroke / 2f + if (marker) (TherapyIndicatorGeometry.MARKER_INSET_DP * proportionalScale).dp.toPx() else 0f
                val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
                val sweep = geometry.sweepDegrees
                val arcs = app.aapswear.model.progressArcSegments(indicator.progress, arcSize.width + stroke, stroke, sweep)
                if (marker) {
                    drawArc(accent, geometry.composeStartDegrees, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                } else {
                    if (arcs.trackSweep > 0f) drawArc(accent.copy(alpha = .30f), geometry.composeStartDegrees + arcs.trackStartOffset, arcs.trackSweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    if (arcs.fillSweep > 0f) drawArc(accent, geometry.composeStartDegrees, arcs.fillSweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                if (marker) {
                    val angle = Math.toRadians((geometry.composeStartDegrees + arcs.fillSweep).toDouble())
                    val center = Offset(size.width / 2f + arcSize.width / 2f * kotlin.math.cos(angle).toFloat(), size.height / 2f + arcSize.height / 2f * kotlin.math.sin(angle).toFloat())
                    drawCircle(accent, stroke / 2f, center)
                    drawCircle(androidx.compose.ui.graphics.Color.Black, stroke / 2f + outline / 2f, center, style = Stroke(outline))
                }
            }
            val valueTextSize = fittedSize(indicator.value, TherapyIndicatorGeometry.VALUE_TEXT_SP)
            Text(
                indicator.value,
                modifier = Modifier.align(Alignment.Center),
                color = SugarliciousColors.TextPrimary,
                fontSize = valueTextSize.sp,
                lineHeight = valueTextSize.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
            )
            indicator.secondary?.let {
                val secondaryTextSize = fittedSize(it, therapyIndicatorSecondaryFontSizeSp(it).toFloat())
                Text(
                    it,
                    modifier = Modifier.align(Alignment.Center).offset(y = (diameterDp * .17f).dp),
                    color = SugarliciousColors.TextSecondary,
                    fontSize = secondaryTextSize.sp,
                    lineHeight = secondaryTextSize.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            val metric =
                when (indicator.label) {
                    "IOB" -> TherapyProgressMetric.IOB
                    "COB" -> TherapyProgressMetric.COB
                    else -> TherapyProgressMetric.BASAL
                }
            SugarliciousIcon(
                indicator.iconRes,
                null,
                Modifier.align(Alignment.Center).offset(y = (diameterDp * (TherapyIndicatorGeometry.ICON_CENTER_Y_FRACTION - .5f)).dp).size(TherapyIndicatorGeometry.iconSizeDp(metric, diameterDp).dp),
                accent,
            )
        }
    }
}
