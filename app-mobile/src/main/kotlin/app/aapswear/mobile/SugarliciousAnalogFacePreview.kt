package app.aapswear.mobile

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import app.aapswear.model.CgmQuality
import app.aapswear.model.DataSourceId
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseGraphScale
import app.aapswear.model.GraphTimeWindow
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColors
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class AnalogRectGeometry(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

internal data class AnalogPointGeometry(val x: Float, val y: Float)

internal data class AnalogArcGeometry(
    val startAngle: Float,
    val endAngle: Float,
    val clockwise: Boolean,
)

/**
 * Geometry copied directly from sugarlicious-analog/src/main/res/raw/watchface.xml.
 * Keep the contract test green whenever the WFF layout is changed.
 */
internal object SugarliciousAnalogGeometry {
    const val WFS_REFERENCE_CANVAS = 450f
    const val CANVAS = 512f
    const val WFS_TO_WFF_SCALE = CANVAS / WFS_REFERENCE_CANVAS
    val center = AnalogPointGeometry(256f, 256f)
    const val watchRadius = 256f
    const val safeRadius = 236f
    const val centerSafetyRadius = 24f
    val handPivot = center
    val graph = AnalogRectGeometry(92f, 68f, 328f, 140f)
    val graphContent = AnalogRectGeometry(100f, 76f, 312f, 124f)
    val middleLeft = AnalogRectGeometry(62f, 204f, 132f, 110f)
    val middleRight = AnalogRectGeometry(318f, 204f, 132f, 110f)
    val bottomCenter = AnalogRectGeometry(146f, 312f, 220f, 116f)
    val middleLeftText = AnalogRectGeometry(70f, 268f, 112f, 32f)
    val middleLeftTitle = AnalogRectGeometry(70f, 232f, 107f, 31f)
    val middleRightText = AnalogRectGeometry(326f, 268f, 107f, 32f)
    val middleRightTitle = AnalogRectGeometry(326f, 232f, 107f, 30f)
    val bottomText = AnalogRectGeometry(164f, 334f, 184f, 64f)

    const val outerCenter = 256f
    val outerTextDiameter = fromWfsValue(376f)
    val outerProgressDiameter = fromWfsValue(359f)
    val outerStroke = fromWfsValue(15f)
    val outerUpperLeft = AnalogArcGeometry(285f, 333f, true)
    val outerUpperRight = AnalogArcGeometry(15f, 63f, true)
    val outerLowerRight = AnalogArcGeometry(103f, 151f, true)
    val outerLowerLeft = AnalogArcGeometry(253f, 205f, false)

    val glucoseProgress = AnalogRectGeometry(190f, 316f, 132f, 4f)

    private fun fromWfsValue(value: Float): Float = (value * WFS_TO_WFF_SCALE).roundToInt().toFloat()

    private fun fromWfsRect(x: Float, y: Float, width: Float, height: Float) =
        AnalogRectGeometry(
            x = fromWfsValue(x),
            y = fromWfsValue(y),
            width = fromWfsValue(width),
            height = fromWfsValue(height),
        )

    private fun within(parent: AnalogRectGeometry, x: Float, y: Float, width: Float, height: Float) =
        AnalogRectGeometry(
            x = parent.x + fromWfsValue(x),
            y = parent.y + fromWfsValue(y),
            width = fromWfsValue(width),
            height = fromWfsValue(height),
        )

    fun mirrorHorizontally(rect: AnalogRectGeometry): AnalogRectGeometry =
        rect.copy(x = CANVAS - rect.x - rect.width)

    fun centerOf(rect: AnalogRectGeometry): AnalogPointGeometry =
        AnalogPointGeometry(rect.x + rect.width / 2f, rect.y + rect.height / 2f)

}

@Composable
internal fun SugarliciousAnalogFacePreview(
    state: TherapyDisplayState?,
    modifier: Modifier = Modifier,
) {
    val now = System.currentTimeMillis()
    val displayable = TherapyDisplayFormatter.isGlucoseDisplayable(state, now)
    val glucoseState = state?.glucose
    val glucose = if (displayable && glucoseState != null) TherapyDisplayFormatter.glucose(glucoseState) else "—"
    val age = TherapyDisplayFormatter.ageMinutes(glucoseState?.measuredAtEpochMs, now)
    val iob = state?.insulin?.totalIob?.let { TherapyDisplayFormatter.units(it, "U", 1) } ?: "1.2U"
    val cob = state?.carbs?.cobGrams?.let { TherapyDisplayFormatter.units(it, "g", 0) } ?: "15g"
    val basal = state?.basal?.displayText?.takeIf { it.isNotBlank() } ?: "0.8U/h"
    val accent = Color(0xFFEB600A)

    Box(
        modifier = modifier.clip(CircleShape).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.sugarlicious_analog_template),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )

        Canvas(Modifier.fillMaxSize()) {
            val scale = size.minDimension / SugarliciousAnalogGeometry.CANVAS
            val originX = (size.width - SugarliciousAnalogGeometry.CANVAS * scale) / 2f
            val originY = (size.height - SugarliciousAnalogGeometry.CANVAS * scale) / 2f
            fun x(v: Float) = originX + v * scale
            fun y(v: Float) = originY + v * scale
            val graph = SugarliciousAnalogGeometry.graphContent

            drawRect(
                color = Color(0xFF111416),
                topLeft = androidx.compose.ui.geometry.Offset(x(graph.x), y(graph.y)),
                size = androidx.compose.ui.geometry.Size(graph.width * scale, graph.height * scale),
            )
            drawRect(
                color = Color(0x2219D7E8),
                topLeft = androidx.compose.ui.geometry.Offset(x(graph.x), y(graph.y + 33f)),
                size = androidx.compose.ui.geometry.Size(graph.width * scale, 38f * scale),
            )
            val graphWindow = GraphTimeWindow.live(now, 2L * 60L * 60_000L)
            val history = buildList {
                addAll(state?.glucoseHistory.orEmpty())
                state?.glucose?.let { current ->
                    add(
                        GlucoseSample(
                            valueMgDl = current.valueMgDl,
                            measuredAtEpochMs = current.measuredAtEpochMs,
                            source = current.source,
                            sensorId = current.sensorId,
                            sessionId = current.sessionId,
                            sequenceNumber = current.sequenceNumber,
                            receivedAtEpochMs = current.receivedAtEpochMs,
                            quality = current.quality,
                        ),
                    )
                }
            }.filter { it.quality == CgmQuality.VALID && it.measuredAtEpochMs in graphWindow.startEpochMs..graphWindow.endEpochMs }
                .distinctBy { listOf(it.sensorId, it.sessionId, it.sequenceNumber, it.measuredAtEpochMs, it.source) }
                .sortedBy(GlucoseSample::measuredAtEpochMs)
            val samples = if (history.size >= 2) {
                history
            } else {
                listOf(105.0, 112.0, 118.0, 114.0, 121.0, 128.0, 124.0, 132.0, 123.0)
                    .mapIndexed { index, value ->
                        GlucoseSample(valueMgDl = value, measuredAtEpochMs = now - (8 - index) * 5L * 60_000L)
                    }
            }
            samples.forEach { sample ->
                val fraction = graphWindow.xFraction(sample.measuredAtEpochMs).coerceIn(0f, 1f)
                val px = x(graph.x + 12f + fraction * (graph.width - 26f))
                val normalized = GlucoseGraphScale.ratio(sample.valueMgDl).toFloat()
                val py = y(graph.y + graph.height - 12f - normalized * (graph.height - 24f))
                val center = androidx.compose.ui.geometry.Offset(px, py)
                drawCircle(SugarliciousColors.color(SugarliciousColorRole.GRAPH_CURRENT_OUTLINE), 3.35f * scale, center)
                val dotColor = when {
                    sample.valueMgDl < 70.0 -> SugarliciousColors.color(SugarliciousColorRole.CGM_DOT_LOW)
                    sample.valueMgDl > 180.0 -> SugarliciousColors.color(SugarliciousColorRole.CGM_DOT_HIGH)
                    else -> SugarliciousColors.color(SugarliciousColorRole.CGM_DOT_IN_RANGE)
                }
                drawCircle(dotColor, 2.4f * scale, center)
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            val scale = size.minDimension / SugarliciousAnalogGeometry.CANVAS
            val originX = (size.width - SugarliciousAnalogGeometry.CANVAS * scale) / 2f
            val originY = (size.height - SugarliciousAnalogGeometry.CANVAS * scale) / 2f
            fun x(v: Float) = originX + v * scale
            fun y(v: Float) = originY + v * scale
            fun sweep(arc: AnalogArcGeometry): Float =
                if (arc.clockwise) {
                    (arc.endAngle - arc.startAngle + 360f) % 360f
                } else {
                    -((arc.startAngle - arc.endAngle + 360f) % 360f)
                }
            fun outerArc(arc: AnalogArcGeometry, progress: Float) {
                val diameter = SugarliciousAnalogGeometry.outerProgressDiameter * scale
                val left = x(SugarliciousAnalogGeometry.outerCenter) - diameter / 2f
                val top = y(SugarliciousAnalogGeometry.outerCenter) - diameter / 2f
                drawArc(
                    color = accent.copy(alpha = 0.22f),
                    startAngle = arc.startAngle,
                    sweepAngle = sweep(arc),
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(diameter, diameter),
                    style = Stroke(SugarliciousAnalogGeometry.outerStroke * scale, cap = StrokeCap.Round),
                )
                drawArc(
                    color = accent,
                    startAngle = arc.startAngle,
                    sweepAngle = sweep(arc) * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(diameter, diameter),
                    style = Stroke(SugarliciousAnalogGeometry.outerStroke * scale, cap = StrokeCap.Round),
                )
            }

            outerArc(SugarliciousAnalogGeometry.outerUpperLeft, 0.74f)
            outerArc(SugarliciousAnalogGeometry.outerUpperRight, 0.61f)
            outerArc(SugarliciousAnalogGeometry.outerLowerRight, 0.48f)
            outerArc(SugarliciousAnalogGeometry.outerLowerLeft, 0.69f)

            val progress = SugarliciousAnalogGeometry.glucoseProgress
            drawLine(
                color = accent,
                start = androidx.compose.ui.geometry.Offset(x(progress.x), y(progress.y + progress.height / 2f)),
                end = androidx.compose.ui.geometry.Offset(
                    x(progress.x + progress.width * if (displayable) 0.54f else 0f),
                    y(progress.y + progress.height / 2f),
                ),
                strokeWidth = progress.height * scale,
                cap = StrokeCap.Round,
            )

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
            }
            fun textCenteredInRect(value: String, rect: AnalogRectGeometry, textSize: Float, color: Int) {
                textPaint.textSize = textSize * scale
                textPaint.color = color
                textPaint.textAlign = Paint.Align.CENTER
                val metrics = textPaint.fontMetrics
                val baseline = y(rect.y + rect.height / 2f) - (metrics.ascent + metrics.descent) / 2f
                drawIntoCanvas {
                    it.nativeCanvas.drawText(value, x(rect.x + rect.width / 2f), baseline, textPaint)
                }
            }
            fun curvedOuterText(value: String, arc: AnalogArcGeometry, textSize: Float, color: Int) {
                textPaint.textSize = textSize * scale
                textPaint.color = color
                textPaint.textAlign = Paint.Align.LEFT
                val radius = (SugarliciousAnalogGeometry.outerTextDiameter / 2f) * scale
                val cx = x(SugarliciousAnalogGeometry.outerCenter)
                val cy = y(SugarliciousAnalogGeometry.outerCenter)
                val path = Path().apply {
                    addArc(
                        RectF(cx - radius, cy - radius, cx + radius, cy + radius),
                        arc.startAngle,
                        sweep(arc),
                    )
                }
                val pathLength = PathMeasure(path, false).length
                val textWidth = textPaint.measureText(value)
                drawIntoCanvas {
                    it.nativeCanvas.drawTextOnPath(value, path, ((pathLength - textWidth) / 2f).coerceAtLeast(0f), 0f, textPaint)
                }
                textPaint.textAlign = Paint.Align.CENTER
            }
            curvedOuterText("74%", SugarliciousAnalogGeometry.outerUpperLeft, 31f, 0xFFEB600A.toInt())
            curvedOuterText("160U", SugarliciousAnalogGeometry.outerUpperRight, 31f, 0xFFEB600A.toInt())
            curvedOuterText(cob, SugarliciousAnalogGeometry.outerLowerRight, 31f, 0xFFEB600A.toInt())
            curvedOuterText(
                "$iob · $basal",
                SugarliciousAnalogGeometry.outerLowerLeft,
                30f,
                0xFFEB600A.toInt(),
            )
            textCenteredInRect("$iob · $cob", SugarliciousAnalogGeometry.middleLeftText, 25f, Color.White.toArgb())
            textCenteredInRect(basal, SugarliciousAnalogGeometry.middleLeftTitle, 27f, 0xFFADADAD.toInt())
            textCenteredInRect(age, SugarliciousAnalogGeometry.middleRightText, 25f, Color.White.toArgb())
            textCenteredInRect(if (displayable) glucose else "—", SugarliciousAnalogGeometry.middleRightTitle, 25f, 0xFFADADAD.toInt())
            textCenteredInRect(
                if (displayable) glucose else "—",
                SugarliciousAnalogGeometry.bottomText,
                55f,
                0xFFFFB146.toInt(),
            )

        }

        AnalogPreviewHand(
            drawable = R.drawable.sugarlicious_analog_hour_hand,
            rotation = fixedWatchPreviewHandAngles.hour,
        )
        AnalogPreviewHand(
            drawable = R.drawable.sugarlicious_analog_minute_hand,
            rotation = fixedWatchPreviewHandAngles.minute,
        )

        Image(
            painter = painterResource(R.drawable.sugarlicious_analog_second_hand),
            contentDescription = null,
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = fixedWatchPreviewHandAngles.second
                        transformOrigin = TransformOrigin.Center
                    },
        )

        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.Black, size.minDimension * (4f / 450f), center)
        }
    }
}

