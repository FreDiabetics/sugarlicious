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
import androidx.compose.ui.graphics.Color
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
import app.aapswear.model.BasalState
import app.aapswear.model.TherapyDisplayState
import java.util.Locale

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
): List<TherapyIndicatorPresentation> {
    val iob = state?.insulin?.totalIob?.takeIf { it.isFinite() && it >= 0.0 }
    val cob = state?.carbs?.cobGrams?.takeIf { it.isFinite() && it >= 0.0 }
    val basal = effectiveBasalPresentation(state?.basal, nowEpochMs)
    val safeIobMaximum = iobMaximumUnits.takeIf { it > 0f }?.toDouble()
    return listOf(
        TherapyIndicatorPresentation(
            label = "IOB",
            value = iob?.let { compactValue(it, 2) } ?: "—",
            secondary = iob?.let { "U" },
            progress = safeIobMaximum?.let { maximum -> iob?.div(maximum)?.toFloat()?.coerceIn(0f, 1f) },
            iconRes = R.drawable.ic_iob,
            colorRole = SugarliciousColorRole.THERAPY_IOB_PROGRESS,
        ),
        TherapyIndicatorPresentation(
            label = "COB",
            value = cob?.let { compactValue(it, 0) } ?: "—",
            secondary = cob?.let { "g" },
            progress = cob?.div(300.0)?.toFloat()?.coerceIn(0f, 1f),
            iconRes = R.drawable.ic_carbs,
            colorRole = SugarliciousColorRole.THERAPY_COB_PROGRESS,
        ),
        TherapyIndicatorPresentation(
            label = "Basal",
            value = basal?.unitsPerHour?.let { compactValue(it, 2) } ?: "—",
            secondary = basal?.percent?.let { "$it%" },
            progress = basal?.percent?.div(500f)?.coerceIn(0f, 1f),
            iconRes = R.drawable.ic_basal,
            colorRole = SugarliciousColorRole.THERAPY_BASAL_PROGRESS,
        ),
    )
}

internal data class EffectiveBasalPresentation(val unitsPerHour: Double, val percent: Int)

internal fun effectiveBasalPresentation(basal: BasalState?, nowEpochMs: Long): EffectiveBasalPresentation? {
    basal ?: return null
    val explicitEnd = basal.tempEndsAtEpochMs
        ?: basal.tempStartedAtEpochMs?.let { start -> basal.tempDurationMinutes?.let { start + it * 60_000L } }
    val tempActive = (basal.tempAbsoluteUnitsPerHour != null || basal.tempPercent != null) &&
        (explicitEnd == null || explicitEnd > nowEpochMs)
    val units = (if (tempActive) basal.tempAbsoluteUnitsPerHour else null)
        ?: basal.currentUnitsPerHour
        ?: return null
    if (!units.isFinite() || units < 0.0) return null
    val percent = if (tempActive) basal.tempPercent else 100
    return percent?.takeIf { it in 0..500 }?.let { EffectiveBasalPresentation(units, it) }
}

private fun compactValue(value: Double, decimals: Int): String =
    String.format(Locale.GERMANY, if (decimals == 0) "%.0f" else "%.${decimals}f", value)

@Composable
internal fun TherapyIndicatorRow(
    indicators: List<TherapyIndicatorPresentation>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = 1.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        indicators.forEach { indicator ->
            TherapyCircularIndicator(indicator, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun TherapyCircularIndicator(indicator: TherapyIndicatorPresentation, modifier: Modifier) {
    val accent = SugarliciousColors.color(indicator.colorRole)
    Box(
        modifier = modifier.semantics {
            contentDescription = buildString {
                append(indicator.label)
                append(' ')
                append(indicator.value)
                indicator.secondary?.let { append(", ").append(it) }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(55.dp)) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = SugarliciousColors.SurfaceRaised,
                startAngle = 130f,
                sweepAngle = 280f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            indicator.progress?.takeIf { it > 0f }?.let { progress ->
                drawArc(
                    color = accent,
                    startAngle = 130f,
                    sweepAngle = 280f * progress,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(indicator.value, color = SugarliciousColors.TextPrimary, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                SugarliciousIcon(indicator.iconRes, null, Modifier.size(10.dp), accent)
                indicator.secondary?.let {
                    Text(it, color = SugarliciousColors.TextSecondary, fontSize = 8.sp, lineHeight = 9.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
