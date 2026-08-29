package app.aapswear.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import app.aapswear.model.Trend
import app.aapswear.model.TrendVisuals
import app.aapswear.uishared.TrendDrawableResources
import kotlin.math.roundToInt

internal object NotificationTrendRenderer {
    fun render(context: Context, trend: Trend, sizePx: Int = 64, tint: Int = Color.WHITE): Bitmap? {
        val spec = TrendVisuals.spec(trend) ?: return null
        val drawable = context.getDrawable(TrendDrawableResources.forAsset(spec.asset))?.mutate() ?: return null
        drawable.setTint(tint)
        val width = (sizePx * spec.aspectRatio).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, sizePx)
        drawable.draw(canvas)
        return bitmap
    }
}
