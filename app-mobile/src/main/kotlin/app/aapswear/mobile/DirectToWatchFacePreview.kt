package app.aapswear.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aapswear.model.CgmQuality
import app.aapswear.model.CgmThresholds
import app.aapswear.model.DataSourceId
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.GraphTimeWindow
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import app.aapswear.uishared.SharedWearCgmGraphInput
import app.aapswear.uishared.SharedWearCgmGraphPalette
import app.aapswear.uishared.SharedWearCgmGraphRenderer
import app.aapswear.uishared.SharedWearCgmGraphStyle

private const val DIRECT_PREVIEW_GRAPH_HOURS = 3
private const val HOUR_MS = 60L * 60_000L

@Composable
internal fun DirectToWatchFacePreview(state: TherapyDisplayState?, modifier: Modifier = Modifier) {
    val now = System.currentTimeMillis()
    val direct = state?.takeIf { it.source == DataSourceId.DEXCOM_G7_WATCH }
    val displayable = TherapyDisplayFormatter.isGlucoseDisplayable(direct, now)
    val glucose = direct?.glucose
    val value = if (displayable && glucose != null) TherapyDisplayFormatter.glucose(glucose) else "—"
    val trend = glucose?.trend?.takeIf { displayable && TherapyDisplayFormatter.trendArrow(it).isNotBlank() }
    val delta = glucose?.takeIf { displayable }?.let { TherapyDisplayFormatter.signedDelta(it.deltaMgDl, it.displayUnit) }.orEmpty()
    val unit = if (glucose?.displayUnit == GlucoseUnit.MMOL_L) "mmol/L" else "mg/dL"
    val secondary = if (displayable) listOf(delta, unit).filter(String::isNotBlank).joinToString(" ") else "NO_SOURCE"
    val age = glucose?.let { TherapyDisplayFormatter.ageMinutesValue(it.measuredAtEpochMs, now)?.let { minutes -> "$minutes min" } } ?: "NO_SOURCE"
    val thresholds = CgmThresholds.DEFAULT
    val samples = directPreviewSamples(direct, now)

    BoxWithConstraints(modifier = modifier.clip(CircleShape).background(Color.Black), contentAlignment = Alignment.Center) {
        val w = maxWidth
        val h = maxHeight
        Row(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = h * 0.075f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, color = Color.White, fontSize = (w.value * 0.225f).sp, fontWeight = FontWeight.Bold)
            trend?.let {
                SugarliciousTrendIndicator(
                    trend = it,
                    modifier = Modifier.padding(start = w * 0.018f),
                    color = Color.White,
                    arrowSize = w * 0.13f,
                )
            }
        }
        Text(
            secondary,
            color = Color(0xFFA8A8BA),
            fontSize = (w.value * 0.055f).sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = h * 0.23f),
        )
        Canvas(
            Modifier.align(Alignment.TopCenter).padding(top = h * 0.31f).fillMaxSize(0.91f),
        ) {
            val graphHeight = size.height * 0.46f
            drawIntoCanvas { canvas ->
                val checkpoint = canvas.nativeCanvas.save()
                canvas.nativeCanvas.clipRect(0f, 0f, size.width, graphHeight)
                SharedWearCgmGraphRenderer.render(
                    canvas.nativeCanvas,
                    size.width.toInt(),
                    graphHeight.toInt(),
                    density,
                    density,
                    SharedWearCgmGraphInput(
                        history = samples,
                        timeWindow = GraphTimeWindow.live(now, DIRECT_PREVIEW_GRAPH_HOURS * HOUR_MS),
                        nowEpochMs = now,
                        thresholds = thresholds,
                        palette = previewPalette,
                        style = SharedWearCgmGraphStyle(cornerRadiusDp = 20f),
                        emptyLabel = secondary,
                    ),
                )
                canvas.nativeCanvas.restoreToCount(checkpoint)
            }
        }
        Text(
            "${DIRECT_PREVIEW_GRAPH_HOURS}h • $age",
            color = Color(0xFFA8A8BA),
            fontSize = (w.value * 0.047f).sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = h * 0.73f),
        )
        Text(
            "12:42",
            color = Color.White,
            fontSize = (w.value * 0.19f).sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = h * 0.035f),
        )
    }
}

private fun directPreviewSamples(state: TherapyDisplayState?, now: Long): List<GlucoseSample> =
    buildList {
        addAll(state?.glucoseHistory.orEmpty())
        state?.glucose?.let { add(GlucoseSample(it.valueMgDl, it.measuredAtEpochMs, source = it.source, quality = it.quality)) }
    }.filter {
        it.source == DataSourceId.DEXCOM_G7_WATCH && it.quality == CgmQuality.VALID &&
            it.measuredAtEpochMs >= now - DIRECT_PREVIEW_GRAPH_HOURS * HOUR_MS
    }.distinctBy { it.measuredAtEpochMs }.sortedBy { it.measuredAtEpochMs }

private val previewPalette = SharedWearCgmGraphPalette(
    background = 0xFF111A27.toInt(),
    targetArea = 0xFF172234.toInt(),
    highArea = 0xFF3A2217.toInt(),
    lowArea = 0xFFB0005D.toInt(),
    highLine = 0xFFFFA64D.toInt(),
    lowLine = 0xFFFF2C82.toInt(),
    dotHigh = 0xFFFFA64D.toInt(),
    dotInRange = 0xFFFFFFFF.toInt(),
    dotLow = 0xFFFF2C82.toInt(),
    dotOutline = 0xFF000000.toInt(),
    axisText = 0xFFA8A8BA.toInt(),
    axisTick = 0xFF687080.toInt(),
    nowLine = 0xFF687080.toInt(),
    border = 0xFF222A38.toInt(),
    predictionIob = Color.Cyan.hashCode(),
    predictionCob = Color.Yellow.hashCode(),
    predictionUam = Color.Green.hashCode(),
    predictionZeroTemp = Color.Magenta.hashCode(),
)
