package app.aapswear.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aapswear.model.GlucoseVisualSpec
import app.aapswear.model.Trend
import app.aapswear.model.TrendVisuals

/** Presentation only: the open arc carries no quantitative meaning and never animates. */
@Composable
internal fun OverviewGlucoseHero(
    glucoseText: String,
    glucoseColor: Color,
    accentColor: Color,
    trend: Trend,
    delta: String,
    age: String,
    unitLabel: String,
    glucoseScalePercent: Int,
    trendScalePercent: Int,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val reference = GlucoseVisualSpec.twoByTwoWidgetReference()
    val selected = reference.scaled(glucoseScalePercent, trendScalePercent)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        val textSize = 96f * selected.glucoseTextSize / reference.glucoseTextSize
        val arrowHeight = 57f * selected.trendHeight / reference.trendHeight * density.fontScale
        val arrowWidth = (TrendVisuals.spec(trend)?.aspectRatio ?: 0f) * arrowHeight
        val measured = measurer.measure(glucoseText, LocalTextStyle.current.copy(fontSize = textSize.sp, fontWeight = FontWeight.Bold), softWrap = false)
        val groupWidth =
            with(density) {
                measured.size.width
                    .toDp()
                    .value
            } + arrowWidth + 6f
        val fit = minOf(1f, maxWidth.value / groupWidth.coerceAtLeast(1f))
        Column(
            Modifier.fillMaxWidth().heightIn(min = 240.dp).padding(vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GlucoseValueAndTrend(
                glucoseText = glucoseText,
                glucoseColor = glucoseColor,
                trend = trend,
                visualSpec = selected.copy(glucoseTextSize = textSize * fit, trendHeight = arrowHeight * fit),
            )
            Spacer(Modifier.height(8.dp))
            OverviewReadingMetadata(delta, unitLabel, age)
            Spacer(Modifier.height(14.dp))
            val accent = accentColor
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .height(28.dp)
                    .clearAndSetSemantics { },
            ) {
                val inset = 3.dp.toPx()
                val arc =
                    Path().apply {
                        moveTo(inset, inset)
                        quadraticTo(size.width / 2f, size.height * 1.5f, size.width - inset, inset)
                    }
                drawPath(
                    arc,
                    Brush.horizontalGradient(listOf(accent.copy(alpha = .12f), accent, accent.copy(alpha = .55f))),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
    }
}
