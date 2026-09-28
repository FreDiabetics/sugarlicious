package app.aapswear.mobile

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColors
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.TherapyIndicatorIcon
import app.aapswear.model.TherapyProgressSemantics
import app.aapswear.model.TherapyRingGeometry
import app.aapswear.model.basalIndicatorIcon
import app.aapswear.model.effectiveBasalPresentation
import java.util.Locale
import app.aapswear.uishared.R as SharedUiR

internal data class TherapyIndicatorPresentation(
    val label: String,
    val value: String,
    val secondary: String? = null,
    val progress: Float? = null,
    @DrawableRes val iconRes: Int,
    val iconSizeDp: Int = 19,
    val colorRole: SugarliciousColorRole,
)

internal fun therapyIndicatorPresentations(
    state: TherapyDisplayState?,
    iobMaximumUnits: Float,
    nowEpochMs: Long,
    cobMaximumGrams: Float = 300f,
): List<TherapyIndicatorPresentation> {
    val iob = state?.insulin?.totalIob?.takeIf(Double::isFinite) ?: 0.0
    val cob = state?.carbs?.cobGrams?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    val basal = effectiveBasalPresentation(state, nowEpochMs)
    val safeIobMaximum = iobMaximumUnits.takeIf { it > 0f }?.toDouble()
    val safeCobMaximum = cobMaximumGrams.takeIf { it > 0f }?.toDouble()
    return listOf(
        TherapyIndicatorPresentation(
            label = "IOB",
            value = TherapyDisplayFormatter.iob(iob, "U", 2),
            progress = TherapyProgressSemantics.scaled(iob, safeIobMaximum),
            iconRes = SharedUiR.drawable.ic_iob,
            colorRole = SugarliciousColorRole.THERAPY_IOB_PROGRESS,
        ),
        TherapyIndicatorPresentation(
            label = "COB",
            value = "${compactValue(cob, 0)}g",
            progress = TherapyProgressSemantics.scaled(cob, safeCobMaximum),
            iconRes = SharedUiR.drawable.ic_carbs,
            iconSizeDp = 17,
            colorRole = SugarliciousColorRole.THERAPY_COB_PROGRESS,
        ),
        TherapyIndicatorPresentation(
            label = "Basal",
            value = basal?.unitsPerHour?.let { "${compactValue(it, 2)}U/h" } ?: "—",
            secondary = basal?.percent?.takeIf { it != 100 }?.let { "@$it%" },
            progress = basal?.percent?.let(::basalProgress),
            iconRes = basalIconResource(basal?.percent),
            colorRole = SugarliciousColorRole.THERAPY_BASAL_PROGRESS,
        ),
    )
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

private fun compactValue(
    value: Double,
    decimals: Int,
): String = String.format(Locale.US, if (decimals == 0) "%.0f" else "%.${decimals}f", value)

internal fun therapyIndicatorFontSizeSp(value: String): Int =
    when {
        value.length >= 8 -> 11
        value.length >= 7 -> 13
        else -> 15
    }

@Composable
internal fun TherapyIndicatorRow(
    indicators: List<TherapyIndicatorPresentation>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        indicators.forEach { indicator ->
            TherapyCircularIndicator(indicator, Modifier.fillMaxHeight())
        }
    }
}

@Composable
private fun TherapyCircularIndicator(
    indicator: TherapyIndicatorPresentation,
    modifier: Modifier,
) {
    val accent = SugarliciousColors.color(indicator.colorRole)
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
        Box(Modifier.size(66.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val geometry = TherapyRingGeometry()
                val stroke = geometry.strokeWidthDp.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = accent.copy(alpha = 0.30f),
                    startAngle = geometry.composeStartDegrees,
                    sweepAngle = geometry.sweepDegrees,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                indicator.progress?.takeIf { it > 0f }?.let { progress ->
                    drawArc(
                        color = accent,
                        startAngle = geometry.composeStartDegrees,
                        sweepAngle = geometry.sweepDegrees * progress,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Column(
                modifier = Modifier.align(Alignment.Center).padding(top = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val valueTextSize = therapyIndicatorFontSizeSp(indicator.value)
                Text(
                    indicator.value,
                    color = SugarliciousColors.TextPrimary,
                    fontSize = valueTextSize.sp,
                    lineHeight = valueTextSize.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                )
                indicator.secondary?.let {
                    Text(it, color = SugarliciousColors.TextSecondary, fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            SugarliciousIcon(
                indicator.iconRes,
                null,
                Modifier.align(Alignment.BottomCenter).size(indicator.iconSizeDp.dp),
                accent,
            )
        }
    }
}
