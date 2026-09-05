package app.aapswear.g7watch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.ScrollView
import kotlin.math.min

/**
 * Round-screen scroll chrome for Direct to Watch.
 *
 * A rectangular top/bottom fade still leaves wide cards visibly intersecting the physical round
 * display boundary: the card keeps its full width until Android clips it at the circle, which
 * produces the hard diagonal/"cut corner" wedges seen on graph and settings cards.  Use the real
 * round viewport as the fade geometry instead.  Content now disappears into the circular edge
 * before the hardware clip becomes visible, while the readable centre stays untouched.
 *
 * The mask is directional: the upper half is only faded when more content exists above, and the
 * lower half only when more content exists below.  This keeps the first/last item fully readable
 * once the user has scrolled it into its natural resting position.
 */
internal class G7EdgeFadeScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.scrollViewStyle,
) : ScrollView(context, attrs, defStyleAttr) {
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (width <= 0 || height <= 0) return

        val fadeTop = canScrollVertically(-1)
        val fadeBottom = canScrollVertically(1)
        if (!fadeTop && !fadeBottom) return

        val radius = min(width, height) / 2f
        if (radius <= 0f) return

        edgePaint.shader = RadialGradient(
            width / 2f,
            height / 2f,
            radius,
            intArrayOf(
                0x00000000,
                0x00000000,
                0x42000000,
                0xA6000000.toInt(),
                0xF2000000.toInt(),
            ),
            floatArrayOf(0f, 0.72f, 0.86f, 0.95f, 1f),
            Shader.TileMode.CLAMP,
        )

        if (fadeTop) {
            val save = canvas.save()
            canvas.clipRect(0f, 0f, width.toFloat(), height / 2f)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), edgePaint)
            canvas.restoreToCount(save)
        }
        if (fadeBottom) {
            val save = canvas.save()
            canvas.clipRect(0f, height / 2f, width.toFloat(), height.toFloat())
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), edgePaint)
            canvas.restoreToCount(save)
        }
        edgePaint.shader = null
    }
}

/**
 * Shared scroll behavior for every Direct-to-Watch surface.
 *
 * The platform's built-in vertical fading edge is deliberately disabled: combining it with the
 * circular mask double-darkens the top/bottom and recreates a rectangular band over the round
 * treatment.  The custom viewport-aware mask above is the single visual edge treatment.
 */
internal fun ScrollView.applyG7EdgeFade(): ScrollView = apply {
    isVerticalScrollBarEnabled = false
    isVerticalFadingEdgeEnabled = false
    clipToPadding = false
    overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
}
