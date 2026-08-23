package app.aapswear.g7watch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import app.aapswear.g7.CgmReading
import app.aapswear.model.GlucoseGraphScale
import app.aapswear.model.RelativeGraphTimeAxis
import kotlin.math.max

@SuppressLint("DrawAllocation")
internal class G7CollectorGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 8.5f, resources.displayMetrics)
        typeface = Typeface.DEFAULT_BOLD
    }

    private var readings: List<CgmReading> = emptyList()
    private var palette = G7AppearancePalette(G7AppearanceRole.entries.associateWith { it.defaultArgb })
    private var graphHours = G7AppearanceStore.DEFAULT_GRAPH_HOURS
    private var nowEpochMs = 0L
    private var targetLowMgDl = 80.0
    private var targetHighMgDl = 160.0

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width <= 0 || view.height <= 0) return
                outline.setRoundRect(0, 0, view.width, view.height, TILE_RADIUS_DP.dp)
            }
        }
        clipToOutline = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateOutline()
    }

    fun bind(
        readings: List<CgmReading>,
        palette: G7AppearancePalette,
        graphHours: Int,
        nowEpochMs: Long = System.currentTimeMillis(),
        targetLowMgDl: Double = 80.0,
        targetHighMgDl: Double = 160.0,
    ) {
        this.readings = readings
        this.palette = palette
        this.graphHours = graphHours.takeIf { it in G7AppearanceStore.ALLOWED_GRAPH_HOURS } ?: G7AppearanceStore.DEFAULT_GRAPH_HOURS
        this.nowEpochMs = nowEpochMs
        this.targetLowMgDl = targetLowMgDl
        this.targetHighMgDl = targetHighMgDl
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val now = nowEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        val start = now - graphHours * RelativeGraphTimeAxis.HOUR_MS
        val visible = G7GraphPolicy.displayReadings(readings, start, now)
        val excursion = G7GraphPolicy.rangeExcursion(readings, targetLowMgDl, targetHighMgDl, now)

        fillPaint.color = palette.argb(G7AppearanceRole.GRAPH_BACKGROUND)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)

        val plotLeft = 7f.dp
        val plotRight = width - 31f.dp
        val plotTop = 7f.dp
        val plotBottom = height - 20f.dp
        if (plotRight <= plotLeft || plotBottom <= plotTop) return

        fun x(timestamp: Long): Float =
            plotLeft +
                ((timestamp - start).toDouble() / (now - start).coerceAtLeast(1L))
                    .coerceIn(0.0, 1.0)
                    .toFloat() * (plotRight - plotLeft)

        fun y(valueMgDl: Double): Float =
            plotBottom - GlucoseGraphScale.ratio(valueMgDl).toFloat() * (plotBottom - plotTop)

        val targetTop = y(targetHighMgDl)
        val targetBottom = y(targetLowMgDl)

        fillPaint.color = palette.argb(G7AppearanceRole.GRAPH_TARGET_AREA)
        canvas.drawRect(0f, targetTop, width.toFloat(), targetBottom, fillPaint)

        if (excursion == G7RangeExcursion.HIGH) {
            fillPaint.color = palette.argb(G7AppearanceRole.GRAPH_HIGH_AREA)
            canvas.drawRect(0f, 0f, width.toFloat(), targetTop, fillPaint)
        }
        if (excursion == G7RangeExcursion.LOW) {
            fillPaint.color = palette.argb(G7AppearanceRole.GRAPH_LOW_AREA)
            canvas.drawRect(0f, targetBottom, width.toFloat(), height.toFloat(), fillPaint)
        }

        drawGridAndAxis(canvas, start, now, plotLeft, plotRight, plotTop, plotBottom)

        linePaint.pathEffect = null
        linePaint.strokeWidth = 1f.dp
        linePaint.color = palette.argb(G7AppearanceRole.GRAPH_HIGH_LINE)
        canvas.drawLine(plotLeft, targetTop, plotRight, targetTop, linePaint)
        linePaint.color = palette.argb(G7AppearanceRole.GRAPH_LOW_LINE)
        canvas.drawLine(plotLeft, targetBottom, plotRight, targetBottom, linePaint)

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = palette.argb(G7AppearanceRole.GRAPH_HIGH_LINE)
        canvas.drawText(targetHighMgDl.toInt().toString(), width - 5f.dp, targetTop - 2f.dp, textPaint)
        textPaint.color = palette.argb(G7AppearanceRole.GRAPH_LOW_LINE)
        canvas.drawText(targetLowMgDl.toInt().toString(), width - 5f.dp, targetBottom - 2f.dp, textPaint)

        visible.forEach { reading ->
            val px = x(reading.timestampEpochMs)
            val py = y(reading.glucoseMgDl)
            val outline = 3.1f.dp
            fillPaint.color = palette.argb(G7AppearanceRole.GRAPH_DOT_OUTLINE)
            canvas.drawCircle(px, py, outline, fillPaint)
            fillPaint.color = when {
                reading.glucoseMgDl < targetLowMgDl -> palette.argb(G7AppearanceRole.GRAPH_DOT_LOW)
                reading.glucoseMgDl > targetHighMgDl -> palette.argb(G7AppearanceRole.GRAPH_DOT_HIGH)
                else -> palette.argb(G7AppearanceRole.GRAPH_DOT_IN_RANGE)
            }
            canvas.drawCircle(px, py, 2.2f.dp, fillPaint)
        }

        if (visible.isEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = palette.argb(G7AppearanceRole.GRAPH_AXIS_TEXT)
            canvas.drawText("Noch keine CGM-Historie", width / 2f, height / 2f, textPaint)
        }

        linePaint.pathEffect = null
        linePaint.strokeWidth = 1f.dp
        linePaint.color = palette.argb(G7AppearanceRole.GRAPH_NOW_MARKER)
        canvas.drawLine(plotRight, plotTop, plotRight, plotBottom, linePaint)

        drawContour(canvas)
    }

    private fun drawGridAndAxis(
        canvas: Canvas,
        start: Long,
        now: Long,
        plotLeft: Float,
        plotRight: Float,
        plotTop: Float,
        plotBottom: Float,
    ) {
        val ticks = RelativeGraphTimeAxis.ticks(start, now, now)
        linePaint.strokeWidth = 0.7f.dp
        linePaint.color = palette.argb(G7AppearanceRole.GRAPH_GRID)
        linePaint.pathEffect = DashPathEffect(floatArrayOf(2.5f.dp, 3f.dp), 0f)

        textPaint.color = palette.argb(G7AppearanceRole.GRAPH_AXIS_TEXT)
        textPaint.textAlign = Paint.Align.CENTER
        ticks.forEach { tick ->
            val fraction = ((tick.timestampEpochMs - start).toDouble() / (now - start).coerceAtLeast(1L)).coerceIn(0.0, 1.0)
            val px = plotLeft + fraction.toFloat() * (plotRight - plotLeft)
            if (tick.hoursBack != 0) canvas.drawLine(px, plotTop, px, plotBottom, linePaint)
            val align = when {
                tick.hoursBack == graphHours -> Paint.Align.LEFT
                tick.hoursBack == 0 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            textPaint.textAlign = align
            val labelX = when (align) {
                Paint.Align.LEFT -> max(2f.dp, px)
                Paint.Align.RIGHT -> minOf(width - 4f.dp, px + 27f.dp)
                else -> px
            }
            canvas.drawText(tick.label, labelX, height - 5f.dp, textPaint)
        }
        linePaint.pathEffect = null
    }

    private fun drawContour(canvas: Canvas) {
        val inset = 0.5f.dp
        linePaint.pathEffect = null
        linePaint.strokeWidth = 1f.dp
        linePaint.color = palette.argb(G7AppearanceRole.GRAPH_TILE_BORDER)
        canvas.drawRoundRect(
            inset,
            inset,
            width - inset,
            height - inset,
            TILE_RADIUS_DP.dp - inset,
            TILE_RADIUS_DP.dp - inset,
            linePaint,
        )
    }

    private val Float.dp: Float get() = this * density

    private companion object {
        const val TILE_RADIUS_DP = 20f
    }
}
