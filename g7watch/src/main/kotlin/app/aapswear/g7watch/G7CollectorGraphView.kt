package app.aapswear.g7watch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.model.CgmQuality
import app.aapswear.model.CgmThresholds
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GraphTimeWindow
import app.aapswear.model.RelativeGraphTimeAxis
import app.aapswear.uishared.SharedWearCgmGraphInput
import app.aapswear.uishared.SharedWearCgmGraphPalette
import app.aapswear.uishared.SharedWearCgmGraphRenderer
import app.aapswear.uishared.SharedWearCgmGraphStyle

@SuppressLint("DrawAllocation")
internal class G7CollectorGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private var readings: List<CgmReading> = emptyList()
    private var palette = G7AppearancePalette(G7AppearanceRole.entries.associateWith { it.defaultArgb })
    private var graphHours = G7AppearanceStore.DEFAULT_GRAPH_HOURS
    private var nowEpochMs = 0L
    private var targetLowMgDl = 80.0
    private var targetHighMgDl = 160.0

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width > 0 && view.height > 0) {
                    outline.setRoundRect(0, 0, view.width, view.height, TILE_RADIUS_DP * density)
                }
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
        val now = nowEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        val window = GraphTimeWindow.live(now, graphHours * RelativeGraphTimeAxis.HOUR_MS)
        val thresholds = CgmThresholds(
            veryHighMgDl = maxOf(CgmThresholds.DEFAULT_VERY_HIGH_MG_DL, targetHighMgDl + 1.0),
            highMgDl = targetHighMgDl,
            lowMgDl = targetLowMgDl,
            veryLowMgDl = minOf(CgmThresholds.DEFAULT_VERY_LOW_MG_DL, targetLowMgDl - 1.0),
        )
        SharedWearCgmGraphRenderer.render(
            canvas,
            width,
            height,
            density,
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics),
            SharedWearCgmGraphInput(
                history = readings.map { it.toGraphSample() },
                timeWindow = window,
                nowEpochMs = now,
                thresholds = thresholds,
                palette = palette.toSharedPalette(),
                style = SharedWearCgmGraphStyle(cornerRadiusDp = TILE_RADIUS_DP),
            ),
        )
    }

    private fun CgmReading.toGraphSample() = GlucoseSample(
        valueMgDl = glucoseMgDl,
        measuredAtEpochMs = timestampEpochMs,
        source = source,
        sensorId = sensorId,
        sessionId = sessionId,
        sequenceNumber = sequenceNumber,
        receivedAtEpochMs = receivedAtEpochMs,
        quality = when (status) {
            CgmReadingStatus.VALID -> CgmQuality.VALID
            CgmReadingStatus.SENSOR_ERROR -> CgmQuality.SENSOR_ERROR
            CgmReadingStatus.INVALID -> CgmQuality.INVALID
        },
    )

    private fun G7AppearancePalette.toSharedPalette() = SharedWearCgmGraphPalette(
        background = argb(G7AppearanceRole.GRAPH_BACKGROUND),
        targetArea = argb(G7AppearanceRole.GRAPH_TARGET_AREA),
        highArea = argb(G7AppearanceRole.GRAPH_HIGH_AREA),
        lowArea = argb(G7AppearanceRole.GRAPH_LOW_AREA),
        highLine = argb(G7AppearanceRole.GRAPH_HIGH_LINE),
        lowLine = argb(G7AppearanceRole.GRAPH_LOW_LINE),
        dotHigh = argb(G7AppearanceRole.GRAPH_DOT_HIGH),
        dotInRange = argb(G7AppearanceRole.GRAPH_DOT_IN_RANGE),
        dotLow = argb(G7AppearanceRole.GRAPH_DOT_LOW),
        dotOutline = argb(G7AppearanceRole.GRAPH_DOT_OUTLINE),
        axisText = argb(G7AppearanceRole.GRAPH_AXIS_TEXT),
        axisTick = argb(G7AppearanceRole.GRAPH_GRID),
        nowLine = argb(G7AppearanceRole.GRAPH_GRID),
        border = argb(G7AppearanceRole.GRAPH_TILE_BORDER),
        predictionIob = argb(G7AppearanceRole.GRAPH_PREDICTION),
        predictionCob = argb(G7AppearanceRole.GRAPH_PREDICTION),
        predictionUam = argb(G7AppearanceRole.GRAPH_PREDICTION),
        predictionZeroTemp = argb(G7AppearanceRole.GRAPH_PREDICTION),
    )

    private companion object {
        const val TILE_RADIUS_DP = 20f
    }
}

/** Compatibility helpers kept for the older pure geometry tests. New rendering uses ui-shared. */
internal object G7GraphLayout {
    fun timeX(timestamp: Long, start: Long, now: Long, left: Float, right: Float): Float =
        left + ((timestamp - start).toDouble() / (now - start).coerceAtLeast(1L)).coerceIn(0.0, 1.0).toFloat() * (right - left)

    fun predictionX(mappedX: Float, dividerX: Float, outerRadius: Float, safetyGap: Float): Float =
        maxOf(mappedX, dividerX + outerRadius + safetyGap)

    fun highLabelBaseline(lineY: Float, metrics: Paint.FontMetrics, gap: Float): Float = lineY - gap - metrics.descent

    fun lowLabelBaseline(lineY: Float, metrics: Paint.FontMetrics, gap: Float): Float = lineY + gap - metrics.ascent
}
