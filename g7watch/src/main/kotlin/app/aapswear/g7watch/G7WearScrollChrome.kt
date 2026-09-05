package app.aapswear.g7watch

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import kotlin.math.abs

/**
 * Round-screen scroll chrome for Direct to Watch.
 *
 * Do not draw a black/alpha overlay across the viewport. Those approaches produced the visible
 * semicircles and still allowed wide cards to hit the physical round display edge.
 *
 * Instead, top-level content items are transformed as they approach the upper/lower round edge:
 * they become slightly narrower and fade out. This gives the intended Wear/One-UI-like roll-away
 * behaviour while keeping the centre of the screen untouched and fully readable.
 */
internal class G7EdgeFadeScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.scrollViewStyle,
) : ScrollView(context, attrs, defStyleAttr) {

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        applyRoundEdgeTransforms()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { applyRoundEdgeTransforms() }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        post { applyRoundEdgeTransforms() }
    }

    private fun applyRoundEdgeTransforms() {
        if (height <= 0 || childCount == 0) return
        val content = getChildAt(0) as? ViewGroup ?: return
        val viewportCenterY = height / 2f
        val radius = height / 2f
        if (radius <= 0f) return

        for (i in 0 until content.childCount) {
            val item = content.getChildAt(i)
            transformItem(item, viewportCenterY, radius)
        }
    }

    private fun transformItem(item: View, viewportCenterY: Float, radius: Float) {
        val itemCenterY = item.top - scrollY + item.height / 2f
        val distance = abs(itemCenterY - viewportCenterY)
        val normalized = (distance / radius).coerceIn(0f, 1.25f)

        // Centre region remains geometrically untouched.
        val edgeProgress = ((normalized - EDGE_START) / (1f - EDGE_START)).coerceIn(0f, 1f)
        val scaleX = lerp(1f, MIN_SCALE_X, edgeProgress)
        val scaleY = lerp(1f, MIN_SCALE_Y, edgeProgress)
        val alpha = lerp(1f, MIN_ALPHA, edgeProgress)

        item.pivotX = item.width / 2f
        item.pivotY = item.height / 2f
        item.scaleX = scaleX
        item.scaleY = scaleY
        item.alpha = alpha

        // Keep touch targets disabled only once an item is essentially off the visible surface.
        item.isEnabled = alpha > 0.12f
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction

    private companion object {
        // Start only near the curved edge; centred cards remain 100% unchanged.
        const val EDGE_START = 0.62f
        const val MIN_SCALE_X = 0.72f
        const val MIN_SCALE_Y = 0.90f
        const val MIN_ALPHA = 0.04f
    }
}

/** Shared scroll behavior for every Direct-to-Watch surface. */
internal fun ScrollView.applyG7EdgeFade(): ScrollView = apply {
    isVerticalScrollBarEnabled = false
    isVerticalFadingEdgeEnabled = false
    clipToPadding = false
    overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
}
