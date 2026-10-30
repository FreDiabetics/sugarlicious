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
        private var markerEnabled = false
        private val geometry = TherapyRingGeometry()
        private val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
        private val bounds = RectF()

        internal fun bind(value: Float?, color: Int, showMarker: Boolean = false) {
            progress = value?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            accent = color
            markerEnabled = showMarker
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val diameter = minOf(width, height).toFloat()
            val scale =
                app.aapswear.model.TherapyIndicatorGeometry
                    .scale(diameter)
            val stroke = geometry.strokeWidthDp * scale
            if (diameter <= stroke) return
            val left = (width - diameter) / 2f + stroke / 2f
            val top = (height - diameter) / 2f + stroke / 2f
            bounds.set(left, top, left + diameter - stroke, top + diameter - stroke)
            val outline = app.aapswear.model.TherapyIndicatorGeometry.MARKER_OUTLINE_DP * scale
            val marker = markerEnabled && progress != null
            if (marker) {
                val inset = app.aapswear.model.TherapyIndicatorGeometry.MARKER_INSET_DP * scale
                bounds.inset(inset, inset)
            }
            val arcs = app.aapswear.model.progressArcSegments(progress, bounds.width() + stroke, stroke, geometry.sweepDegrees)
            paint.color = accent
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.alpha = (Color.alpha(accent) * .30f).roundToInt()
            if (!marker && arcs.trackSweep > 0f) canvas.drawArc(bounds, geometry.composeStartDegrees + arcs.trackStartOffset, arcs.trackSweep, false, paint)
            paint.alpha = Color.alpha(accent)
            if (marker) {
                canvas.drawArc(bounds, geometry.composeStartDegrees, geometry.sweepDegrees, false, paint)
            } else if (arcs.fillSweep > 0f) {
                canvas.drawArc(bounds, geometry.composeStartDegrees, arcs.fillSweep, false, paint)
            }
            if (marker) {
                val angle = Math.toRadians((geometry.composeStartDegrees + arcs.fillSweep).toDouble())
                val x = bounds.centerX() + bounds.width() / 2f * kotlin.math.cos(angle).toFloat()
                val y = bounds.centerY() + bounds.height() / 2f * kotlin.math.sin(angle).toFloat()
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x, y, stroke / 2f, paint)
                paint.color = Color.BLACK
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = outline
                canvas.drawCircle(x, y, stroke / 2f + outline / 2f, paint)
            }
        }
    }
