package app.sugarlicious.mobile

import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils

internal object FloatingSurfaceOutline {
    const val WIDTH_DP = .5f

    fun color(background: Int): Int = if (ColorUtils.calculateLuminance(background) > .5) 0x24000000 else 0x24FFFFFF
}

internal fun Modifier.floatingSurfaceOutline(background: Color, shape: Shape): Modifier =
    border(FloatingSurfaceOutline.WIDTH_DP.dp, Color(FloatingSurfaceOutline.color(background.toArgb())), shape)
