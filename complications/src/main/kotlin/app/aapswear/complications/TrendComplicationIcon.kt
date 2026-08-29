package app.aapswear.complications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.MonochromaticImage
import app.aapswear.model.Trend
import app.aapswear.model.TrendVisuals
import app.aapswear.uishared.TrendDrawableResources
import kotlin.math.roundToInt

/** Renders the exact Sugarlicious trend vector used by the phone overview. */
internal object TrendComplicationIcon {
    fun monochromaticImage(
        context: Context,
        trend: Trend,
        sizePx: Int = 72,
    ): MonochromaticImage? {
        val bitmap = render(context, trend, sizePx) ?: return null
        return MonochromaticImage.Builder(Icon.createWithBitmap(bitmap)).build()
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
