package app.aapswear.g7watch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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
import kotlin.math.min

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

        val plotLeft = 16f.dp
        val plotRight = width - 31f.dp
        val plotTop = 7f.dp
        val plotBottom = height - 20f.dp
        if (plotRight <= plotLeft || plotBottom <= plotTop) return

        fun x(timestamp: Long): Float = G7GraphLayout.timeX(timestamp, start, now, plotLeft, plotRight)

        fun y(valueMgDl: Double): Float =
            plotBottom - GlucoseGraphScale.ratio(valueMgDl).toFloat() * (plotBottom - plotTop)

        val targetTop = y(targetHighMgDl)
        val targetBottom = y(targetLowMgDl)

        fillPaint.color = palette.argb(G7AppearanceRole.GRAPH_TARGET_AREA)
        canvas.drawRect(plotLeft, targetTop, plotRight, targetBottom, fillPaint)

        if (excursion == G7RangeExcursion.HIGH) {
            fillPaint.color = palette.argb(G7AppearanceRole.GRAPH_HIGH_AREA)
            canvas.drawRect(plotLeft, plotTop, plotRight, targetTop, fillPaint)
        }
        if (excursion == G7RangeExcursion.LOW) {
            fillPaint.color = palette.argb(G7AppearanceRole.GRAPH_LOW_AREA)
            canvas.drawRect(plotLeft, targetBottom, plotRight, plotBottom, fillPaint)
        }

        val nowLineX = G7GraphLayout.nowLineX(
            dividerX = plotRight,
            cgmOuterRadius = CGM_OUTER_RADIUS_DP.dp,
            safetyGap = NOW_LANE_SAFETY_GAP_DP.dp,
        )
        drawGridAndAxis(canvas, start, now, plotLeft, plotRight, nowLineX, plotTop, plotBottom)

        linePaint.pathEffect = null
        linePaint.strokeWidth = 1f.dp
        linePaint.color = palette.argb(G7AppearanceRole.GRAPH_HIGH_LINE)
        canvas.drawLine(plotLeft, targetTop, plotRight, targetTop, linePaint)
        linePaint.color = palette.argb(G7AppearanceRole.GRAPH_LOW_LINE)
        canvas.drawLine(plotLeft, targetBottom, plotRight, targetBottom, linePaint)

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = Color.WHITE
        textPaint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(
            targetHighMgDl.toInt().toString(),
            width - 5f.dp,
            G7GraphLayout.highLabelBaseline(targetTop, textPaint.fontMetrics, SCALE_LABEL_GAP_DP.dp),
            textPaint,
        )
        canvas.drawText(
            targetLowMgDl.toInt().toString(),
            width - 5f.dp,
            G7GraphLayout.lowLabelBaseline(targetBottom, textPaint.fontMetrics, SCALE_LABEL_GAP_DP.dp),
            textPaint,
        )

        linePaint.pathEffect = null
        linePaint.strokeWidth = 0.7f.dp
        linePaint.color = palette.argb(G7AppearanceRole.GRAPH_GRID)
        canvas.drawLine(plotRight, plotTop, plotRight, plotBottom, linePaint)

        val latestTimestamp = visible.maxOfOrNull { it.timestampEpochMs }
        visible.forEach { reading ->
            val px = if (reading.timestampEpochMs == latestTimestamp) nowLineX else G7GraphLayout.realCgmX(x(reading.timestampEpochMs), nowLineX)
            val py = y(reading.glucoseMgDl)
            fillPaint.color = palette.argb(G7AppearanceRole.GRAPH_DOT_OUTLINE)
            canvas.drawCircle(px, py, CGM_OUTER_RADIUS_DP.dp, fillPaint)
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

        drawContour(canvas)
    }

    private fun drawGridAndAxis(
        canvas: Canvas,
        start: Long,
        now: Long,
        plotLeft: Float,
        plotRight: Float,
        nowLineX: Float,
        plotTop: Float,
        plotBottom: Float,
    ) {
        val ticks = RelativeGraphTimeAxis.ticks(start, now, now)
        linePaint.strokeWidth = 0.7f.dp
        linePaint.color = palette.argb(G7AppearanceRole.GRAPH_GRID)
        linePaint.pathEffect = null

        textPaint.color = palette.argb(G7AppearanceRole.GRAPH_AXIS_TEXT)
        textPaint.textAlign = Paint.Align.CENTER
        ticks.forEach { tick ->
            val fraction = ((tick.timestampEpochMs - start).toDouble() / (now - start).coerceAtLeast(1L)).coerceIn(0.0, 1.0)
            val px = if (tick.hoursBack == 0) nowLineX else plotLeft + fraction.toFloat() * (plotRight - plotLeft)
            canvas.drawLine(px, plotBottom + 2f.dp, px, plotBottom + 6f.dp, linePaint)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(tick.label, px, height - 5f.dp, textPaint)
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
        const val CGM_OUTER_RADIUS_DP = 3.1f
        const val NOW_LANE_SAFETY_GAP_DP = 1f
        const val SCALE_LABEL_GAP_DP = 1.5f
    }
}

internal object G7GraphLayout {
    fun timeX(timestamp: Long, start: Long, now: Long, left: Float, right: Float): Float =
        left +
            ((timestamp - start).toDouble() / (now - start).coerceAtLeast(1L))
                .coerceIn(0.0, 1.0)
                .toFloat() * (right - left)

    fun nowLineX(dividerX: Float, cgmOuterRadius: Float, safetyGap: Float): Float =
        dividerX - cgmOuterRadius - safetyGap

    fun realCgmX(mappedX: Float, nowLineX: Float): Float = min(mappedX, nowLineX)

    fun predictionX(mappedX: Float, dividerX: Float, outerRadius: Float, safetyGap: Float): Float =
        maxOf(mappedX, dividerX + outerRadius + safetyGap)

    fun highLabelBaseline(lineY: Float, metrics: Paint.FontMetrics, gap: Float): Float =
        lineY - gap - metrics.descent

    fun lowLabelBaseline(lineY: Float, metrics: Paint.FontMetrics, gap: Float): Float =
        lineY + gap - metrics.ascent
}
