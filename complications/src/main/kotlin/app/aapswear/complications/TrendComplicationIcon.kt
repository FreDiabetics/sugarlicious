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
        catalogId: Int? = null,
    ): MonochromaticImage? {
        val systemScale = context.getSharedPreferences("watch_display", Context.MODE_PRIVATE)
            .getInt("trend_scale_percent", GlucoseTrendSizing.DEFAULT_SCALE_PERCENT)
            .coerceIn(GlucoseTrendSizing.MIN_SCALE_PERCENT, GlucoseTrendSizing.MAX_SCALE_PERCENT)
        val appearance = context.getSharedPreferences("complication_appearance", Context.MODE_PRIVATE)
        val scale = catalogId?.let { id ->
            appearance.getInt("$id.trendScale", systemScale)
        } ?: systemScale
        val offsetX = catalogId?.let { appearance.getInt("$it.trendX", 0) } ?: 0
        val offsetY = catalogId?.let { appearance.getInt("$it.trendY", 0) } ?: 0
        val bitmap = renderScaled(context, trend, sizePx, scale, offsetX, offsetY) ?: return null
        return MonochromaticImage.Builder(Icon.createWithBitmap(bitmap)).build()
    }

    /**
     * Keeps a stable 200% canvas and scales the glyph uniformly inside it. Wear OS therefore
     * cannot squeeze wide double arrows into a square icon box and 70..200% remains visible.
     */
    internal fun renderScaled(
        context: Context,
        trend: Trend,
        referenceHeightPx: Int,
        scalePercent: Int,
        offsetXPercent: Int = 0,
        offsetYPercent: Int = 0,
    ): Bitmap? {
        val spec = TrendVisuals.spec(trend) ?: return null
        val canvasHeight = referenceHeightPx * 2
        val canvasWidth = (canvasHeight * spec.aspectRatio).roundToInt().coerceAtLeast(1)
        val targetHeight = (referenceHeightPx * GlucoseTrendSizing.scaleFactor(scalePercent)).roundToInt().coerceAtLeast(1)
        val targetWidth = (targetHeight * spec.aspectRatio).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        val drawable = context.getDrawable(TrendDrawableResources.forAsset(spec.asset))?.mutate() ?: return null
        drawable.setTint(Color.WHITE)
        val left = ((canvasWidth - targetWidth) / 2f + referenceHeightPx * offsetXPercent.coerceIn(-50, 50) / 100f).roundToInt()
        val top = ((canvasHeight - targetHeight) / 2f + referenceHeightPx * offsetYPercent.coerceIn(-50, 50) / 100f).roundToInt()
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
