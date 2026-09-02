package app.aapswear.complications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Icon
import android.content.res.Configuration
import androidx.wear.watchface.complications.data.MonochromaticImage
import app.aapswear.model.Trend
import app.aapswear.model.TrendVisuals
import app.aapswear.model.GlucoseTrendSizing
import app.aapswear.model.AppearanceMode
import app.aapswear.model.TrendArrowStyle
import app.aapswear.model.TrendArrowStyleOverride
import app.aapswear.storage.TrendArrowStylePreferences
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
        val mode = if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) AppearanceMode.DARK else AppearanceMode.LIGHT
        val parent = TrendArrowStylePreferences.read(
            context.getSharedPreferences("watch_display", Context.MODE_PRIVATE), mode, Color.WHITE,
            legacyScaleKey = "trend_scale_percent",
        )
        val override = catalogId?.let { id ->
            TrendArrowStyleOverride(
                fillColor = appearance.getInt("$id.trendFill", Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE },
                outlineEnabled = if (appearance.contains("$id.trendOutlineEnabled")) appearance.getBoolean("$id.trendOutlineEnabled", false) else null,
                outlineColor = appearance.getInt("$id.trendOutlineColor", Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE },
                outlineThicknessDp = appearance.getFloat("$id.trendOutlineThickness", Float.NaN).takeUnless { it.isNaN() },
                sizePercent = if (appearance.contains("$id.trendScale")) scale else null,
                alpha = appearance.getFloat("$id.trendAlpha", Float.NaN).takeUnless { it.isNaN() },
            )
        } ?: TrendArrowStyleOverride()
        val bitmap = renderScaled(context, trend, sizePx, scale, offsetX, offsetY, override.resolve(parent)) ?: return null
        return MonochromaticImage.Builder(Icon.createWithBitmap(bitmap)).build()
    }

    /**
     * Keeps a stable canvas so every direction retains identical geometry. The percentage is
     * calibrated to the icon box supplied by third-party watch faces: 100% occupies most of the
     * available height, while 70..200% still produces a visible size change without distortion.
     */
    internal fun renderScaled(
        context: Context,
        trend: Trend,
        referenceHeightPx: Int,
        scalePercent: Int,
        offsetXPercent: Int = 0,
        offsetYPercent: Int = 0,
        style: TrendArrowStyle = TrendArrowStyle.defaults(AppearanceMode.DARK, Color.WHITE),
    ): Bitmap? {
        val spec = TrendVisuals.spec(trend) ?: return null
        val canvasHeight = referenceHeightPx * 2
        val canvasWidth = (canvasHeight * spec.aspectRatio).roundToInt().coerceAtLeast(1)
        val targetHeight = (canvasHeight * glyphFillFraction(scalePercent)).roundToInt().coerceAtLeast(1)
        val targetWidth = (targetHeight * spec.aspectRatio).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        val drawable = context.getDrawable(TrendDrawableResources.forAsset(spec.asset))?.mutate() ?: return null
        val render = style.copy(sizePercent = scalePercent, fillColor = Color.WHITE, outlineColor = Color.WHITE).renderSpec()
        drawable.setTint(render.fillColor)
        val left = ((canvasWidth - targetWidth) / 2f + referenceHeightPx * offsetXPercent.coerceIn(-50, 50) / 100f).roundToInt()
        val top = ((canvasHeight - targetHeight) / 2f + referenceHeightPx * offsetYPercent.coerceIn(-50, 50) / 100f).roundToInt()
        drawable.setBounds(left, top, left + targetWidth, top + targetHeight)
        val canvas = Canvas(bitmap)
        if (render.outlineThicknessDp > 0f) {
            val outline = context.getDrawable(TrendDrawableResources.forAsset(spec.asset))?.mutate()
            outline?.setTint(render.outlineColor)
            val px = render.outlineThicknessDp * context.resources.displayMetrics.density
            listOf(-px to 0f, px to 0f, 0f to -px, 0f to px).forEach { (x, y) ->
                val save = canvas.save(); canvas.translate(x, y); outline?.bounds = drawable.bounds; outline?.draw(canvas); canvas.restoreToCount(save)
            }
        }
        drawable.draw(canvas)
        return bitmap
    }

    internal fun glyphFillFraction(scalePercent: Int): Float {
        val normalized = (scalePercent.coerceIn(
            GlucoseTrendSizing.MIN_SCALE_PERCENT,
            GlucoseTrendSizing.MAX_SCALE_PERCENT,
        ) - GlucoseTrendSizing.MIN_SCALE_PERCENT).toFloat() /
            (GlucoseTrendSizing.MAX_SCALE_PERCENT - GlucoseTrendSizing.MIN_SCALE_PERCENT)
        return 0.70f + normalized * 0.30f
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
