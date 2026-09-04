package app.aapswear.uishared

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import app.aapswear.model.CgmGraphPolicy
import app.aapswear.model.CgmQuality
import app.aapswear.model.CgmRangeClass
import app.aapswear.model.CgmThresholds
import app.aapswear.model.GlucosePrediction
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GraphTimeWindow
import app.aapswear.model.GraphAxisLayoutSpec
import app.aapswear.model.PredictionKind
import app.aapswear.model.RangeExcursion
import app.aapswear.model.RelativeGraphTimeAxis

/** Semantic colors shared by the Sugarlicious Wear and G7 Collector graph adapters. */
data class SharedWearCgmGraphPalette(
    val background: Int,
    val targetArea: Int,
    val highArea: Int,
    val lowArea: Int,
    val highLine: Int,
    val lowLine: Int,
    val dotHigh: Int,
    val dotInRange: Int,
    val dotLow: Int,
    val dotOutline: Int,
    val axisText: Int,
    val axisTick: Int,
    val nowLine: Int,
    val border: Int,
    val predictionIob: Int,
    val predictionCob: Int,
    val predictionUam: Int,
    val predictionZeroTemp: Int,
    val dotVeryHigh: Int = dotHigh,
    val dotVeryLow: Int = dotLow,
    val targetText: Int = axisText,
    val emptyText: Int = axisText,
)

data class SharedWearCgmGraphStyle(
    val dotRadiusDp: Float = 2.4f,
    val dotOutlineWidthDp: Float = 0.95f,
    val dotOutlineEnabled: Boolean = true,
    val historicalDotOutlineEnabled: Boolean = true,
    val currentDotOutlineEnabled: Boolean = true,
    val cornerRadiusDp: Float = 20f,
    val borderEnabled: Boolean = true,
    val timeAxisEnabled: Boolean = true,
    val targetTicksEnabled: Boolean = true,
    val targetLabelsOutsideRange: Boolean = false,
    val targetLabelsInsidePlot: Boolean = false,
    val rangeBackgroundEnabled: Boolean = true,
)

object DirectToWatchGraphDefaults {
    fun style() = SharedWearCgmGraphStyle(
        dotOutlineWidthDp = 0.35f,
        borderEnabled = false,
        timeAxisEnabled = false,
        targetTicksEnabled = false,
        targetLabelsOutsideRange = true,
        targetLabelsInsidePlot = true,
    )
}

/** Wear-only CGM scale. Mobile and graph complications outside Vigil keep their existing scale. */
object WearCgmGraphScale {
    const val MIN_MG_DL = 40.0
    const val MAX_MG_DL = 400.0

    fun ratio(valueMgDl: Double): Double {
        val value = valueMgDl.coerceIn(MIN_MG_DL, MAX_MG_DL)
        return when {
            value <= 80.0 -> ((value - MIN_MG_DL) / 40.0) * 0.215
            value <= 160.0 -> 0.215 + ((value - 80.0) / 80.0) * 0.300
            else -> 0.515 + ((value - 160.0) / 240.0) * 0.485
        }.coerceIn(0.0, 1.0)
    }
}

data class SharedWearCgmGraphInput(
    val history: List<GlucoseSample>,
    val predictions: List<GlucosePrediction> = emptyList(),
    val timeWindow: GraphTimeWindow,
    val nowEpochMs: Long,
    val thresholds: CgmThresholds,
    val palette: SharedWearCgmGraphPalette,
    val style: SharedWearCgmGraphStyle = SharedWearCgmGraphStyle(),
    val emptyLabel: String = "Noch keine CGM-Historie",
)

data class SharedWearCgmGraphMetrics(
    val plot: RectF,
    val axisLeftPx: Float,
    val highY: Float,
    val lowY: Float,
    val liveX: Float,
) {
    fun xFor(window: GraphTimeWindow, timestampEpochMs: Long): Float =
        window.plotX(timestampEpochMs, plot.left, plot.width())

    fun yFor(valueMgDl: Double): Float =
        plot.bottom - WearCgmGraphScale.ratio(valueMgDl).toFloat() * plot.height()
}

/**
 * One Canvas renderer for both Wear applications. App-specific code only adapts data and colors;
 * geometry, range confirmation, axes, clipping policy and timestamp placement stay identical.
 */
object SharedWearCgmGraphRenderer {
    fun metrics(
        widthPx: Int,
        heightPx: Int,
        density: Float,
        thresholds: CgmThresholds,
        style: SharedWearCgmGraphStyle = SharedWearCgmGraphStyle(),
    ): SharedWearCgmGraphMetrics {
        fun dp(value: Float) = value * density
        val left = dp(6f)
        val axisLeft = widthPx - dp(if (style.targetLabelsInsidePlot) 6f else 29f)
        val top = dp(6f)
        val bottom = heightPx - dp(if (style.timeAxisEnabled) 20f else 6f)
        val plot = RectF(left, top, axisLeft, bottom)
        fun y(value: Double): Float =
            plot.bottom - WearCgmGraphScale.ratio(value).toFloat() * plot.height()
        return SharedWearCgmGraphMetrics(
            plot = plot,
            axisLeftPx = axisLeft,
            highY = y(thresholds.highMgDl),
            lowY = y(thresholds.lowMgDl),
            liveX = axisLeft,
        )
    }