@Composable
private fun AnalogPreviewHand(drawable: Int, rotation: Float) {
    Image(
        painter = painterResource(drawable),
        contentDescription = null,
        modifier =
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = rotation
                    transformOrigin = TransformOrigin.Center
                },
    )
}

private fun apexPreviewState(
    value: Double = 123.0,
    ageMinutes: Long = 2,
    source: DataSourceId = DataSourceId.DEXCOM_G7_WATCH,
    quality: CgmQuality = CgmQuality.VALID,
): TherapyDisplayState {
    val now = System.currentTimeMillis()
    return TherapyDisplayState(
        source = source,
        receivedAtEpochMs = now,
        glucose = GlucoseState(
            valueMgDl = value,
            displayUnit = GlucoseUnit.MG_DL,
            trend = Trend.FORTY_FIVE_UP,
            measuredAtEpochMs = now - ageMinutes * 60_000L,
            deltaMgDl = 7.0,
            source = source,
            quality = quality,
        ),
    )
}

@Preview(name = "ApeX Fresh", widthDp = 450, heightDp = 450)
@Composable private fun ApeXFreshPreview() = SugarliciousAnalogFacePreview(apexPreviewState())

@Preview(name = "ApeX High", widthDp = 450, heightDp = 450)
@Composable private fun ApeXHighPreview() = SugarliciousAnalogFacePreview(apexPreviewState(value = 250.0))

