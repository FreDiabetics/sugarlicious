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
import app.aapswear.model.Freshness
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.TrendVisuals
import app.aapswear.uishared.TrendVectorPaths
import kotlin.math.cos
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
    const val CANVAS = 512f
    val center = AnalogPointGeometry(256f, 256f)
    const val watchRadius = 256f
    const val safeRadius = 236f
    const val centerSafetyRadius = 24f
    val graph = AnalogRectGeometry(96f, 78f, 320f, 112f)
    val middleLeft = AnalogRectGeometry(82f, 193f, 125f, 125f)
    val middleRight = mirrorHorizontally(middleLeft)
    val bottomCenter = AnalogRectGeometry(182f, 283f, 148f, 148f)

    const val outerCenter = 256f
    const val outerDiameter = 448f
    const val outerStroke = 12f
    val outerUpperLeft = AnalogArcGeometry(284f, 342f, true)
    val outerUpperRight = AnalogArcGeometry(18f, 76f, true)
    val outerLowerRight = AnalogArcGeometry(104f, 162f, true)
    val outerLowerLeft = AnalogArcGeometry(256f, 198f, false)

    const val middleArcDiameter = 104f
    const val middleArcStart = 215f
    const val middleArcSweep = 290f
    const val middleArcStroke = 6f
    const val bottomArcDiameter = 116f
    const val bottomArcStart = 218f
    const val bottomArcSweep = 284f
    const val bottomArcStroke = 10f

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
    val freshness = TherapyDisplayFormatter.freshness(state, now)
    val displayable = TherapyDisplayFormatter.isGlucoseDisplayable(state, now)
    val glucoseState = state?.glucose
    val glucose = if (displayable && glucoseState != null) TherapyDisplayFormatter.glucose(glucoseState) else "—"
    val age = TherapyDisplayFormatter.ageMinutes(glucoseState?.measuredAtEpochMs, now)
    val source = TherapyDisplayFormatter.sourceName(state?.source)
    val iob = state?.insulin?.totalIob?.let { TherapyDisplayFormatter.units(it, "U", 1) } ?: "1.2U"
    val cob = state?.carbs?.cobGrams?.let { TherapyDisplayFormatter.units(it, "g", 0) } ?: "15g"
    val basal = state?.basal?.displayText?.takeIf { it.isNotBlank() } ?: "0.8U/h"
    val status = when (freshness) {
        Freshness.CURRENT -> "$source · $age"
        Freshness.DELAYED -> "VERZÖGERT · $age"
        Freshness.STALE -> "VERALTET"
        Freshness.ERROR -> "SENSORFEHLER"
        Freshness.NO_DATA -> "KEINE DATEN"
    }
    val accent = Color(0xFFEB600A)

    Box(
        modifier = modifier.clip(CircleShape).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val scale = size.minDimension / SugarliciousAnalogGeometry.CANVAS
            val originX = (size.width - SugarliciousAnalogGeometry.CANVAS * scale) / 2f
            val originY = (size.height - SugarliciousAnalogGeometry.CANVAS * scale) / 2f
            fun x(v: Float) = originX + v * scale
            fun y(v: Float) = originY + v * scale
            val graph = SugarliciousAnalogGeometry.graph

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
            val history = state?.glucoseHistory.orEmpty().takeLast(24)
            val samples = if (history.size >= 2) history.map { it.valueMgDl } else listOf(105.0, 112.0, 118.0, 114.0, 121.0, 128.0, 124.0, 132.0, 123.0)
            val min = 60.0
            val max = 220.0
            samples.forEachIndexed { index, value ->
                val fraction = if (samples.size <= 1) 0f else index.toFloat() / (samples.size - 1).toFloat()
                val px = x(graph.x + 12f + fraction * (graph.width - 26f))
                val normalized = ((value - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
                val py = y(graph.y + graph.height - 12f - normalized * (graph.height - 24f))
                drawCircle(Color.White, 2.7f * scale, androidx.compose.ui.geometry.Offset(px, py))
            }
        }

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
            fun sweep(arc: AnalogArcGeometry): Float =
                if (arc.clockwise) {
                    (arc.endAngle - arc.startAngle + 360f) % 360f
                } else {
                    -((arc.startAngle - arc.endAngle + 360f) % 360f)
                }
            fun outerArc(arc: AnalogArcGeometry, progress: Float) {
                val diameter = SugarliciousAnalogGeometry.outerDiameter * scale
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

            fun roundSlot(rect: AnalogRectGeometry, diameter: Float, start: Float, sweep: Float, stroke: Float, progress: Float) {
                val cx = x(rect.x + rect.width / 2f)
                val cy = y(rect.y + rect.height / 2f)
                val d = diameter * scale
                val left = cx - d / 2f
                val top = cy - d / 2f
                drawArc(
                    accent.copy(alpha = 0.22f),
                    start,
                    sweep,
                    false,
                    androidx.compose.ui.geometry.Offset(left, top),
                    androidx.compose.ui.geometry.Size(d, d),
                    style = Stroke(stroke * scale, cap = StrokeCap.Round),
                )
                drawArc(
                    accent,
                    start,
                    sweep * progress.coerceIn(0f, 1f),
                    false,
                    androidx.compose.ui.geometry.Offset(left, top),
                    androidx.compose.ui.geometry.Size(d, d),
                    style = Stroke(stroke * scale, cap = StrokeCap.Round),
                )
            }

            roundSlot(
                SugarliciousAnalogGeometry.middleLeft,
                SugarliciousAnalogGeometry.middleArcDiameter,
                SugarliciousAnalogGeometry.middleArcStart,
                SugarliciousAnalogGeometry.middleArcSweep,
                SugarliciousAnalogGeometry.middleArcStroke,
                0.58f,
            )
            roundSlot(
                SugarliciousAnalogGeometry.middleRight,
                SugarliciousAnalogGeometry.middleArcDiameter,
                SugarliciousAnalogGeometry.middleArcStart,
                SugarliciousAnalogGeometry.middleArcSweep,
                SugarliciousAnalogGeometry.middleArcStroke,
                if (displayable) 0.66f else 0f,
            )
            roundSlot(
                SugarliciousAnalogGeometry.bottomCenter,
                SugarliciousAnalogGeometry.bottomArcDiameter,
                SugarliciousAnalogGeometry.bottomArcStart,
                SugarliciousAnalogGeometry.bottomArcSweep,
                SugarliciousAnalogGeometry.bottomArcStroke,
                if (displayable) 0.54f else 0f,
            )

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
            }
            fun text(value: String, px: Float, py: Float, textSize: Float, color: Int) {
                textPaint.textSize = textSize * scale
                textPaint.color = color
                drawIntoCanvas { it.nativeCanvas.drawText(value, x(px), y(py), textPaint) }
            }
            fun curvedOuterText(value: String, arc: AnalogArcGeometry, textSize: Float, color: Int) {
                textPaint.textSize = textSize * scale
                textPaint.color = color
                textPaint.textAlign = Paint.Align.LEFT
                val radius = (SugarliciousAnalogGeometry.outerDiameter / 2f - 17f) * scale
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
            fun trendIcon(centerX: Float, centerY: Float, height: Float) {
                val spec = glucoseState?.trend?.let(TrendVisuals::spec) ?: return
                val assetScale = height * scale / spec.canvasHeight
                val iconWidth = spec.canvasWidth * assetScale
                val iconHeight = spec.canvasHeight * assetScale
                val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFEB600A.toInt()
                    style = Paint.Style.FILL
                }
                drawIntoCanvas { composeCanvas ->
                    val native = composeCanvas.nativeCanvas
                    native.save()
                    native.translate(x(centerX) - iconWidth / 2f, y(centerY) - iconHeight / 2f)
                    native.scale(assetScale, assetScale)
                    TrendVectorPaths.forAsset(spec.asset).forEach { pathData ->
                        native.drawPath(androidx.core.graphics.PathParser.createPathFromPathData(pathData), arrowPaint)
                    }
                    native.restore()
                }
            }

            text("74%", 105f, 89f, 18f, 0xFFEB600A.toInt())
            text("160U", 345f, 92f, 17f, 0xFFEB600A.toInt())
            text(cob, 349f, 330f, 18f, 0xFFEB600A.toInt())
            curvedOuterText(
                "$iob · $basal",
                SugarliciousAnalogGeometry.outerLowerLeft,
                14f,
                0xFFEB600A.toInt(),
            )
            text(iob, 127f, 218f, 18f, 0xFFEB600A.toInt())
            text("IOB", 127f, 241f, 13f, Color.White.toArgb())
            text(if (displayable) glucose else "—", 310f, 218f, 18f, 0xFFEB600A.toInt())
            if (displayable) trendIcon(349f, 211f, 18f)
            text(age, 323f, 242f, 13f, Color.White.toArgb())
            text(if (displayable) glucose else "—", 225f, 310f, 34f, Color.White.toArgb())
            text(status, 225f, 342f, 11f, 0xFFEB600A.toInt())

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
