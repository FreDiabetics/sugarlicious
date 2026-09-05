package app.aapswear.g7watch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.ScrollView
import kotlin.math.min

/**
 * Round-screen scroll chrome for Direct to Watch.
 *
 * The edge treatment must fade the scrolled content itself. Drawing a dark radial overlay on top
 * of the UI produces the large black semicircles seen on hardware. Instead, the children are drawn
 * into a temporary layer and a radial alpha mask is applied with DST_IN. The centre stays fully
 * opaque while content close to the physical round edge fades to transparent before the hardware
 * clip becomes visible.
 *
 * The top and bottom halves are applied only when content can actually scroll in that direction, so
 * the first/last resting item remains fully readable. No black overlay is ever drawn on top of the
 * graph or settings cards.
 */
internal class G7EdgeFadeScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.scrollViewStyle,
) : ScrollView(context, attrs, defStyleAttr) {
    private val edgeMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) {
            super.dispatchDraw(canvas)
            return
        }

        val fadeTop = canScrollVertically(-1)
        val fadeBottom = canScrollVertically(1)
        if (!fadeTop && !fadeBottom) {
            super.dispatchDraw(canvas)
            return
        }

        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)

        val radius = min(width, height) / 2f
        if (radius > 0f) {
            edgeMaskPaint.shader = RadialGradient(
                width / 2f,
                height / 2f,
                radius,
                intArrayOf(
                    0xFFFFFFFF.toInt(),
                    0xFFFFFFFF.toInt(),
                    0xE6FFFFFF.toInt(),
                    0x99FFFFFF.toInt(),
                    0x00FFFFFF,
                ),
                floatArrayOf(0f, 0.74f, 0.86f, 0.95f, 1f),
                Shader.TileMode.CLAMP,
            )

            if (fadeTop) {
                val save = canvas.save()
                canvas.clipRect(0f, 0f, width.toFloat(), height / 2f)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), edgeMaskPaint)
                canvas.restoreToCount(save)
            }
            if (fadeBottom) {
                val save = canvas.save()
                canvas.clipRect(0f, height / 2f, width.toFloat(), height.toFloat())
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), edgeMaskPaint)
                canvas.restoreToCount(save)
            }
            edgeMaskPaint.shader = null
        }

        canvas.restoreToCount(layer)
    }
}

/** Shared scroll behavior for every Direct-to-Watch surface. */
internal fun ScrollView.applyG7EdgeFade(): ScrollView = apply {
    isVerticalScrollBarEnabled = false
    isVerticalFadingEdgeEnabled = false
    clipToPadding = false
    overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
}
