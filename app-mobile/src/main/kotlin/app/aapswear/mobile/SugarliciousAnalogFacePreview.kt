package app.aapswear.mobile

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import app.aapswear.model.TherapyDisplayState
import kotlin.math.roundToInt

internal data class AnalogRectGeometry(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

internal data class AnalogPointGeometry(
    val x: Float,
    val y: Float,
)

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
    val graph = fromWfsRect(51.8748f, 54.9999f, 346.2504f, 121.3336f)

    // Transparent artwork uses the entire field between the ring and central complications.
    val graphContent = AnalogRectGeometry(129f, 103f, 254f, 90f)
    val middleLeft = fromWfsRect(73f, 171f, 108.3334f, 108.3334f)
    val middleRight = fromWfsRect(269f, 171f, 108.3334f, 108.3334f)
    val bottomCenter = fromWfsRect(158.9996f, 247f, 132.0008f, 130.9996f)
    val middleLeftText = within(middleLeft, 8f, 60f, 107f, 38f)
    val middleLeftTitle = within(middleLeft, 8f, 20f, 107f, 40f)
    val middleRightText = within(middleRight, 8f, 60f, 107f, 38f)
    val middleRightTitle = within(middleRight, 8f, 20f, 107f, 40f)
    val bottomText = AnalogRectGeometry(188f, 330f, 136f, 54f)

    const val outerCenter = 256f
    val outerTextDiameter = fromWfsValue(376f - 33f)
    val outerProgressDiameter = fromWfsValue(359f - 15f)
    val outerStroke = fromWfsValue(15f)
    val outerUpperLeft = AnalogArcGeometry(285f, 333f, true)
    val outerUpperRight = AnalogArcGeometry(15f, 63f, true)
    val outerLowerRight = AnalogArcGeometry(103f, 151f, true)
    val outerLowerLeft = AnalogArcGeometry(195f, 243f, true)

    val bottomArcDiameter = fromWfsValue(120f - 10f)
    const val bottomArcStart = 220f
    const val bottomArcSweep = 280f
    val bottomArcStroke = fromWfsValue(10f)

    private fun fromWfsValue(value: Float): Float = (value * WFS_TO_WFF_SCALE).roundToInt().toFloat()

    private fun fromWfsRect(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ) = AnalogRectGeometry(
        x = fromWfsValue(x),
        y = fromWfsValue(y),
        width = fromWfsValue(width),
        height = fromWfsValue(height),
    )

    private fun within(
        parent: AnalogRectGeometry,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ) = AnalogRectGeometry(
        x = parent.x + fromWfsValue(x),
        y = parent.y + fromWfsValue(y),
        width = fromWfsValue(width),
        height = fromWfsValue(height),
    )

    fun mirrorHorizontally(rect: AnalogRectGeometry): AnalogRectGeometry = rect.copy(x = CANVAS - rect.x - rect.width)

    fun centerOf(rect: AnalogRectGeometry): AnalogPointGeometry = AnalogPointGeometry(rect.x + rect.width / 2f, rect.y + rect.height / 2f)
}

/** Native-rendered template: illustrative provider preview data, never live glucose. */
@Composable
internal fun SugarliciousAnalogFacePreview(
    @Suppress("UNUSED_PARAMETER") state: TherapyDisplayState?,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(R.drawable.apex_template_preview),
        contentDescription = "ApeX – gespeicherte Darstellungsvorlage, keine Live-Messwerte",
        modifier = modifier,
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
    )
}