@Preview(name = "ApeX Low", widthDp = 450, heightDp = 450)
@Composable private fun ApeXLowPreview() = SugarliciousAnalogFacePreview(apexPreviewState(value = 55.0))

@Preview(name = "ApeX Stale", widthDp = 450, heightDp = 450)
@Composable private fun ApeXStalePreview() = SugarliciousAnalogFacePreview(apexPreviewState(ageMinutes = 20))

@Preview(name = "ApeX No source", widthDp = 450, heightDp = 450)
@Composable private fun ApeXNoSourcePreview() = SugarliciousAnalogFacePreview(null)

@Preview(name = "ApeX Sensor error", widthDp = 450, heightDp = 450)
@Composable private fun ApeXSensorErrorPreview() = SugarliciousAnalogFacePreview(apexPreviewState(quality = CgmQuality.SENSOR_ERROR))

@Preview(name = "ApeX Watch Direct", widthDp = 450, heightDp = 450)
@Composable private fun ApeXWatchDirectPreview() = SugarliciousAnalogFacePreview(apexPreviewState(source = DataSourceId.DEXCOM_G7_WATCH))

@Preview(name = "ApeX Mobile", widthDp = 450, heightDp = 450)
@Composable private fun ApeXMobilePreview() = SugarliciousAnalogFacePreview(apexPreviewState(source = DataSourceId.ANDROID_APS))

@Preview(name = "ApeX Ambient composition", widthDp = 450, heightDp = 450)
@Composable private fun ApeXAmbientPreview() = SugarliciousAnalogFacePreview(apexPreviewState())

@Preview(name = "ApeX Galaxy Watch Ultra", widthDp = 480, heightDp = 480)
@Composable private fun ApeXGalaxyWatchUltraPreview() = SugarliciousAnalogFacePreview(apexPreviewState())

@Preview(name = "ApeX Pixel Watch", widthDp = 384, heightDp = 384)
@Composable private fun ApeXPixelWatchPreview() = SugarliciousAnalogFacePreview(apexPreviewState())

@Preview(name = "ApeX Small round", widthDp = 320, heightDp = 320)
@Composable private fun ApeXSmallRoundPreview() = SugarliciousAnalogFacePreview(apexPreviewState(value = 399.0))
