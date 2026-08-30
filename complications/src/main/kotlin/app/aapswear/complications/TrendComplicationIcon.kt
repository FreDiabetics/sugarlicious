package app.aapswear.complications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.MonochromaticImage
import app.aapswear.model.Trend
import app.aapswear.model.TrendVisuals
import app.aapswear.model.GlucoseTrendSizing
import app.aapswear.uishared.TrendDrawableResources
import kotlin.math.roundToInt

/** Renders the exact Sugarlicious trend vector used by the phone overview. */
internal object TrendComplicationIcon {
    fun monochromaticImage(
        context: Context,
        trend: Trend,
        sizePx: Int = 72,
    ): MonochromaticImage? {
        val scale = context.getSharedPreferences("watch_display", Context.MODE_PRIVATE)
            .getInt("trend_scale_percent", GlucoseTrendSizing.DEFAULT_SCALE_PERCENT)
            .coerceIn(GlucoseTrendSizing.MIN_SCALE_PERCENT, GlucoseTrendSizing.MAX_SCALE_PERCENT)
        val bitmap = renderScaled(context, trend, sizePx, scale) ?: return null
        return MonochromaticImage.Builder(Icon.createWithBitmap(bitmap)).build()
    }

    /**
     * Keeps a stable 200% canvas and scales the glyph uniformly inside it. Wear OS therefore
     * cannot squeeze wide double arrows into a square icon box and 70..200% remains visible.
     */
    internal fun renderScaled(context: Context, trend: Trend, referenceHeightPx: Int, scalePercent: Int): Bitmap? {
        val spec = TrendVisuals.spec(trend) ?: return null
        val canvasHeight = referenceHeightPx * 2
        val canvasWidth = (canvasHeight * spec.aspectRatio).roundToInt().coerceAtLeast(1)
        val targetHeight = (referenceHeightPx * GlucoseTrendSizing.scaleFactor(scalePercent)).roundToInt().coerceAtLeast(1)
        val targetWidth = (targetHeight * spec.aspectRatio).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        val drawable = context.getDrawable(TrendDrawableResources.forAsset(spec.asset))?.mutate() ?: return null
        drawable.setTint(Color.WHITE)
        val left = (canvasWidth - targetWidth) / 2
        val top = (canvasHeight - targetHeight) / 2
        drawable.setBounds(left, top, left + targetWidth, top + targetHeight)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    fun render(
        context: Context,
        trend: Trend,
        sizePx: Int,
    ): Bitmap? {
        val spec = TrendVisuals.spec(trend) ?: return null
        val drawable = context.getDrawable(TrendDrawableResources.forAsset(spec.asset))?.mutate() ?: return null
        drawable.setTint(Color.WHITE)
        val width = (sizePx * spec.aspectRatio).roundToInt().coerceAtLeast(1)
        val base = Bitmap.createBitmap(width, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(base)
        drawable.setBounds(0, 0, width, sizePx)
        drawable.draw(canvas)
        return base
    }
}
