package app.aapswear.g7watch

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * Plain Direct-to-Watch scroll container.
 *
 * Custom edge fade, masks, scaling and roll-away transforms stay disabled until a viewport-bound
 * implementation has been hardware-validated. A drawing overlay makes the effect appear attached
 * to stationary graph/cards and causes dark semicircles on the round display.
 */
internal class G7EdgeFadeScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.scrollViewStyle,
) : ScrollView(context, attrs, defStyleAttr)

/** Preserve normal content geometry and opacity; only hide the scrollbar. */
internal fun ScrollView.applyG7EdgeFade(): ScrollView = apply {
    isVerticalScrollBarEnabled = false
    isVerticalFadingEdgeEnabled = false
    overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
}
