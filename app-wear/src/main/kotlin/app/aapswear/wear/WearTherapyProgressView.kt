package app.aapswear.wear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import app.aapswear.model.TherapyRingGeometry
import kotlin.math.roundToInt

/** Native overview ring using the same geometry as Mobile and the Wear therapy tile. */
class WearTherapyProgressView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        internal var progress: Float? = null
            private set
        private var accent = Color.WHITE
        private val geometry = TherapyRingGeometry()
        private val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
        private val bounds = RectF()

        internal fun bind(value: Float?, color: Int) {
            progress = value?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            accent = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val diameter = minOf(width, height).toFloat()
            val stroke = geometry.strokeWidthDp * resources.displayMetrics.density
            if (diameter <= stroke) return
            val left = (width - diameter) / 2f + stroke / 2f
            val top = (height - diameter) / 2f + stroke / 2f
            bounds.set(left, top, left + diameter - stroke, top + diameter - stroke)
            paint.color = accent
            paint.strokeWidth = stroke
            paint.alpha = (Color.alpha(accent) * .30f).roundToInt()
            canvas.drawArc(bounds, geometry.composeStartDegrees, geometry.sweepDegrees, false, paint)
            progress?.takeIf { it > 0f }?.let {
                paint.alpha = Color.alpha(accent)
                canvas.drawArc(bounds, geometry.composeStartDegrees, geometry.sweepDegrees * it, false, paint)
            }
        }
    }
