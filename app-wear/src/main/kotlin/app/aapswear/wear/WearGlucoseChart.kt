package app.aapswear.wear

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
import app.aapswear.model.CgmGraphPolicy
import app.aapswear.model.CgmRangeClass
import app.aapswear.model.CgmThresholds
import app.aapswear.model.CgmQuality
import app.aapswear.model.GlucoseGraphScale
import app.aapswear.model.GlucosePrediction
import app.aapswear.model.GlucoseSample
import app.aapswear.model.PredictionKind
import app.aapswear.model.RangeExcursion
import app.aapswear.model.RelativeGraphTimeAxis
import app.aapswear.model.TherapyDisplayState
import app.aapswear.protocol.WatchGraphColors
import app.aapswear.protocol.WatchGraphStyle
import app.aapswear.storage.PredictionDisplayTimeline
import kotlin.math.max

@SuppressLint("DrawAllocation")
class WearGlucoseChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val emptyTextPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize =
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    10f,
                    resources.displayMetrics,
                )
            textAlign = Paint.Align.CENTER
        }
    private val targetLabelPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize =
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    10f,
                    resources.displayMetrics,
                )
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.DEFAULT_BOLD
        }
    private val axisLabelPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize =
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    7.5f,
                    resources.displayMetrics,
                )
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

    private var state: TherapyDisplayState? = null
    private var durationHours: Int = 3
    private var showPredictions: Boolean = false
    private var colors: WatchGraphColors = WatchGraphColors()
    private var graphStyle: WatchGraphStyle = WatchGraphStyle()
    private var thresholds: CgmThresholds = CgmThresholds.DEFAULT
    private var stateSignature: List<Any?>? = null

    init {
        outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (view.width <= 0 || view.height <= 0) return
                    outline.setRoundRect(
                        0,
                        0,
                        view.width,
                        view.height,
                        TILE_RADIUS_DP * density,
                    )
                }
            }
        clipToOutline = true
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateOutline()
    }

    fun bind(
        newState: TherapyDisplayState?,
        graphHours: Int,
        showPredictions: Boolean,
        colors: WatchGraphColors,
        style: WatchGraphStyle,
        thresholds: CgmThresholds = CgmThresholds.DEFAULT,
    ) {
        val resolvedDuration =
            graphHours
                .takeIf { it in WearDisplayPreferences.allowedGraphHours }
                ?: 3
        val newStateSignature = wearChartStateSignature(newState)
        if (
            stateSignature == newStateSignature &&
            durationHours == resolvedDuration &&
            this.showPredictions == showPredictions &&
            this.colors == colors &&
            graphStyle == style && this.thresholds == thresholds
        ) {
            return
        }

        state = newState
        stateSignature = newStateSignature
        durationHours = resolvedDuration
        this.showPredictions = showPredictions
        this.colors = colors
        graphStyle = style
        this.thresholds = thresholds
        emptyTextPaint.color = colors.divider
        targetLabelPaint.color = colors.divider
        axisLabelPaint.color = colors.divider
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewRight = width.toFloat()
        val viewBottom = height.toFloat()
        if (viewRight <= 0f || viewBottom <= 0f) return

        val tileRadius = TILE_RADIUS_DP.dp
        val now = System.currentTimeMillis()
        val predictions =
            if (showPredictions) {
                PredictionDisplayTimeline.anchor(
                    state?.glucosePredictions.orEmpty(),
                    now,
                )
            } else {
                emptyList()
            }
        val predictionEnd =
            predictions
                .flatMap { it.samples }
                .maxOfOrNull { it.measuredAtEpochMs }
                ?: now
        val timeWindow =
            wearChartTimeWindow(
                timelineNow = now,
                predictionEnd = predictionEnd,
                durationHours = durationHours,
                showPredictions = showPredictions,
            )
        val start = timeWindow.start
        val end = timeWindow.endInclusive

        val history =
            buildList {
                addAll(state?.glucoseHistory.orEmpty())
                state?.glucose?.let {
                    add(
                        GlucoseSample(
                            valueMgDl = it.valueMgDl,
                            measuredAtEpochMs = it.measuredAtEpochMs,
                            source = state?.source ?: it.source,
                            sensorId = it.sensorId,
                            sessionId = it.sessionId,
                            sequenceNumber = it.sequenceNumber,
                            receivedAtEpochMs = it.receivedAtEpochMs,
                            quality = it.quality,
                        ),
                    )
                }
            }
                .filter {
                    it.quality == CgmQuality.VALID &&
                        it.valueMgDl.isFinite() &&
                        it.valueMgDl in 20.0..1000.0 &&
                        it.measuredAtEpochMs in start..end
                }
                .associateBy { it.measuredAtEpochMs }
                .values
                .sortedBy { it.measuredAtEpochMs }

        val visiblePredictions =
            predictions
                .map { series ->
                    series.copy(
                        samples =
                            series.samples.filter {
                                it.measuredAtEpochMs in start..end
                            },
                    )
                }
                .filter { it.samples.isNotEmpty() }

        val left = 6f.dp
        val right = width - 6f.dp
        val top = 5f.dp
        val bottom = height - 5f.dp
        if (right <= left || bottom <= top) return

        fun xFor(timestamp: Long): Float =
            left +
                (
                    (timestamp - start).toDouble() /
                        (end - start).coerceAtLeast(1L)
                    )
                    .coerceIn(0.0, 1.0)
                    .toFloat() *
                (right - left)

        fun yFor(value: Double): Float =
            bottom -
                GlucoseGraphScale
                    .ratio(value)
                    .toFloat() *
                (bottom - top)

        val targetLow = thresholds.lowMgDl
        val targetHigh = thresholds.highMgDl
        val targetTop = yFor(targetHigh)
        val targetBottom = yFor(targetLow)

        // The view itself owns the rounded clip. Every background/range fill can therefore paint
        // truly full-bleed to the view edges without a second Path clip that leaves dark fringe
        // pixels between the graph surface and the final tile contour on Wear hardware.
        fillPaint.color = colors.graphBackground
        canvas.drawRect(
            0f,
            0f,
            viewRight,
            viewBottom,
            fillPaint,
        )

        val excursion = CgmGraphPolicy.rangeExcursion(history, targetLow, targetHigh)
        if (excursion == RangeExcursion.HIGH) {
            fillPaint.color = colors.rangeHigh
            canvas.drawRect(
                0f,
                0f,
                viewRight,
                targetTop,
                fillPaint,
            )
        }

        fillPaint.color = colors.rangeInRange
        canvas.drawRect(
            0f,
            targetTop,
            viewRight,
            targetBottom,
            fillPaint,
        )

        if (excursion == RangeExcursion.LOW) {
            fillPaint.color = colors.rangeLow
            canvas.drawRect(
                0f,
                targetBottom,
                viewRight,
                viewBottom,
                fillPaint,
            )
        }

        if (history.isEmpty() && visiblePredictions.isEmpty()) {
            canvas.drawText(
                "Noch keine CGM-Historie",
                width / 2f,
                height / 2f + 4f.dp,
                emptyTextPaint,
            )
            drawRelativeTimeAxis(canvas, start, end, now, ::xFor)
            drawTileContour(canvas, tileRadius)
            return
        }

        linePaint.color = colors.divider
        linePaint.pathEffect = null
        linePaint.strokeWidth = 0.7f.dp
        canvas.drawLine(left, targetTop, right, targetTop, linePaint)
        canvas.drawLine(left, targetBottom, right, targetBottom, linePaint)

        canvas.drawText(
            targetHigh.toInt().toString(),
            right - 1f.dp,
            targetTop - 2f.dp,
            targetLabelPaint,
        )
        canvas.drawText(
            targetLow.toInt().toString(),
            right - 1f.dp,
            targetBottom - 2f.dp,
            targetLabelPaint,
        )

        val dividerX = xFor(now)
        if (visiblePredictions.isNotEmpty()) {
            linePaint.color = colors.divider
            linePaint.strokeWidth = 1f.dp
            linePaint.pathEffect =
                DashPathEffect(
                    floatArrayOf(3f.dp, 3f.dp),
                    0f,
                )
            canvas.drawLine(dividerX, top, dividerX, bottom, linePaint)
            linePaint.pathEffect = null
        }

        val dotRadius =
            graphStyle.cgmDotRadiusDp
                .coerceIn(1.5f, 6.0f)
                .dp
        val outlineWidth =
            graphStyle.cgmDotOutlineWidthDp
                .coerceIn(0.25f, 3.0f)
                .dp

        history.forEach { point ->
            drawCgmDot(
                canvas = canvas,
                x = xFor(point.measuredAtEpochMs),
                y = yFor(point.valueMgDl),
                valueMgDl = point.valueMgDl,
                targetLow = targetLow,
                targetHigh = targetHigh,
                radius = dotRadius,
                outlineWidth = outlineWidth,
            )
        }

        visiblePredictions.forEach { series ->
            drawPrediction(
                canvas = canvas,
                series = series,
                xFor = ::xFor,
                yFor = ::yFor,
            )
        }

        drawRelativeTimeAxis(canvas, start, end, now, ::xFor)
        drawTileContour(canvas, tileRadius)
    }

    private fun drawRelativeTimeAxis(
        canvas: Canvas,
        start: Long,
        end: Long,
        now: Long,
        xFor: (Long) -> Float,
    ) {
        axisLabelPaint.color = colors.divider
        RelativeGraphTimeAxis.ticks(start, end, now).forEach { tick ->
            val x = xFor(tick.timestampEpochMs)
            axisLabelPaint.textAlign =
                when {
                    tick.timestampEpochMs <= start + 30_000L -> Paint.Align.LEFT
                    tick.hoursBack == 0 -> Paint.Align.RIGHT
                    else -> Paint.Align.CENTER
                }
            val labelX =
                when (axisLabelPaint.textAlign) {
                    Paint.Align.LEFT -> maxOf(3f.dp, x)
                    Paint.Align.RIGHT -> minOf(width - 3f.dp, x)
                    else -> x
                }
            canvas.drawText(tick.label, labelX, height - 2f.dp, axisLabelPaint)
        }
    }

    private fun drawTileContour(
        canvas: Canvas,
        radius: Float,
    ) {
        val strokeWidth = 1f.dp
        val inset = strokeWidth / 2f

        linePaint.color = colors.divider
        linePaint.strokeWidth = strokeWidth
        linePaint.pathEffect = null
        canvas.drawRoundRect(
            inset,
            inset,
            width - inset,
            height - inset,
            (radius - inset).coerceAtLeast(0f),
            (radius - inset).coerceAtLeast(0f),
            linePaint,
        )
    }

    private fun drawCgmDot(
        canvas: Canvas,
        x: Float,
        y: Float,
        valueMgDl: Double,
        targetLow: Double,
        targetHigh: Double,
        radius: Float,
        outlineWidth: Float,
    ) {
        if (graphStyle.cgmDotOutlineEnabled) {
            fillPaint.color = colors.outline
            canvas.drawCircle(
                x,
                y,
                radius + outlineWidth,
                fillPaint,
            )
        }

        fillPaint.color =
            glucoseColor(valueMgDl)
        canvas.drawCircle(x, y, radius, fillPaint)
    }

    private fun drawPrediction(
        canvas: Canvas,
        series: GlucosePrediction,
        xFor: (Long) -> Float,
        yFor: (Double) -> Float,
    ) {
        fillPaint.color =
            when (series.kind) {
                PredictionKind.IOB -> colors.predictionIob
                PredictionKind.COB,
                PredictionKind.ACOB -> colors.predictionCob
                PredictionKind.UAM -> colors.predictionUam
                PredictionKind.ZERO_TEMP -> colors.predictionZeroTemp
            }

        series.samples.forEach { point ->
            canvas.drawCircle(
                xFor(point.measuredAtEpochMs),
                yFor(point.valueMgDl),
                1.8f.dp,
                fillPaint,
            )
        }
    }

    private fun glucoseColor(valueMgDl: Double): Int = when (thresholds.classify(valueMgDl)) {
        CgmRangeClass.VERY_LOW -> colors.cgmVeryLow
        CgmRangeClass.LOW -> colors.cgmLow
        CgmRangeClass.IN_RANGE -> colors.cgmInRange
        CgmRangeClass.HIGH -> colors.cgmHigh
        CgmRangeClass.VERY_HIGH -> colors.cgmVeryHigh
        null -> colors.cgmInRange
    }

    private val Float.dp: Float
        get() = this * density

    companion object {
        private const val TILE_RADIUS_DP = 18f
    }
}

/**
 * Wear graph rendering intentionally ignores effective/temporary target values and target history.
 * Only the display range (low/high) influences this graph. The target-value timeline belongs solely
 * to the Mobile CGM graph.
 */
internal fun wearChartStateSignature(state: TherapyDisplayState?): List<Any?>? =
    state?.let {
        listOf(
            it.glucose,
            it.glucoseHistory,
            it.glucosePredictions,
            it.target?.lowMgDl,
            it.target?.highMgDl,
        )
    }

internal fun wearChartTimeWindow(
    timelineNow: Long,
    predictionEnd: Long,
    durationHours: Int,
    showPredictions: Boolean,
): LongRange {
    val historyDuration =
        durationHours
            .coerceAtLeast(1)
            .toLong() *
            WEAR_CHART_HOUR_MS
    val start = (timelineNow - historyDuration).coerceAtLeast(0L)
    val end =
        if (showPredictions) {
            max(timelineNow, predictionEnd)
        } else {
            timelineNow
        }.coerceAtLeast(start + 1L)
    return start..end
}

private const val WEAR_CHART_HOUR_MS = 60L * 60_000L
