package app.aapswear.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aapswear.model.Freshness
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState

private const val GRAPH_WINDOW_MS = 3L * 60L * 60_000L

@Composable
internal fun G6StyleFacePreview(
    state: TherapyDisplayState?,
    modifier: Modifier = Modifier,
) {
    val now = System.currentTimeMillis()
    val freshness = TherapyDisplayFormatter.freshness(state, now)
    val displayable = TherapyDisplayFormatter.isGlucoseDisplayable(state, now)
    val glucose = state?.glucose
    val value = if (displayable && glucose != null) TherapyDisplayFormatter.glucose(glucose) else "—"
    val trend = glucose?.trend?.takeIf { displayable }
    val unit =
        if (displayable && glucose != null) {
            if (glucose.displayUnit == GlucoseUnit.MMOL_L) "mmol/L" else "mg/dL"
        } else {
            TherapyDisplayFormatter.freshnessLabel(freshness)
        }
    val source = TherapyDisplayFormatter.sourceName(state?.source)
    val age = TherapyDisplayFormatter.ageMinutes(glucose?.measuredAtEpochMs, now)
    val targetLow = (state?.target?.lowMgDl ?: 70.0).coerceIn(40.0, 180.0)
    val targetHigh = (state?.target?.highMgDl ?: 180.0).coerceIn(targetLow + 1.0, 300.0)
    val samples = g6PreviewSamples(state, now, displayable)

    BoxWithConstraints(
        modifier = modifier.clip(CircleShape).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val w = maxWidth
        val h = maxHeight
        Canvas(Modifier.fillMaxSize()) {
            val left = size.width * 0.055f
            val right = size.width * 0.945f
            val top = size.height * 0.36f
            val bottom = size.height * 0.70f
            fun yFor(valueMgDl: Double): Float {
                val fraction = ((valueMgDl.coerceIn(40.0, 300.0) - 40.0) / 260.0).toFloat()
                return bottom - fraction * (bottom - top)
            }

            drawRect(
                color = Color(0xFF172234),
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
            )
            drawRect(
                color = Color(0xFF111925),
                topLeft = Offset(left, top),
                size = Size(right - left, yFor(targetHigh) - top),
            )
            drawRect(
                color = Color(0xFFB0005D),
                topLeft = Offset(left, yFor(targetLow)),
                size = Size(right - left, bottom - yFor(targetLow)),
            )
            drawLine(
                color = Color(0xFFFF2C82),
                start = Offset(left, yFor(targetLow)),
                end = Offset(right, yFor(targetLow)),
                strokeWidth = size.width * 0.007f,
            )

            val cutoff = now - GRAPH_WINDOW_MS
            samples.forEach { sample ->
                val xFraction = ((sample.measuredAtEpochMs - cutoff).toDouble() / GRAPH_WINDOW_MS.toDouble()).coerceIn(0.0, 1.0)
                val x = left + (right - left) * xFraction.toFloat()
                drawCircle(
                    color = Color.White,
                    radius = size.width * 0.010f,
                    center = Offset(x, yFor(sample.valueMgDl)),
                )
            }
        }

        Row(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = h * 0.075f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                color = Color.White,
                fontSize = (w.value * 0.25f).sp,
                fontWeight = FontWeight.Bold,
            )
            trend?.let {
                SugarliciousTrendIndicator(
                    trend = it,
                    modifier = Modifier.padding(start = w * 0.02f),
                    color = Color.White,
                    arrowSize = w * 0.19f,
                )
            }
        }
        Text(
            text = unit,
            color = Color.White,
            fontSize = (w.value * 0.09f).sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = h * 0.275f),
        )
        Text(
            text = "3HR",
            color = Color.White,
            fontSize = (w.value * 0.075f).sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopStart).padding(start = w * 0.08f, top = h * 0.375f),
        )
        Text(
            text = "300",
            color = Color.White,
            fontSize = (w.value * 0.075f).sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = w * 0.07f, top = h * 0.375f),
        )
        if (!displayable) {
            Text(
                text = TherapyDisplayFormatter.freshnessLabel(freshness),
                color = Color.White,
                fontSize = (w.value * 0.07f).sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).offset(y = h * 0.08f),
            )
        }
        Text(
            text = "5:40",
            color = Color.White,
            fontSize = (w.value * 0.22f).sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = h * 0.115f),
        )
        Text(
            text = "$source · $age · ${TherapyDisplayFormatter.freshnessLabel(freshness)}",
            color = Color(0xFFD9D7FF),
            fontSize = (w.value * 0.052f).sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = w * 0.09f, end = w * 0.09f, bottom = h * 0.045f),
        )
    }
}

private fun g6PreviewSamples(
    state: TherapyDisplayState?,
    nowEpochMs: Long,
    displayable: Boolean,
): List<GlucoseSample> {
    if (!displayable) return emptyList()
    val cutoff = nowEpochMs - GRAPH_WINDOW_MS
    val merged = linkedMapOf<Long, GlucoseSample>()
    state?.glucoseHistory.orEmpty().forEach { sample ->
        if (sample.measuredAtEpochMs in cutoff..(nowEpochMs + 5 * 60_000L) && sample.valueMgDl in 20.0..1000.0) {
            merged[sample.measuredAtEpochMs] = sample
        }
    }
    state?.glucose?.let { current ->
        if (current.measuredAtEpochMs in cutoff..(nowEpochMs + 5 * 60_000L)) {
            merged[current.measuredAtEpochMs] = GlucoseSample(current.valueMgDl, current.measuredAtEpochMs)
        }
    }
    return merged.values.sortedBy(GlucoseSample::measuredAtEpochMs)
}
