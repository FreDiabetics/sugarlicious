package app.aapswear.g7watch

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * Scroll surface with a deterministic One UI Watch-style edge fade.
 *
 * Android's built-in fading edge varies considerably between Wear builds. The explicit overlay
 * keeps content readable in the centre while making cards and text visually roll into the round
 * screen boundary. It is only drawn in a direction in which more content exists.
 */
internal class G7EdgeFadeScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.scrollViewStyle,
) : ScrollView(context, attrs, defStyleAttr) {
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeLengthPx: Float
        get() = 46f * resources.displayMetrics.density

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val length = edgeLengthPx.coerceAtMost(height / 3f)
        if (length <= 0f) return
        if (canScrollVertically(-1)) {
            edgePaint.shader = LinearGradient(0f, 0f, 0f, length, 0xE6000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width.toFloat(), length, edgePaint)
        }
        if (canScrollVertically(1)) {
            edgePaint.shader = LinearGradient(0f, height - length, 0f, height.toFloat(), 0x00000000, 0xE6000000.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, height - length, width.toFloat(), height.toFloat(), edgePaint)
        }
        edgePaint.shader = null
    }
}

/** Soft round-screen edge treatment shared by all Direct-to-Watch scroll surfaces. */
internal fun ScrollView.applyG7EdgeFade(): ScrollView = apply {
    isVerticalScrollBarEnabled = false
    isVerticalFadingEdgeEnabled = true
    setFadingEdgeLength((46f * resources.displayMetrics.density).toInt())
    clipToPadding = false
    overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
}
