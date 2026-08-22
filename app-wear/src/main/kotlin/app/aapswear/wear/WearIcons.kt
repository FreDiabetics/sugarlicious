package app.aapswear.wear

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.annotation.DrawableRes
import app.aapswear.model.ArgbContrast
import kotlin.math.ceil

internal fun shouldOutlineWearIcon(backgroundArgb: Int, colored: Boolean): Boolean =
    colored && ArgbContrast.isLight(backgroundArgb)

/** Shared classic-View icon renderer for the Wear app's user-configurable light surfaces. */
internal fun ImageView.renderSugarliciousWearIcon(
    @DrawableRes drawableRes: Int,
    tintArgb: Int?,
    backgroundArgb: Int,
    colored: Boolean = true,
) {
    if (shouldOutlineWearIcon(backgroundArgb, colored)) {
        imageTintList = null
        val foreground = requireNotNull(context.getDrawable(drawableRes)).mutate().apply {
            tintArgb?.let(::setTint)
        }
        val outline = requireNotNull(context.getDrawable(drawableRes)).mutate().apply {
            setTint(0xB2000000.toInt())
        }
        setImageDrawable(
            SilhouetteOutlineDrawable(
                foreground = foreground,
                outline = outline,
                widthPx = context.resources.displayMetrics.density * 0.70f,
            )
        )
    } else {
        setImageResource(drawableRes)
        imageTintList = tintArgb?.let(ColorStateList::valueOf)
    }
}

private class SilhouetteOutlineDrawable(
    private val foreground: Drawable,
    private val outline: Drawable,
    private val widthPx: Float,
) : Drawable() {
    private val offsets =
        listOf(
            -1f to 0f,
            1f to 0f,
            0f to -1f,
            0f to 1f,
            -0.7f to -0.7f,
            0.7f to -0.7f,
            -0.7f to 0.7f,
            0.7f to 0.7f,
        )

    override fun onBoundsChange(bounds: Rect) {
        val inset = ceil(widthPx).toInt().coerceAtLeast(1)
        val content = Rect(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        foreground.bounds = content
        outline.bounds = content
    }

    override fun draw(canvas: Canvas) {
        offsets.forEach { (x, y) ->
            val checkpoint = canvas.save()
            canvas.translate(x * widthPx, y * widthPx)
            outline.draw(canvas)
            canvas.restoreToCount(checkpoint)
        }
        foreground.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {
        foreground.alpha = alpha
        outline.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        foreground.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in the Android Drawable API")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = foreground.intrinsicWidth

    override fun getIntrinsicHeight(): Int = foreground.intrinsicHeight
}
