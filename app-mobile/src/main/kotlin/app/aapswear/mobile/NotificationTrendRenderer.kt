package app.aapswear.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import app.aapswear.model.Trend
import app.aapswear.model.TrendVisuals
import kotlin.math.roundToInt

internal object NotificationTrendRenderer {
    fun render(context: Context, trend: Trend, sizePx: Int = 64, tint: Int = Color.WHITE): Bitmap? {
        val spec = TrendVisuals.spec(trend) ?: return null
        val drawable = context.getDrawable(R.drawable.ic_trend_arrow)?.mutate() ?: return null
        drawable.setTint(tint)
        val bitmap = Bitmap.createBitmap(sizePx * if (spec.arrowCount == 2) 2 else 1, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        repeat(spec.arrowCount) { index ->
            val cx = sizePx * (index + 0.5f)
            canvas.save()
            canvas.rotate(spec.rotationDegrees, cx, sizePx / 2f)
            val margin = (sizePx * 0.09f).roundToInt()
            val left = index * sizePx + margin
            drawable.setBounds(left, margin, (index + 1) * sizePx - margin, sizePx - margin)
            drawable.draw(canvas)
            canvas.restore()
        }
        return bitmap
    }
}
