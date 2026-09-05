package app.aapswear.g7watch

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * Plain Direct-to-Watch scroll container.
 *
 * All custom edge fade, masking, scaling and roll-away transforms are intentionally disabled.
 * The previous implementations changed the appearance of graph/settings content even while it was
 * stationary, which made the effect look attached to the cards instead of to the viewport edge.
 * Keep this class as a compatibility wrapper for the existing activities, but do not modify child
 * alpha, scale, clipping or drawing in any way.
 */
internal class G7EdgeFadeScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.scrollViewStyle,
) : ScrollView(context, attrs, defStyleAttr)

/**
 * No custom scroll-edge visual effect.
 * Only hide the scrollbar; content is rendered at its normal geometry and opacity.
 */
internal fun ScrollView.applyG7EdgeFade(): ScrollView = apply {
    isVerticalScrollBarEnabled = false
    isVerticalFadingEdgeEnabled = false
    overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
}
