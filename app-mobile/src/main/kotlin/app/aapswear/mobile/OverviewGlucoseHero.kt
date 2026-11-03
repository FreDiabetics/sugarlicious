package app.aapswear.mobile

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aapswear.mobile.ui.theme.SugarliciousColors
import app.aapswear.model.CgmThresholds
import app.aapswear.model.GlucoseVisualSpec
import app.aapswear.model.Trend
import app.aapswear.model.TrendVisuals
import app.aapswear.uishared.R as SharedUiR

/** Glucose header with a canonical-value range bar. */
@Composable
internal fun OverviewGlucoseHero(
    glucoseText: String,
    glucoseColor: Color,
    glucoseMgDl: Double?,
    thresholds: CgmThresholds,
    rangeStats: MeasuredRangeStats,
    trend: Trend,
    delta: String,
    age: String,
    unitLabel: String,
    glucoseScalePercent: Int,
    trendScalePercent: Int,
    active: Boolean = true,
    centeredGlucose: Boolean = false,
    status: String? = null,
    onClick: () -> Unit = {},
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val reference = GlucoseVisualSpec.twoByTwoWidgetReference()
    val selected = reference.scaled(glucoseScalePercent, trendScalePercent)
    BoxWithConstraints(Modifier.fillMaxWidth().clickable(onClickLabel = "Glukosedetails", onClick = onClick)) {
        val textSize = 84f * selected.glucoseTextSize / reference.glucoseTextSize
        val arrowHeight = 50f * selected.trendHeight / reference.trendHeight * density.fontScale
        val arrowWidth = (TrendVisuals.spec(trend)?.aspectRatio ?: 0f) * arrowHeight
        val measured = measurer.measure(glucoseText, LocalTextStyle.current.copy(fontSize = textSize.sp, fontWeight = FontWeight.Bold), softWrap = false)
        val groupWidth =
            with(density) {
                measured.size.width
                    .toDp()
                    .value
            } + arrowWidth + 6f
        val fit = minOf(1f, (maxWidth.value - 96f).coerceAtLeast(1f) / groupWidth.coerceAtLeast(1f))
        val waveHeight = (maxWidth.value * .18f).coerceIn(30f, 86f).dp
        Column(Modifier.fillMaxWidth().padding(top = 0.dp, bottom = 46.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().offset(y = 44.dp).padding(start = if (centeredGlucose) 0.dp else 24.dp), horizontalArrangement = if (centeredGlucose) Arrangement.Center else Arrangement.Start) {
                GlucoseValueAndTrend(
                    glucoseText,
                    glucoseColor,
                    trend,
                    selected.copy(glucoseTextSize = textSize * fit, trendHeight = arrowHeight * fit),
                    trendColor = SugarliciousColors.TextPrimary,
                )
            }
            Spacer(Modifier.height(6.dp))
            GlucoseRangeBar(glucoseMgDl.takeIf { active }, thresholds, rangeStats, waveHeight.value.coerceAtLeast(66f))
            Spacer(Modifier.height(22.dp))
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftMotionInfoPill(
                    SharedUiR.drawable.ic_metadata_delta,
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(delta) }
                        withStyle(SpanStyle(color = SugarliciousColors.TextSecondary)) { append(" $unitLabel") }
                    },
                )
                SoftMotionInfoPill(SharedUiR.drawable.ic_metadata_age, softMotionAgeText(age, SugarliciousColors.TextPrimary, SugarliciousColors.TextSecondary))
            }
            status?.let { Text(it, color = SugarliciousColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

@Composable
private fun SoftMotionInfoPill(icon: Int, text: androidx.compose.ui.text.AnnotatedString) {
    val fontScale = LocalDensity.current.fontScale
    val iconSize = ((if (icon == SharedUiR.drawable.ic_metadata_delta) 19f else 17f) * fontScale).dp
    Row(
        Modifier.background(SugarliciousColors.Surface, RoundedCornerShape(40.dp)).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size((20f * fontScale).dp), contentAlignment = Alignment.Center) {
            SugarliciousIcon(icon, null, Modifier.size(iconSize), SugarliciousColors.TextSecondary)
        }
        Text(text, color = SugarliciousColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

internal fun softMotionAge(age: String): String {
    val minutes = age.removeSuffix("m").toLongOrNull()?.takeIf { it >= 0 } ?: return "\u2014"
    return when {
        minutes < 60 -> "vor $minutes Min."
        minutes < 1440 -> "vor ${minutes / 60} Std. ${minutes % 60} Min."
        minutes < 2880 -> "vor 1 Tag"
        else -> "vor ${minutes / 1440} Tagen"
    }
}

internal fun softMotionAgeText(age: String, numberColor: Color, labelColor: Color): androidx.compose.ui.text.AnnotatedString =
    buildAnnotatedString {
        val text = softMotionAge(age)
        withStyle(SpanStyle(color = labelColor, fontWeight = FontWeight.SemiBold)) { append(text) }
        Regex("[0-9]+").findAll(text).forEach { match ->
            addStyle(SpanStyle(color = numberColor, fontWeight = FontWeight.Bold), match.range.first, match.range.last + 1)
        }
    }

@Composable
private fun GlucoseRangeBar(value: Double?, thresholds: CgmThresholds, stats: MeasuredRangeStats, heightDp: Float) {
    val distribution = glucoseBarDistribution(stats)
    val fraction = distribution?.let { range -> value?.let { glucoseBarMarker(it, thresholds, range) } }
    val motion = updateTransition(Triple(distribution?.lowEnd ?: 0f, distribution?.highStart ?: 1f, fraction ?: .5f), label = "glucose bar motion")
    val animatedLow by motion.animateFloat(transitionSpec = { spring(dampingRatio = 1f, stiffness = 90f, visibilityThreshold = .0001f) }, label = "low time share") { it.first }
    val animatedHigh by motion.animateFloat(transitionSpec = { spring(dampingRatio = 1f, stiffness = 90f, visibilityThreshold = .0001f) }, label = "high time share") { it.second }
    val animatedPosition by motion.animateFloat(transitionSpec = { spring(dampingRatio = 1f, stiffness = 90f, visibilityThreshold = .0001f) }, label = "glucose position") { it.third }
    val low = animatedLow.coerceIn(0f, 1f)
    val high = animatedHigh.coerceIn(low, 1f)
    val position = animatedPosition.coerceIn(0f, 1f)
    val lowColor = SugarliciousColors.GlucoseLow
    val targetColor = SugarliciousColors.color(app.aapswear.mobile.ui.theme.SugarliciousColorRole.OVERVIEW_WAVE_IN_RANGE)
    val highColor = SugarliciousColors.GlucoseHigh
    Box(Modifier.fillMaxWidth(.88f).height(heightDp.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(16.dp)
                .align(Alignment.BottomCenter)
                .clearAndSetSemantics {},
        ) {
            val colors =
                buildList {
                    add(0f to if (low > 0f) lowColor else targetColor)
                    if (low > 0f) {
                        add(low to lowColor)
                        add(low to targetColor)
                    }
                    if (high < 1f) {
                        add(high to targetColor)
                        add(high to highColor)
                    }
                    add(1f to if (high < 1f) highColor else targetColor)
                }
            val gradient = Brush.horizontalGradient(*colors.toTypedArray(), startX = 0f, endX = size.width)
            val gap = 7.dp.toPx()
            val marker = size.width * position

            fun segment(left: Float, right: Float) {
                if (right > left) {
                    clipRect(left, 0f, right, size.height) {
                        drawRoundRect(gradient, Offset(left, 0f), Size(right - left, size.height), CornerRadius(size.height / 2f))
                    }
                }
            }
            if (distribution == null) {
                drawRoundRect(SugarliciousColors.TextSecondary.copy(alpha = .25f), cornerRadius = CornerRadius(size.height / 2f))
            } else if (fraction == null) {
                drawRoundRect(gradient, cornerRadius = CornerRadius(size.height / 2f), alpha = .35f)
            } else {
                segment(0f, (marker - gap / 2f).coerceAtLeast(0f))
                segment((marker + gap / 2f).coerceAtMost(size.width), size.width)
            }
        }
    }
}
