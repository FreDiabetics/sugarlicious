package app.aapswear.wear

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import app.aapswear.model.GlucoseGraphScale
import app.aapswear.model.CgmGraphPolicy
import app.aapswear.model.CgmQuality
import app.aapswear.model.GlucosePrediction
import app.aapswear.model.GlucoseSample
import app.aapswear.model.Freshness
import app.aapswear.model.TargetStepTimeline
import app.aapswear.model.PredictionKind
import app.aapswear.model.RangeExcursion
import app.aapswear.model.TherapyDisplayFormatter
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

    private var state: TherapyDisplayState? = null
    private var durationHours: Int = 3
    private var showPredictions: Boolean = false
    private var colors: WatchGraphColors = WatchGraphColors()
    private var graphStyle: WatchGraphStyle = WatchGraphStyle()
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
    ) {
        val resolvedDuration =
            graphHours
                .takeIf { it in WearDisplayPreferences.allowedGraphHours }
                ?: 3
        val newStateSignature =
            newState?.let {
                listOf(
                    it.glucose,
                    it.glucoseHistory,
                    it.glucosePredictions,
                    it.target,
                    it.targetHistory,
                )
            }
        if (
            stateSignature == newStateSignature &&
            durationHours == resolvedDuration &&
            this.showPredictions == showPredictions &&
            this.colors == colors &&
            graphStyle == style
        ) {
            return
        }

        state = newState
        stateSignature = newStateSignature
        durationHours = resolvedDuration
        this.showPredictions = showPredictions
        this.colors = colors
        graphStyle = style
        emptyTextPaint.color = colors.divider
        targetLabelPaint.color = colors.divider
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

        val targetLow = state?.target?.lowMgDl ?: TARGET_LOW
        val targetHigh = state?.target?.highMgDl ?: TARGET_HIGH
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

        val currentTarget = state?.target?.valueMgDl?.takeIf { it.isFinite() && it in 20.0..1_000.0 }
        val freshness = TherapyDisplayFormatter.freshness(state, now)
        val targetSegments =
            state?.targetHistory.orEmpty()
                .asSequence()
                .filter {
                    it.valueMgDl.isFinite() &&
                        it.valueMgDl in 20.0..1_000.0 &&
                        it.startedAtEpochMs <= end &&
                        it.endsAtEpochMs >= start
                }
                .map { sample ->
                    val active =
                        sample == state?.targetHistory?.lastOrNull() &&
                            sample.valueMgDl == currentTarget &&
                            sample.temporary == (state?.target?.temporary == true) &&
                            freshness in setOf(Freshness.CURRENT, Freshness.DELAYED)
                    val mayExtendToNow = active && (!sample.temporary || state?.target?.endsAtEpochMs == null)
                    sample.copy(endsAtEpochMs = if (mayExtendToNow) maxOf(sample.endsAtEpochMs, now) else sample.endsAtEpochMs)
                }
                .toList()
                .ifEmpty {
                    currentTarget?.let { value ->
                        val observedAt = state?.glucose?.measuredAtEpochMs ?: state?.receivedAtEpochMs ?: now
                        val temporary = state?.target?.temporary == true
                        val explicitEnd = state?.target?.endsAtEpochMs?.takeIf { temporary }
                        if (explicitEnd != null && explicitEnd <= observedAt) {
                            emptyList()
                        } else {
                            listOf(
                                app.aapswear.model.TargetSample(
                                    valueMgDl = value,
                                    startedAtEpochMs = if (temporary) observedAt else start,
                                    endsAtEpochMs = explicitEnd ?: now,
                                    temporary = temporary,
                                ),
                            )
                        }
                    }.orEmpty()
                }
        linePaint.color = colors.targetValue
        linePaint.strokeWidth = 1.4f.dp
        linePaint.pathEffect = DashPathEffect(floatArrayOf(4f.dp, 3f.dp), 0f)
        TargetStepTimeline.build(targetSegments, start, end).forEach { points ->
            val path = Path().apply {
                points.forEachIndexed { index, (time, value) ->
                    val x = xFor(time)
                    val y = yFor(value)
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            canvas.drawPath(path, linePaint)
        }
        linePaint.pathEffect = null

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

        drawTileContour(canvas, tileRadius)
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
            glucoseColor(
                valueMgDl,
                targetLow,
                targetHigh,
            )
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

    private fun glucoseColor(
        valueMgDl: Double,
        low: Double,
        high: Double,
    ): Int =
        when {
            valueMgDl < low -> colors.cgmLow
            valueMgDl > high -> colors.cgmHigh
            else -> colors.cgmInRange
        }

    private val Float.dp: Float
        get() = this * density

    companion object {
        private const val TARGET_LOW = 80.0
        private const val TARGET_HIGH = 160.0
        private const val TILE_RADIUS_DP = 18f
    }
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
