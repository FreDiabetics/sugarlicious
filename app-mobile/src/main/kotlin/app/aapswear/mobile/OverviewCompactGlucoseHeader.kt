package app.aapswear.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aapswear.mobile.ui.theme.SugarliciousColors
import app.aapswear.model.GlucoseVisualSpec
import app.aapswear.model.Trend

@Composable
internal fun OverviewCompactGlucoseHeader(glucose: String, color: Color, trend: Trend, delta: String, unit: String, age: String, modifier: Modifier = Modifier) {
    val background = Color(MobileSettingsStyle.card(SugarliciousColors.palette.isLight))
    val readableGlucose =
        if (androidx.core.graphics.ColorUtils
                .calculateContrast(color.toArgb(), background.toArgb()) >= 3.0
        ) {
            color
        } else {
            Color.White
        }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        // Measure at the device font scale, then fit the complete single-line group.
        val glucoseWidth = measurer.measure(glucose, TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold), softWrap = false).size.width
        val metadataWidth = measurer.measure("$delta $unit · vor ${age.removeSuffix("m")} Min.", TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold), softWrap = false).size.width
        val requiredWidth = glucoseWidth + metadataWidth + with(density) { 78.dp.toPx() }
        val scale = minOf(1f, constraints.maxWidth / requiredWidth)
        Row(
            Modifier.shadow(10.dp, CircleShape, clip = false, ambientColor = Color.Black.copy(alpha = .35f), spotColor = Color.Black.copy(alpha = .50f)).background(background, CircleShape).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy((12f * scale).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlucoseValueAndTrend(glucose, readableGlucose, trend, GlucoseVisualSpec.twoByTwoWidgetReference().copy(glucoseTextSize = 30f * scale, trendHeight = 24f * scale))
            OverviewReadingMetadata(delta, unit, age, compact = true, scale = scale)
        }
    }
}
