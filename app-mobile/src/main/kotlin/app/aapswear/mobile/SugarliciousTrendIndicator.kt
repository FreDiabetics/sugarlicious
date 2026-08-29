package app.aapswear.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aapswear.mobile.ui.theme.SugarliciousColors
import app.aapswear.model.Trend
import app.aapswear.model.TrendVisuals
import app.aapswear.uishared.TrendDrawableResources

/** Optional local cap used by compact complication previews without changing larger app visuals. */
internal val LocalSugarliciousTrendArrowMaxSize = staticCompositionLocalOf<Dp?> { null }

/** Canonical Sugarlicious trend visual. All phone previews use the same vector and geometry. */
@Composable
internal fun SugarliciousTrendIndicator(
    trend: Trend,
    modifier: Modifier = Modifier,
    color: Color = SugarliciousColors.TextPrimary,
    arrowSize: Dp = 25.dp,
) {
    val spec = TrendVisuals.spec(trend) ?: return
    val effectiveArrowSize = LocalSugarliciousTrendArrowMaxSize.current?.let { minOf(arrowSize, it) } ?: arrowSize
    val width = effectiveArrowSize * spec.aspectRatio
    val height = effectiveArrowSize
    Box(modifier = modifier.size(width = width, height = height), contentAlignment = Alignment.Center) {
        SugarliciousIcon(
            drawableRes = TrendDrawableResources.forAsset(spec.asset),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(width = width, height = height),
        )
    }
}