    fun render(
        canvas: Canvas,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        scaledDensity: Float,
        input: SharedWearCgmGraphInput,
    ): SharedWearCgmGraphMetrics? {
        if (widthPx <= 0 || heightPx <= 0 || !input.thresholds.isValid) return null
        fun dp(value: Float) = value * density
        val palette = input.palette
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.axisText
            textSize = 8.5f * scaledDensity
            typeface = Typeface.DEFAULT_BOLD
        }
        val emptyText = Paint(axisText).apply {
            color = palette.emptyText
            textAlign = Paint.Align.CENTER
            textSize = 10f * scaledDensity
        }
        val metrics = metrics(widthPx, heightPx, density, input.thresholds, input.style)
        val plot = metrics.plot
        if (plot.width() <= 0f || plot.height() <= 0f) return null

        val cornerRadius = dp(input.style.cornerRadiusDp).coerceAtLeast(0f)
        val canvasState = canvas.save()
        canvas.clipPath(Path().apply {
            addRoundRect(RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()), cornerRadius, cornerRadius, Path.Direction.CW)
        })

        fill.color = palette.background
        canvas.drawRoundRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), cornerRadius, cornerRadius, fill)

        val history = input.history
            .asSequence()
            .filter {
                it.quality == CgmQuality.VALID &&
                    it.valueMgDl.isFinite() &&
                    it.valueMgDl in 20.0..1_000.0 &&
                    it.measuredAtEpochMs in input.timeWindow.startEpochMs..input.timeWindow.endEpochMs
            }
            .sortedBy(GlucoseSample::measuredAtEpochMs)
            .distinctBy { listOf(it.sensorId, it.sessionId, it.sequenceNumber, it.measuredAtEpochMs, it.source) }
            .toList()
        val predictions = input.predictions.map { series ->
            series.copy(samples = series.samples.filter { it.measuredAtEpochMs in input.timeWindow.startEpochMs..input.timeWindow.endEpochMs })
        }.filter { it.samples.isNotEmpty() }

        fill.color = palette.targetArea
        canvas.drawRect(plot.left, metrics.highY, plot.right, metrics.lowY, fill)
        when (CgmGraphPolicy.rangeExcursion(history, input.thresholds).takeIf { input.style.rangeBackgroundEnabled }) {
            RangeExcursion.HIGH -> {
                fill.color = palette.highArea
                canvas.drawRect(plot.left, plot.top, plot.right, metrics.highY, fill)
            }
            RangeExcursion.LOW -> {
                fill.color = palette.lowArea
                canvas.drawRect(plot.left, metrics.lowY, plot.right, plot.bottom, fill)
            }
            null -> Unit
        }

        line.pathEffect = null
        line.strokeWidth = dp(0.8f)
        line.color = palette.highLine
        canvas.drawLine(plot.left, metrics.highY, plot.right, metrics.highY, line)
        line.color = palette.lowLine
        canvas.drawLine(plot.left, metrics.lowY, plot.right, metrics.lowY, line)

        val targetText = Paint(axisText).apply { color = palette.targetText }
        drawTargetLabel(canvas, input.thresholds.highMgDl, metrics.highY, true, plot.right, widthPx, density, targetText, line, palette.axisTick, input.style)
        drawTargetLabel(canvas, input.thresholds.lowMgDl, metrics.lowY, false, plot.right, widthPx, density, targetText, line, palette.axisTick, input.style)

        val liveX = metrics.xFor(input.timeWindow, input.timeWindow.liveEdgeEpochMs)
        if (predictions.isNotEmpty()) {
            line.color = palette.nowLine
            line.strokeWidth = dp(1f)
            line.pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
            canvas.drawLine(liveX, plot.top, liveX, plot.bottom, line)
            line.pathEffect = null
        }

        val radius = input.style.dotRadiusDp.coerceIn(1.5f, 6f) * density
        val outline = input.style.dotOutlineWidthDp.coerceIn(0.25f, 3f) * density
        history.forEachIndexed { index, sample ->
            val x = metrics.xFor(input.timeWindow, sample.measuredAtEpochMs)
            val y = metrics.yFor(sample.valueMgDl)
            val isCurrent = index == history.lastIndex
            val outlineEnabled = input.style.dotOutlineEnabled &&
                if (isCurrent) input.style.currentDotOutlineEnabled else input.style.historicalDotOutlineEnabled
            if (outlineEnabled) {
                fill.color = palette.dotOutline
                canvas.drawCircle(x, y, radius + outline, fill)
            }
            fill.color = when (input.thresholds.classify(sample.valueMgDl)) {
                CgmRangeClass.VERY_LOW -> palette.dotVeryLow
                CgmRangeClass.LOW -> palette.dotLow
                CgmRangeClass.HIGH -> palette.dotHigh
                CgmRangeClass.VERY_HIGH -> palette.dotVeryHigh
                else -> palette.dotInRange
            }
            canvas.drawCircle(x, y, radius, fill)
        }

        predictions.forEach { series ->
            fill.color = when (series.kind) {
                PredictionKind.IOB -> palette.predictionIob
                PredictionKind.COB, PredictionKind.ACOB -> palette.predictionCob
                PredictionKind.UAM -> palette.predictionUam
                PredictionKind.ZERO_TEMP -> palette.predictionZeroTemp
            }
            series.samples.forEach { sample ->
                canvas.drawCircle(
                    metrics.xFor(input.timeWindow, sample.measuredAtEpochMs),
                    metrics.yFor(sample.valueMgDl),
                    dp(1.8f),
                    fill,
                )
            }
        }

        if (input.style.timeAxisEnabled) drawTimeAxis(canvas, input, metrics, widthPx, heightPx, dp(1f), axisText, line)
        if (history.isEmpty() && predictions.isEmpty()) {
            emptyText.color = palette.emptyText
            canvas.drawText(input.emptyLabel, plot.centerX(), plot.centerY() - (emptyText.ascent() + emptyText.descent()) / 2f, emptyText)
        }

        if (input.style.borderEnabled) {
            val borderInset = dp(0.5f)
            line.color = palette.border
            line.strokeWidth = dp(1f)
            line.pathEffect = null
            canvas.drawRoundRect(
                borderInset, borderInset, widthPx - borderInset, heightPx - borderInset,
                (dp(input.style.cornerRadiusDp) - borderInset).coerceAtLeast(0f),
                (dp(input.style.cornerRadiusDp) - borderInset).coerceAtLeast(0f), line,
            )
        }
        canvas.restoreToCount(canvasState)
        return metrics.copy(liveX = liveX)
    }

    private fun drawTargetLabel(
        canvas: Canvas,
        value: Double,
        y: Float,
        isHigh: Boolean,
        plotRight: Float,
        widthPx: Int,
        density: Float,
        text: Paint,
        line: Paint,
        tickColor: Int,
        style: SharedWearCgmGraphStyle,
    ) {
        val spec = GraphAxisLayoutSpec.COMPACT
        val gap = spec.plotToTickGapDp * density
        val tickLength = spec.tickLengthDp * density
        val labelGap = spec.tickToLabelGapDp * density
        line.color = tickColor
        line.strokeWidth = 0.8f * density
        line.pathEffect = null
        val tickStart = plotRight + gap
        val tickEnd = if (style.targetTicksEnabled) tickStart + tickLength else tickStart
        if (style.targetTicksEnabled) canvas.drawLine(tickStart, y, tickEnd, y, line)
        text.textAlign = if (style.targetLabelsInsidePlot) Paint.Align.RIGHT else Paint.Align.LEFT
        val baseline = if (style.targetLabelsOutsideRange) {
            if (isHigh) y - 2f * density - text.descent() else y + 2f * density - text.ascent()
        } else y - (text.ascent() + text.descent()) / 2f
        val labelX = if (style.targetLabelsInsidePlot) {
            plotRight - 2f * density
        } else {
            minOf(tickEnd + if (style.targetTicksEnabled) labelGap else 1.5f * density, widthPx - spec.outerEdgePaddingDp * density - text.measureText(value.toInt().toString()))
        }
        canvas.drawText(value.toInt().toString(), labelX, baseline, text)
    }

    private fun drawTimeAxis(
        canvas: Canvas,
        input: SharedWearCgmGraphInput,
        metrics: SharedWearCgmGraphMetrics,
        widthPx: Int,
        heightPx: Int,
        oneDp: Float,
        text: Paint,
        line: Paint,
    ) {
        text.color = input.palette.axisText
        line.color = input.palette.axisTick
        line.strokeWidth = 0.8f * oneDp
        line.pathEffect = null
        RelativeGraphTimeAxis.ticks(
            input.timeWindow.startEpochMs,
            input.timeWindow.endEpochMs,
            input.nowEpochMs,
        ).forEach { tick ->
            val x = metrics.xFor(input.timeWindow, tick.timestampEpochMs)
            text.textAlign = when {
                tick.timestampEpochMs <= input.timeWindow.startEpochMs + 30_000L -> Paint.Align.LEFT
                tick.hoursBack == 0 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            val labelX = when (text.textAlign) {
                Paint.Align.LEFT -> maxOf(3f * oneDp, x)
                Paint.Align.RIGHT -> minOf(widthPx - 3f * oneDp, x)
                else -> x
            }
            val spec = GraphAxisLayoutSpec.COMPACT
            val tickStart = metrics.plot.bottom + spec.plotToTickGapDp * oneDp
            val tickEnd = tickStart + spec.tickLengthDp * oneDp
            canvas.drawLine(labelX, tickStart, labelX, tickEnd, line)
            val baseline = tickEnd + spec.tickToLabelGapDp * oneDp - text.ascent()
            canvas.drawText(tick.label, labelX, minOf(heightPx - spec.outerEdgePaddingDp * oneDp, baseline), text)
        }
    }
}
