package app.aapswear.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.withClip
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColors
import app.aapswear.mobile.ui.theme.derivedTargetValueArgb
import app.aapswear.model.Freshness
import app.aapswear.model.FreshnessPolicy
import app.aapswear.model.CanonicalCgmHistory
import app.aapswear.model.CgmGraphPolicy
import app.aapswear.model.GlucosePrediction
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.PredictionKind
import app.aapswear.model.RangeExcursion
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyHistorySample
import app.aapswear.model.TargetSample
import app.aapswear.model.TargetStepTimeline
import app.aapswear.storage.PredictionDisplayTimeline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

private const val HOUR_MS = 60L * 60_000L
private const val BASAL_HEIGHT_FRACTION = 0.28f
private const val ACTIVITY_HEIGHT_FRACTION = 0.92f
private const val GRAPH_CORNER_RADIUS_DP = 18f
private const val GLUCOSE_ZERO_RATIO = 0.055
private const val GLUCOSE_LOW_RATIO = 0.215
private const val GLUCOSE_TARGET_HIGH_RATIO = 0.515
private const val GLUCOSE_DISPLAY_MAX = 400.0
private const val TOOLKIT_ACTIVITY_SCALE_FACTOR = 1.15
private const val OVERVIEW_GRAPH_HOURS_MIGRATION = "graphHoursDefault3MigratedV5"

internal fun sustainedRangeExcursion(
    samples: List<GlucoseSample>,
    lowMgDl: Double,
    highMgDl: Double,
): RangeExcursion? = CgmGraphPolicy.rangeExcursion(samples, lowMgDl, highMgDl)

internal fun availableGlucoseHistoryWindowMs(state: TherapyDisplayState?, nowEpochMs: Long): Long {
    val earliest =
        buildList {
            state?.glucoseHistory.orEmpty().forEach { sample ->
                if (sample.measuredAtEpochMs <= nowEpochMs) add(sample.measuredAtEpochMs)
            }
            state?.glucose?.measuredAtEpochMs?.takeIf { it <= nowEpochMs }?.let(::add)
        }.minOrNull() ?: return 0L
    return (nowEpochMs - earliest).coerceIn(0L, 24L * HOUR_MS)
}

internal fun resolveOverviewGraphHoursPreference(
    preferences: SharedPreferences,
    durationHours: Int,
): Int {
    val normalized = durationHours.takeIf { it in listOf(3, 6, 12, 24) } ?: 3
    if (preferences.getBoolean(OVERVIEW_GRAPH_HOURS_MIGRATION, false)) return normalized

    val previousAuto24 =
        preferences.getBoolean("graphHoursDefault24MigratedV4", false) &&
            preferences.getInt("graphHours", normalized) == 24
    val resolved = when {
        !preferences.contains("graphHours") -> 3
        previousAuto24 -> 3
        else -> normalized
    }
    preferences.edit()
        .putInt("graphHours", resolved)
        .putBoolean(OVERVIEW_GRAPH_HOURS_MIGRATION, true)
        .apply()
    return resolved
}

internal fun graphCenterBeforeDivider(
    dividerX: Float,
    radiusPx: Float,
    outlineWidthPx: Float,
    safetyPx: Float,
): Float = dividerX - radiusPx - outlineWidthPx / 2f - safetyPx

internal fun graphCenterAfterDivider(
    dividerX: Float,
    radiusPx: Float,
    outlineWidthPx: Float,
    safetyPx: Float,
): Float = dividerX + radiusPx + outlineWidthPx / 2f + safetyPx

internal class ChartViewport(initialHours: Int) {
    private val listeners = LinkedHashSet<() -> Unit>()
    private var availablePastWindowMs = 24L * HOUR_MS
    private var requestedHours = initialHours.toFloat().coerceIn(1f, 24f)
    var hours = requestedHours
        private set
    var panMs = 0L
        private set
    var futureWindowMs = 0L
        private set

    fun setHours(value: Float, resetPan: Boolean = false) {
        requestedHours = value.coerceIn(1f, 24f)
        val next = requestedHours.coerceAtMost(maximumHours())
        val changed = next != hours || (resetPan && panMs != 0L)
        hours = next
        if (resetPan) panMs = 0L
        clampPan()
        if (changed) notifyChanged()
    }

    fun setFutureWindow(valueMs: Long) {
        val next = valueMs.coerceIn(0L, 2L * HOUR_MS)
        if (next == futureWindowMs) return
        futureWindowMs = next
        hours = requestedHours.coerceAtMost(maximumHours())
        clampPan()
        notifyChanged()
    }

    fun setAvailablePastWindow(valueMs: Long) {
        val next = valueMs.coerceIn(0L, 24L * HOUR_MS)
        if (next == availablePastWindowMs) return
        availablePastWindowMs = next
        hours = requestedHours.coerceAtMost(maximumHours())
        clampPan()
        notifyChanged()
    }

    fun endEpochMs(now: Long): Long = now + futureWindowMs + panMs

    fun zoom(scaleFactor: Float) {
        val oldHours = hours
        requestedHours = (hours / scaleFactor.coerceAtLeast(0.05f)).coerceIn(1f, 24f)
        hours = requestedHours.coerceAtMost(maximumHours())
        clampPan()
        if (hours != oldHours) notifyChanged()
    }

    fun pan(deltaPixels: Float, width: Float) {
        if (width <= 0f) return
        val oldPan = panMs
        panMs -= (deltaPixels / width * hours * HOUR_MS).toLong()
        clampPan()
        if (panMs != oldPan) notifyChanged()
    }

    internal fun addListener(listener: () -> Unit) { listeners += listener }
    internal fun removeListener(listener: () -> Unit) { listeners -= listener }
    private fun notifyChanged() { listeners.toList().forEach { it() } }
    private fun maximumHours(): Float =
        ((availablePastWindowMs + futureWindowMs).toFloat() / HOUR_MS)
            .coerceIn(1f, 24f)

    private fun clampPan() {
        val visibleWindowMs = (hours * HOUR_MS).toLong()
        val minimumPanMs = (visibleWindowMs - futureWindowMs - availablePastWindowMs).coerceAtMost(0L)
        panMs = panMs.coerceIn(minimumPanMs, 0L)
    }
}

internal abstract class InteractiveChartView(
    context: Context,
    attrs: AttributeSet? = null,
    protected val viewport: ChartViewport,
) : View(context, attrs) {
    private var lastX = 0f
    private var downX = 0f
    private var downY = 0f
    private var moving = false
    private val viewportListener: () -> Unit = { invalidate() }
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                viewport.zoom(detector.scaleFactor)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        },
    )

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewport.addListener(viewportListener)
    }

    override fun onDetachedFromWindow() {
        viewport.removeListener(viewportListener)
        super.onDetachedFromWindow()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                downX = event.x
                downY = event.y
                moving = false
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val delta = event.x - lastX
                    val horizontalGesture = abs(event.x - downX) > abs(event.y - downY) + 4f
                    if (horizontalGesture && abs(delta) > 0.4f) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        viewport.pan(delta, width.toFloat())
                        moving = true
                    }
                    lastX = event.x
                }
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!moving) performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

@SuppressLint("DrawAllocation")
internal class GlucoseDashboardChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    sharedViewport: ChartViewport = ChartViewport(6),
) : InteractiveChartView(context, attrs, sharedViewport) {
    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var state: TherapyDisplayState? = null
    private var unit = GlucoseUnit.MG_DL
    private var showPredictions = false
    private var showTargetRange = false
    private var showTargetValue = false
    private var showBasal = false
    private var showActivity = false
    private var showPredictionIob = false
    private var showPredictionCob = false
    private var showPredictionUam = false
    private var showPredictionZeroTemp = false
    private var cgmDotRadiusDp = 2.4f
    private var cgmDotOutlineEnabled = true
    private var cgmDotOutlineWidthDp = 0.95f
    private var predictionDotRadiusDp = 1.75f
    private var predictionDotOutlineWidthDp = 0.70f
    private var stateSignature: List<Any?>? = null
    private var clockBucket: Long = Long.MIN_VALUE
    private var boundDurationHours: Int? = null

    fun bind(
        state: TherapyDisplayState?,
        unit: GlucoseUnit,
        showPredictions: Boolean,
        durationHours: Int,
        showTargetRange: Boolean = false,
        showTargetValue: Boolean = false,
        showBasal: Boolean = false,
        showActivity: Boolean = false,
        showPredictionIob: Boolean = false,
        showPredictionCob: Boolean = false,
        showPredictionUam: Boolean = false,
        showPredictionZeroTemp: Boolean = false,
        cgmDotRadiusDp: Float = 2.4f,
        cgmDotOutlineEnabled: Boolean = true,
        cgmDotOutlineWidthDp: Float = 0.95f,
        clockEpochMs: Long = System.currentTimeMillis(),
    ) {
        val resolvedRadius = cgmDotRadiusDp.coerceIn(1.5f, 6.0f)
        val resolvedOutlineWidth = cgmDotOutlineWidthDp.coerceIn(0.25f, 3.0f)
        val stylePreferences = context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE)
        val resolvedDurationHours = resolveOverviewGraphHoursPreference(stylePreferences, durationHours)
        val resolvedPredictionRadius = stylePreferences.getFloat("cgm.prediction.dotRadiusDp", 1.75f).coerceIn(1.0f, 6.0f)
        val resolvedPredictionOutlineWidth = stylePreferences.getFloat("cgm.prediction.dotOutlineWidthDp", 0.70f).coerceIn(0.0f, 3.0f)
        val resolvedClockBucket = clockEpochMs / CLOCK_REFRESH_MS
        viewport.setAvailablePastWindow(availableGlucoseHistoryWindowMs(state, clockEpochMs))
        val newStateSignature = state?.let {
            buildList {
                add(it.source)
                add(it.glucose)
                add(it.glucoseHistory)
                add(it.glucosePredictions)
                add(it.target)
                add(it.targetHistory)
                if (showBasal || showActivity) add(it.therapyHistory)
            }
        }
        val durationChanged = boundDurationHours != resolvedDurationHours
        val changed =
            stateSignature != newStateSignature ||
                durationChanged ||
                this.unit != unit ||
                this.showPredictions != showPredictions ||
                this.showTargetRange != showTargetRange ||
                this.showTargetValue != showTargetValue ||
                this.showBasal != showBasal ||
                this.showActivity != showActivity ||
                this.showPredictionIob != showPredictionIob ||
                this.showPredictionCob != showPredictionCob ||
                this.showPredictionUam != showPredictionUam ||
                this.showPredictionZeroTemp != showPredictionZeroTemp ||
                this.cgmDotRadiusDp != resolvedRadius ||
                this.cgmDotOutlineEnabled != cgmDotOutlineEnabled ||
                this.cgmDotOutlineWidthDp != resolvedOutlineWidth ||
                this.predictionDotRadiusDp != resolvedPredictionRadius ||
                this.predictionDotOutlineWidthDp != resolvedPredictionOutlineWidth ||
                clockBucket != resolvedClockBucket

        if (!changed) return

        this.state = state
        stateSignature = newStateSignature
        boundDurationHours = resolvedDurationHours
        this.unit = unit
        this.showPredictions = showPredictions
        this.showTargetRange = showTargetRange
        this.showTargetValue = showTargetValue
        this.showBasal = showBasal
        this.showActivity = showActivity
        this.showPredictionIob = showPredictionIob
        this.showPredictionCob = showPredictionCob
        this.showPredictionUam = showPredictionUam
        this.showPredictionZeroTemp = showPredictionZeroTemp
        this.cgmDotRadiusDp = resolvedRadius
        this.cgmDotOutlineEnabled = cgmDotOutlineEnabled
        this.cgmDotOutlineWidthDp = resolvedOutlineWidth
        this.predictionDotRadiusDp = resolvedPredictionRadius
        this.predictionDotOutlineWidthDp = resolvedPredictionOutlineWidth
        clockBucket = resolvedClockBucket

        if (durationChanged) viewport.setHours(resolvedDurationHours.toFloat(), resetPan = true)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val outlineInset = 0.5f.dp
        val scaleContainer = RectF(outlineInset, outlineInset, width - outlineInset, height - outlineInset)
        val plot = RectF(scaleContainer)
        val contentBounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        if (plot.width() <= 24f || plot.height() <= 24f) return
        val radius = GRAPH_CORNER_RADIUS_DP.dp
        val contentClip = Path().apply { addRoundRect(contentBounds, radius + outlineInset, radius + outlineInset, Path.Direction.CW) }

        canvas.withClip(contentClip) {
            fillPaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_BACKGROUND)
            canvas.drawRect(contentBounds, fillPaint)

            val now = System.currentTimeMillis()
            val targetLow = state?.target?.lowMgDl ?: 80.0
            val targetHigh = state?.target?.highMgDl ?: 160.0
            val freshness = FreshnessPolicy.classify(state?.glucose?.measuredAtEpochMs, now)
            val signalLost = !TherapyDisplayFormatter.isGlucoseDisplayable(state, now)
            val predictions = if (showPredictions) state?.glucosePredictions.orEmpty().filter { predictionEnabled(it.kind) } else emptyList()
            val liveEdge = when {
                signalLost -> now
                !showPredictions || predictions.isEmpty() -> state?.glucose?.measuredAtEpochMs?.coerceAtMost(now) ?: now
                else -> now
            }
            val end = viewport.endEpochMs(liveEdge)
            val start = end - (viewport.hours * HOUR_MS).toLong()
            val allHistory = CanonicalCgmHistory.merge(
                samples = buildList {
                    addAll(state?.glucoseHistory.orEmpty())
                    state?.glucose?.let { glucose ->
                        add(
                            GlucoseSample(
                                valueMgDl = glucose.valueMgDl,
                                measuredAtEpochMs = glucose.measuredAtEpochMs,
                                source = glucose.source,
                                sensorId = glucose.sensorId,
                                sessionId = glucose.sessionId,
                                sequenceNumber = glucose.sequenceNumber,
                                receivedAtEpochMs = glucose.receivedAtEpochMs,
                                quality = glucose.quality,
                            ),
                        )
                    }
                },
                nowEpochMs = now,
                preferredSource = state?.source,
            )
            val history = allHistory.filter { it.measuredAtEpochMs in start..min(end, now) }
            val visiblePredictions = PredictionDisplayTimeline.anchor(predictions, now)
                .map { series -> series.copy(samples = series.samples.filter { it.measuredAtEpochMs in start..end }) }
                .filter { it.samples.isNotEmpty() }

            val targetTop = mapGlucoseY(targetHigh, plot)
            val targetBottom = mapGlucoseY(targetLow, plot)
            val excursion = if (signalLost) null else sustainedRangeExcursion(allHistory, targetLow, targetHigh)

            if (showTargetRange) {
                if (excursion == RangeExcursion.HIGH) {
                    fillPaint.color = SugarliciousColors.argb(SugarliciousColorRole.RANGE_HIGH)
                    canvas.drawRect(contentBounds.left, contentBounds.top, contentBounds.right, targetTop, fillPaint)
                }
                fillPaint.color = SugarliciousColors.argb(SugarliciousColorRole.RANGE_IN_RANGE)
                canvas.drawRect(contentBounds.left, targetTop, contentBounds.right, targetBottom, fillPaint)
                if (excursion == RangeExcursion.LOW) {
                    fillPaint.color = SugarliciousColors.argb(SugarliciousColorRole.RANGE_LOW)
                    canvas.drawRect(contentBounds.left, targetBottom, contentBounds.right, contentBounds.bottom, fillPaint)
                }
            }

            drawGrid(canvas, plot, start, end)

            if (signalLost) {
                val signalStart = state?.glucose?.measuredAtEpochMs?.let { mapX(it, start, end, plot) }?.coerceIn(plot.left, plot.right) ?: plot.left
                if (signalStart < plot.right) {
                    fillPaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_SIGNAL_LOSS)
                    canvas.drawRect(signalStart, plot.top, plot.right, plot.bottom, fillPaint)
                    drawText(
                        canvas,
                        "SIGNALVERLUST",
                        (signalStart + plot.right) / 2f,
                        plot.top + 15f.dp,
                        9f,
                        luminousTargetValueColor(SugarliciousColors.argb(SugarliciousColorRole.GRAPH_SIGNAL_LOSS)),
                        Paint.Align.CENTER,
                    )
                }
            }

            if (showTargetValue) {
                val currentTarget = state?.target?.valueMgDl?.takeIf { it.isFinite() && it in 20.0..1_000.0 }
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
                            val isCurrentObservedTarget =
                                sample == state?.targetHistory?.lastOrNull() &&
                                    currentTarget == sample.valueMgDl &&
                                    sample.temporary == (state?.target?.temporary == true) &&
                                    freshness in setOf(Freshness.CURRENT, Freshness.DELAYED)
                            val mayExtendToNow =
                                isCurrentObservedTarget &&
                                    (!sample.temporary || state?.target?.endsAtEpochMs == null)
                            sample.copy(
                                endsAtEpochMs =
                                    if (mayExtendToNow) maxOf(sample.endsAtEpochMs, now) else sample.endsAtEpochMs,
                            )
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

                val targetValueColor = withAlpha(SugarliciousColors.argb(SugarliciousColorRole.TARGET_VALUE), 190)
                linePaint.color = targetValueColor
                linePaint.strokeWidth = 1.35f.dp
                linePaint.pathEffect = DashPathEffect(floatArrayOf(3f.dp, 3f.dp), 0f)
                targetStepPaths(targetSegments, start, end).forEach { points ->
                    canvas.drawPath(
                        valuePath(points, start, end, plot) { value -> mapGlucoseY(value, plot) },
                        linePaint,
                    )
                }
                linePaint.pathEffect = null
            }

            if (showBasal) drawBasal(canvas, plot, start, end, state?.therapyHistory.orEmpty())
            if (showActivity) {
                drawInsulinActivity(
                    canvas,
                    RectF(plot.left, targetTop, plot.right, targetBottom),
                    start,
                    end,
                    now,
                    state?.therapyHistory.orEmpty(),
                )
            }

            val dividerX = mapX(now, start, end, plot).coerceIn(plot.left, plot.right)
            val futureLaneVisible = end > now && now in start..end
            if (futureLaneVisible) {
                linePaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_DIVIDER)
                linePaint.strokeWidth = 1f.dp
                linePaint.pathEffect = DashPathEffect(floatArrayOf(4f.dp, 4f.dp), 0f)
                canvas.drawLine(dividerX, plot.top, dividerX, plot.bottom, linePaint)
                linePaint.pathEffect = null
            }

            history.forEachIndexed { index, point ->
                val mappedX = mapX(point.measuredAtEpochMs, start, end, plot)
                val y = mapGlucoseY(point.valueMgDl, plot)
                val current = index == history.lastIndex
                val dotRadius = (cgmDotRadiusDp + if (current) 0.1f else 0f).dp
                val outlineWidth = if (cgmDotOutlineEnabled) cgmDotOutlineWidthDp.dp else 0f
                val historyRightEdge =
                    if (futureLaneVisible) {
                        graphCenterBeforeDivider(dividerX, dotRadius, outlineWidth, 2f.dp)
                    } else {
                        plot.right - dotRadius - outlineWidth / 2f - 2f.dp
                    }
                val x = min(mappedX, historyRightEdge).coerceAtLeast(plot.left + dotRadius + outlineWidth / 2f)
                fillPaint.color = dotColor(point.valueMgDl, targetLow, targetHigh)
                canvas.drawCircle(x, y, dotRadius, fillPaint)
                if (cgmDotOutlineEnabled) {
                    dotOutlinePaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_CURRENT_OUTLINE)
                    dotOutlinePaint.strokeWidth = outlineWidth
                    canvas.drawCircle(x, y, dotRadius + outlineWidth / 2f, dotOutlinePaint)
                }
            }

            if (visiblePredictions.isNotEmpty() && futureLaneVisible) {
                val predictionAnchorY = history.lastOrNull()?.let { point -> mapGlucoseY(point.valueMgDl, plot) }
                visiblePredictions.forEach {
                    drawPrediction(canvas, it, plot, start, end, dividerX, predictionAnchorY)
                }
            }

            if (showTargetRange) {
                drawTargetLabels(canvas, glucoseLabel(targetHigh), glucoseLabel(targetLow), plot, targetTop, targetBottom)
            }

            if (history.size < 2) {
                drawText(canvas, "Noch kein Verlauf", plot.centerX(), plot.centerY(), 10f, SugarliciousColors.argb(SugarliciousColorRole.GRAPH_MUTED), Paint.Align.CENTER)
            }
        }
        drawRoundedBorder(canvas, scaleContainer, radius)
    }

    private fun drawGrid(canvas: Canvas, plot: RectF, start: Long, end: Long) {
        linePaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_GRID)
        linePaint.strokeWidth = 0.7f.dp
        linePaint.pathEffect = DashPathEffect(floatArrayOf(3f.dp, 3f.dp), 0f)
        val interval = timeGridIntervalMs(viewport.hours)
        var tick = firstAlignedTick(start, interval)
        while (tick <= end) {
            val x = mapX(tick, start, end, plot)
            if (x >= plot.left && x <= plot.right) canvas.drawLine(x, plot.top, x, plot.bottom, linePaint)
            tick += interval
        }
        linePaint.pathEffect = null
        tick = firstAlignedTick(start, interval)
        while (tick <= end) {
            val x = mapX(tick, start, end, plot)
            if (x >= plot.left + 22f.dp && x <= plot.right - 22f.dp) {
                drawText(
                    canvas,
                    timeFormat.format(Date(tick)),
                    x,
                    plot.bottom - 7f.dp,
                    8.5f,
                    SugarliciousColors.argb(SugarliciousColorRole.GRAPH_LABEL),
                    Paint.Align.CENTER,
                    bold = true,
                )
            }
            tick += interval
        }
    }

    private fun drawBasal(canvas: Canvas, plot: RectF, start: Long, end: Long, points: List<TherapyHistorySample>) {
        val sorted = points.filter { it.baseBasalUnitsPerHour != null || it.basalUnitsPerHour != null }.sortedBy { it.measuredAtEpochMs }
        val visible = windowedStepSamples(sorted, start, end)
        if (visible.size < 2) return
        val maxBasal = max(0.1, visible.flatMap { listOfNotNull(it.baseBasalUnitsPerHour ?: it.basalUnitsPerHour, effectiveBasal(it)) }.maxOrNull() ?: 0.1)
        fun basalY(value: Double): Float = plot.top + (value.coerceIn(0.0, maxBasal) / maxBasal).toFloat() * plot.height() * BASAL_HEIGHT_FRACTION
        val cyan = SugarliciousColors.argb(SugarliciousColorRole.SECONDARY)
        val effective = visible.map { it.measuredAtEpochMs to effectiveBasal(it) }
        val base = visible.map { it.measuredAtEpochMs to (it.baseBasalUnitsPerHour ?: it.basalUnitsPerHour ?: 0.0) }
        val clip = Path().apply { addRoundRect(plot, 14f.dp, 14f.dp, Path.Direction.CW) }
        canvas.withClip(clip) {
            val area = stepPath(effective, start, end, plot, ::basalY, closeAt = plot.top)
            fillPaint.color = withAlpha(cyan, 76)
            drawPath(area, fillPaint)
            linePaint.color = cyan
            linePaint.strokeWidth = 1.2f.dp
            linePaint.pathEffect = null
            drawPath(stepPath(effective, start, end, plot, ::basalY), linePaint)
            linePaint.strokeWidth = 1f.dp
            linePaint.pathEffect = DashPathEffect(floatArrayOf(1f.dp, 2f.dp), 0f)
            drawPath(stepPath(base, start, end, plot, ::basalY), linePaint)
            linePaint.pathEffect = null
        }
    }

    private fun drawInsulinActivity(
        canvas: Canvas,
        band: RectF,
        start: Long,
        end: Long,
        now: Long,
        points: List<TherapyHistorySample>,
    ) {
        val actual = points.mapNotNull { point ->
            point.insulinActivityUnitsPerMinute?.takeIf { it.isFinite() && it >= 0.0 }?.let { point.measuredAtEpochMs to it }
        }.filter { it.first in start..min(end, now) }.sortedBy { it.first }
        if (actual.size < 2) return
        val future = buildActivityProjection(actual.last(), max(now, actual.last().first), end)
        val maxActivity = max(0.0001, (actual.map { it.second } + future.map { it.second }).maxOrNull() ?: 0.0001)
        fun activityY(value: Double): Float = band.bottom - (value / maxActivity).coerceIn(0.0, 1.0).toFloat() * band.height() * ACTIVITY_HEIGHT_FRACTION
        val yellow = Color.rgb(242, 201, 76)
        val smoothedActual = smoothSeries(actual)
        val smoothedFuture = smoothSeries(future)
        linePaint.color = yellow
        linePaint.strokeWidth = 1.35f.dp
        linePaint.pathEffect = null
        canvas.drawPath(smoothValuePath(smoothedActual, start, end, band, ::activityY), linePaint)
        if (smoothedFuture.size >= 2) {
            linePaint.pathEffect = DashPathEffect(floatArrayOf(4f.dp, 4f.dp), 0f)
            canvas.drawPath(smoothValuePath(smoothedFuture, start, end, band, ::activityY), linePaint)
            linePaint.pathEffect = null
        }
    }

    private fun predictionEnabled(kind: PredictionKind): Boolean = when (kind) {
        PredictionKind.IOB -> showPredictionIob
        PredictionKind.COB, PredictionKind.ACOB -> showPredictionCob
        PredictionKind.UAM -> showPredictionUam
        PredictionKind.ZERO_TEMP -> showPredictionZeroTemp
    }

    private fun drawPrediction(
        canvas: Canvas,
        series: GlucosePrediction,
        plot: RectF,
        start: Long,
        end: Long,
        anchorX: Float,
        anchorY: Float?,
    ) {
        val color = when (series.kind) {
            PredictionKind.IOB -> SugarliciousColors.argb(SugarliciousColorRole.PREDICTION_IOB)
            PredictionKind.COB, PredictionKind.ACOB -> SugarliciousColors.argb(SugarliciousColorRole.PREDICTION_COB)
            PredictionKind.UAM -> SugarliciousColors.argb(SugarliciousColorRole.PREDICTION_UAM)
            PredictionKind.ZERO_TEMP -> SugarliciousColors.argb(SugarliciousColorRole.PREDICTION_ZERO_TEMP)
        }
        val radius = predictionDotRadiusDp.dp
        val outlineWidth = predictionDotOutlineWidthDp.dp
        val minimumPredictionCenter = graphCenterAfterDivider(anchorX, radius, outlineWidth, 2f.dp)
        val maximumPredictionCenter = plot.right - radius - outlineWidth / 2f - 1f.dp
        series.samples.forEachIndexed { index, point ->
            val mappedX = mapX(point.measuredAtEpochMs, start, end, plot)
            val x = mappedX.coerceAtLeast(minimumPredictionCenter)
            if (x > maximumPredictionCenter) return@forEachIndexed
            val y = if (index == 0 && anchorY != null) anchorY else mapGlucoseY(point.valueMgDl, plot)
            if (outlineWidth > 0f) {
                dotOutlinePaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_CURRENT_OUTLINE)
                dotOutlinePaint.strokeWidth = outlineWidth
                canvas.drawCircle(x, y, radius + outlineWidth / 2f, dotOutlinePaint)
            }
            fillPaint.color = color
            canvas.drawCircle(x, y, radius, fillPaint)
        }
    }

    companion object { private const val CLOCK_REFRESH_MS = 30_000L }

    private fun drawRoundedBorder(canvas: Canvas, rect: RectF, radius: Float) {
        linePaint.style = Paint.Style.STROKE
        linePaint.pathEffect = null
        linePaint.color = SugarliciousColors.argb(SugarliciousColorRole.BORDER)
        linePaint.strokeWidth = 1f.dp
        canvas.drawRoundRect(rect, radius, radius, linePaint)
    }

    private fun dotColor(value: Double, low: Double, high: Double): Int = when {
        value < low -> SugarliciousColors.argb(SugarliciousColorRole.CGM_DOT_LOW)
        value > high -> SugarliciousColors.argb(SugarliciousColorRole.CGM_DOT_HIGH)
        else -> SugarliciousColors.argb(SugarliciousColorRole.CGM_DOT_IN_RANGE)
    }

    private fun glucoseLabel(valueMgDl: Double): String =
        if (unit == GlucoseUnit.MMOL_L) String.format(Locale.getDefault(), "%.1f", valueMgDl / 18.0) else valueMgDl.toInt().toString()

    private fun drawTargetLabels(
        canvas: Canvas,
        highValue: String,
        lowValue: String,
        plot: RectF,
        targetTop: Float,
        targetBottom: Float,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
            color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_LABEL)
            textAlign = Paint.Align.RIGHT
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val metrics = paint.fontMetrics
        val minimumBaseline = plot.top - metrics.ascent + 1f.dp
        val maximumBaseline = plot.bottom - metrics.descent - 1f.dp
        val highBaseline = (targetTop - 2f.dp - metrics.descent).coerceIn(minimumBaseline, maximumBaseline)
        val lowBaseline = (targetBottom + 2f.dp - metrics.ascent).coerceIn(minimumBaseline, maximumBaseline)
        val x = plot.right - 5f.dp
        canvas.drawText(highValue, x, highBaseline, paint)
        canvas.drawText(lowValue, x, lowBaseline, paint)
    }

    private val Float.dp get() = this * density
}

@SuppressLint("DrawAllocation")
internal class MetabolicDashboardChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    sharedViewport: ChartViewport = ChartViewport(6),
) : InteractiveChartView(context, attrs, sharedViewport) {
    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var state: TherapyDisplayState? = null
    private var boundDurationHours: Int? = null
    private var stateSignature: List<Any?>? = null

    fun bind(state: TherapyDisplayState?, durationHours: Int) {
        val newStateSignature = state?.let { listOf(it.glucose, it.therapyHistory) }
        if (stateSignature == newStateSignature && boundDurationHours == durationHours) return
        this.state = state
        stateSignature = newStateSignature
        boundDurationHours = durationHours
        if (!isAttachedToWindow) viewport.setHours(durationHours.toFloat())
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val outer = RectF(0.5f.dp, 0.5f.dp, width - 0.5f.dp, height - 0.5f.dp)
        if (outer.width() <= 24f || outer.height() <= 24f) return
        val radius = GRAPH_CORNER_RADIUS_DP.dp
        val clip = Path().apply { addRoundRect(outer, radius, radius, Path.Direction.CW) }
        canvas.withClip(clip) {
            fillPaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_BACKGROUND)
            canvas.drawRoundRect(outer, radius, radius, fillPaint)
            val chartNow = System.currentTimeMillis()
            val liveEdge = if (viewport.futureWindowMs == 0L) state?.glucose?.measuredAtEpochMs?.coerceAtMost(chartNow) ?: chartNow else chartNow
            val end = viewport.endEpochMs(liveEdge)
            val start = end - (viewport.hours * HOUR_MS).toLong()
            val allPoints = state?.therapyHistory.orEmpty()
            val points = allPoints.filter { it.measuredAtEpochMs in start..end }
            val left = outer.left
            val right = outer.right
            val top = outer.top
            val bottom = outer.bottom
            val gap = 3f.dp
            val half = (bottom - top - gap) / 2f
            val iobPlot = RectF(left, top, right, top + half)
            val cobPlot = RectF(left, top + half + gap, right, bottom)
            val iobRange = toolkitMetabolicRange(allPoints.mapNotNull { it.totalIob })
            val cobRange = toolkitMetabolicRange(allPoints.mapNotNull { it.cobGrams }, sharedZeroRatio = iobRange.zeroRatio)
            drawSharedGrid(canvas, iobPlot, cobPlot, start, end)
            drawLane(canvas, iobPlot, points, start, end, iob = true, range = iobRange)
            drawInsulinActivity(canvas, iobPlot, allPoints, points, start, end, iobRange.zeroRatio)
            drawLane(canvas, cobPlot, points, start, end, iob = false, range = cobRange)
            val projectionNow = System.currentTimeMillis()

            if (projectionNow in start..end) {
                val dividerX = mapX(projectionNow, start, end, iobPlot)
                linePaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_DIVIDER)
                linePaint.strokeWidth = 1f.dp
                linePaint.pathEffect = DashPathEffect(floatArrayOf(4f.dp, 4f.dp), 0f)
                canvas.drawLine(dividerX, iobPlot.top, dividerX, cobPlot.bottom, linePaint)
                linePaint.pathEffect = null
            }

            drawFutureLane(canvas, iobPlot, buildIobProjection(allPoints, projectionNow, end), start, end, iobRange, SugarliciousColors.argb(SugarliciousColorRole.GRAPH_IOB))
            drawFutureLane(canvas, cobPlot, buildCobProjection(allPoints, projectionNow, end), start, end, cobRange, SugarliciousColors.argb(SugarliciousColorRole.GRAPH_COB))
            drawSmbMarkers(canvas, iobPlot, points, start, end, mapSignedLogY(0.0, iobRange.minimum, iobRange.maximum, iobPlot))
            if (points.none { it.totalIob != null || it.cobGrams != null }) {
                drawText(canvas, "Noch kein IOB/COB-Verlauf", (left + right) / 2f, (top + bottom) / 2f, 10f, SugarliciousColors.argb(SugarliciousColorRole.GRAPH_MUTED), Paint.Align.CENTER)
            }
        }
        linePaint.color = SugarliciousColors.argb(SugarliciousColorRole.BORDER)
        linePaint.strokeWidth = 1f.dp
        linePaint.pathEffect = null
        canvas.drawRoundRect(outer, radius, radius, linePaint)
    }

    private fun drawSharedGrid(canvas: Canvas, iob: RectF, cob: RectF, start: Long, end: Long) {
        linePaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_GRID)
        linePaint.strokeWidth = 0.7f.dp
        linePaint.pathEffect = DashPathEffect(floatArrayOf(3f.dp, 3f.dp), 0f)
        val interval = timeGridIntervalMs(viewport.hours)
        var tick = firstAlignedTick(start, interval)
        while (tick <= end) {
            val x = mapX(tick, start, end, iob)
            if (x >= iob.left && x <= iob.right) canvas.drawLine(x, iob.top, x, cob.bottom, linePaint)
            tick += interval
        }
        linePaint.pathEffect = null
        tick = firstAlignedTick(start, interval)
        while (tick <= end) {
            val x = mapX(tick, start, end, cob)
            if (x >= cob.left + 22f.dp && x <= cob.right - 22f.dp) {
                drawText(
                    canvas,
                    timeFormat.format(Date(tick)),
                    x,
                    cob.bottom - 7f.dp,
                    8.5f,
                    SugarliciousColors.argb(SugarliciousColorRole.GRAPH_LABEL),
                    Paint.Align.CENTER,
                    bold = true,
                )
            }
            tick += interval
        }
    }

    private fun drawLane(
        canvas: Canvas,
        plot: RectF,
        points: List<TherapyHistorySample>,
        start: Long,
        end: Long,
        iob: Boolean,
        range: ToolkitMetabolicRange,
    ) {
        val actual = points.mapNotNull { point ->
            (if (iob) point.totalIob else point.cobGrams)?.takeIf { it.isFinite() }?.let { point.measuredAtEpochMs to it }
        }.sortedBy { it.first }
        if (actual.isEmpty()) return
        fun y(value: Double) = mapSignedLogY(value, range.minimum, range.maximum, plot)
        val zeroY = y(0.0)
        val color = SugarliciousColors.argb(if (iob) SugarliciousColorRole.GRAPH_IOB else SugarliciousColorRole.GRAPH_COB)
        val area = Path().apply {
            moveTo(mapX(actual.first().first, start, end, plot), zeroY)
            actual.forEach { (time, value) -> lineTo(mapX(time, start, end, plot), y(value)) }
            lineTo(mapX(actual.last().first, start, end, plot), zeroY)
            close()
        }
        fillPaint.shader = LinearGradient(0f, plot.top, 0f, plot.bottom, withAlpha(color, 112), withAlpha(color, 7), Shader.TileMode.CLAMP)
        canvas.drawPath(area, fillPaint)
        fillPaint.shader = null
        linePaint.color = color
        linePaint.strokeWidth = 2.35f.dp
        linePaint.pathEffect = null
        canvas.drawPath(valuePath(actual, start, end, plot, ::y), linePaint)
        val labelX = plot.right - 7f.dp
        val labelColor = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_LABEL)
        drawText(canvas, formatMetabolicScale(range.maximum), labelX, plot.top + 12f.dp, 8f, labelColor, Paint.Align.RIGHT)
        drawText(canvas, "0", labelX, (zeroY - 3f.dp).coerceIn(plot.top + 12f.dp, plot.bottom - 7f.dp), 8f, labelColor, Paint.Align.RIGHT)
        if (range.minimum < -0.01) drawText(canvas, formatMetabolicScale(range.minimum), labelX, plot.bottom - 7f.dp, 8f, labelColor, Paint.Align.RIGHT)
    }

    private fun drawInsulinActivity(
        canvas: Canvas,
        plot: RectF,
        allPoints: List<TherapyHistorySample>,
        visiblePoints: List<TherapyHistorySample>,
        start: Long,
        end: Long,
        sharedZeroRatio: Double,
    ) {
        val actual = visiblePoints.mapNotNull { point ->
            point.insulinActivityUnitsPerMinute?.takeIf { it.isFinite() && it >= 0.0 }?.let { point.measuredAtEpochMs to it }
        }.sortedBy { it.first }
        if (actual.size < 2) return
        val maximum = allPoints.mapNotNull { it.insulinActivityUnitsPerMinute }
            .filter { it.isFinite() && it >= 0.0 }
            .maxOrNull()?.times(TOOLKIT_ACTIVITY_SCALE_FACTOR)?.coerceAtLeast(0.000001) ?: return
        val minimum = toolkitMinimumForZeroRatio(maximum, sharedZeroRatio)
        fun y(value: Double) = mapSignedLogY(value, minimum, maximum, plot)
        val smoothed = smoothSeries(actual)
        linePaint.color = Color.rgb(242, 201, 76)
        linePaint.strokeWidth = 1.6f.dp
        linePaint.pathEffect = null
        canvas.drawPath(smoothValuePath(smoothed, start, end, plot, ::y), linePaint)
    }

    private fun drawFutureLane(
        canvas: Canvas,
        plot: RectF,
        values: List<Pair<Long, Double>>,
        start: Long,
        end: Long,
        range: ToolkitMetabolicRange,
        color: Int,
    ) {
        if (values.size < 2) return
        fun y(value: Double) = mapSignedLogY(value, range.minimum, range.maximum, plot)
        linePaint.color = withAlpha(color, 210)
        linePaint.strokeWidth = 1.8f.dp
        linePaint.pathEffect = DashPathEffect(floatArrayOf(5f.dp, 4f.dp), 0f)
        canvas.drawPath(smoothValuePath(values, start, end, plot, ::y), linePaint)
        linePaint.pathEffect = null
    }

    private fun drawSmbMarkers(canvas: Canvas, plot: RectF, points: List<TherapyHistorySample>, start: Long, end: Long, zeroY: Float) {
        val markers = points.mapNotNull { point -> point.smbUnits?.takeIf { it > 0.0 }?.let { point.measuredAtEpochMs to it } }
        if (markers.isEmpty()) return
        fillPaint.color = Color.rgb(42, 202, 186)
        markers.forEach { (time, units) ->
            val side = toolkitSmbMarkerSide(units).dp
            val halfWidth = side / 2f
            val markerHeight = side * (sqrt(3.0).toFloat() / 2f)
            val x = mapX(time, start, end, plot).coerceIn(plot.left + halfWidth, plot.right - halfWidth)
            val baseY = (zeroY + markerHeight).coerceAtMost(plot.bottom - 1f.dp)
            canvas.drawPath(roundedUpTriangle(x, baseY, halfWidth, markerHeight, 1.6f.dp), fillPaint)
        }
    }

    private val Float.dp get() = this * density
}

internal data class ToolkitMetabolicRange(val minimum: Double, val maximum: Double) {
    val zeroRatio: Double get() = ((0.0 - minimum) / (maximum - minimum).coerceAtLeast(0.000001)).coerceIn(0.01, 0.95)
}

internal fun toolkitMetabolicRange(values: List<Double>, sharedZeroRatio: Double? = null): ToolkitMetabolicRange {
    val finite = values.filter { it.isFinite() }
    val referenceMaximum = finite.filter { it >= 0.0 }.maxOrNull() ?: 0.0
    val referenceMinimum = min(0.0, finite.minOrNull() ?: 0.0)
    val maximum = max(0.01, referenceMaximum * 1.08)
    val minimum = sharedZeroRatio?.let { toolkitMinimumForZeroRatio(maximum, it) } ?: if (referenceMinimum < 0.0) referenceMinimum * 1.08 else -maximum * 0.08
    return ToolkitMetabolicRange(minimum, maximum)
}

internal fun toolkitMinimumForZeroRatio(maximum: Double, zeroRatio: Double): Double {
    val ratio = zeroRatio.coerceIn(0.01, 0.95)
    return -(ratio * maximum.coerceAtLeast(0.000001)) / (1.0 - ratio).coerceAtLeast(0.01)
}

internal fun buildIobProjection(points: List<TherapyHistorySample>, now: Long, end: Long): List<Pair<Long, Double>> {
    if (end <= now) return emptyList()
    val actual = points.mapNotNull { point -> point.totalIob?.takeIf { it.isFinite() }?.let { point.measuredAtEpochMs to it } }
    val latest = actual.filter { it.first <= now }.maxByOrNull { it.first } ?: return emptyList()
    val negativeSlope = recentNegativeSlope(actual, now) ?: return emptyList()
    return buildList {
        var time = now
        while (time <= end) {
            val minutes = (time - now) / 60_000.0
            add(time to max(0.0, latest.second + negativeSlope * minutes))
            time += 5 * 60_000L
        }
    }
}

internal fun buildCobProjection(points: List<TherapyHistorySample>, now: Long, end: Long): List<Pair<Long, Double>> {
    if (end <= now) return emptyList()
    val actual = points.mapNotNull { point -> point.cobGrams?.takeIf { it.isFinite() && it >= 0.0 }?.let { point.measuredAtEpochMs to it } }
    val latest = actual.filter { it.first <= now }.maxByOrNull { it.first } ?: return emptyList()
    val negativeSlope = recentNegativeSlope(actual, now) ?: return emptyList()
    return buildList {
        var time = now
        while (time <= end) {
            val minutes = (time - now) / 60_000.0
            add(time to max(0.0, latest.second + negativeSlope * minutes))
            time += 5 * 60_000L
        }
    }
}

private fun recentNegativeSlope(values: List<Pair<Long, Double>>, now: Long): Double? {
    val slopes = values.filter { it.first in (now - 60L * 60_000L)..now }.sortedBy { it.first }.zipWithNext().mapNotNull { (first, second) ->
        val minutes = (second.first - first.first) / 60_000.0
        if (minutes !in 2.0..20.0) return@mapNotNull null
        ((second.second - first.second) / minutes).takeIf { it.isFinite() && it < 0.0 }
    }
    if (slopes.isEmpty()) return null
    val sorted = slopes.sorted()
    return sorted[sorted.size / 2]
}

internal fun toolkitSmbMarkerSide(units: Double): Float = when {
    abs(units) <= 0.1 -> 9f
    abs(units) < 0.5 -> 12f
    else -> 15f
}

internal fun glucoseLogRatio(valueMgDl: Double): Double {
    val value = valueMgDl.coerceIn(0.0, GLUCOSE_DISPLAY_MAX)
    return when {
        value <= 80.0 -> GLUCOSE_ZERO_RATIO + value / 80.0 * (GLUCOSE_LOW_RATIO - GLUCOSE_ZERO_RATIO)
        value <= 160.0 -> GLUCOSE_LOW_RATIO + (ln(value / 80.0) / ln(2.0)) * (GLUCOSE_TARGET_HIGH_RATIO - GLUCOSE_LOW_RATIO)
        else -> GLUCOSE_TARGET_HIGH_RATIO + (ln(value / 160.0) / ln(GLUCOSE_DISPLAY_MAX / 160.0)) * (1.0 - GLUCOSE_TARGET_HIGH_RATIO)
    }.coerceIn(GLUCOSE_ZERO_RATIO, 1.0)
}

private fun mapGlucoseY(valueMgDl: Double, plot: RectF): Float = plot.bottom - glucoseLogRatio(valueMgDl).toFloat() * plot.height()

private fun windowedStepSamples(points: List<TherapyHistorySample>, start: Long, end: Long): List<TherapyHistorySample> {
    if (points.isEmpty()) return emptyList()
    val seed = points.lastOrNull { it.measuredAtEpochMs <= start }
    val visible = points.filter { it.measuredAtEpochMs in (start + 1)..end }
    val combined = buildList {
        seed?.let { add(it.copy(measuredAtEpochMs = start)) }
        addAll(visible)
    }.toMutableList()
    if (combined.isEmpty()) return emptyList()
    if (combined.first().measuredAtEpochMs > start) combined.add(0, combined.first().copy(measuredAtEpochMs = start))
    if (combined.last().measuredAtEpochMs < end) combined += combined.last().copy(measuredAtEpochMs = end)
    return combined
}

private fun effectiveBasal(sample: TherapyHistorySample): Double = sample.tempBasalUnitsPerHour ?: sample.basalUnitsPerHour ?: sample.baseBasalUnitsPerHour ?: 0.0

private fun stepPath(
    values: List<Pair<Long, Double>>,
    start: Long,
    end: Long,
    plot: RectF,
    mapValue: (Double) -> Float,
    closeAt: Float? = null,
): Path = Path().apply {
    if (values.isEmpty()) return@apply
    val firstX = mapX(values.first().first, start, end, plot)
    if (closeAt != null) moveTo(firstX, closeAt) else moveTo(firstX, mapValue(values.first().second))
    if (closeAt != null) lineTo(firstX, mapValue(values.first().second))
    var priorY = mapValue(values.first().second)
    values.drop(1).forEach { (time, value) ->
        val x = mapX(time, start, end, plot)
        lineTo(x, priorY)
        priorY = mapValue(value)
        lineTo(x, priorY)
    }
    if (closeAt != null) {
        lineTo(mapX(values.last().first, start, end, plot), closeAt)
        close()
    }
}

/**
 * Builds AAPS-style step points: every target change contributes two points at the same time,
 * producing the vertical transition between the two horizontal target sections. Real gaps stay
 * gaps so missing target history is never silently invented.
 */
internal fun targetStepPaths(
    samples: List<TargetSample>,
    start: Long,
    end: Long,
    continuityToleranceMs: Long = 90_000L,
): List<List<Pair<Long, Double>>> = TargetStepTimeline.build(samples, start, end, continuityToleranceMs)

private fun valuePath(values: List<Pair<Long, Double>>, start: Long, end: Long, plot: RectF, mapValue: (Double) -> Float): Path = Path().apply {
    values.forEachIndexed { index, (time, value) ->
        val x = mapX(time, start, end, plot)
        val y = mapValue(value)
        if (index == 0) moveTo(x, y) else lineTo(x, y)
    }
}

private fun timeGridIntervalMs(hours: Float): Long = when {
    hours <= 2f -> 30L * 60_000L
    hours <= 4f -> 60L * 60_000L
    hours <= 8f -> 2L * HOUR_MS
    hours <= 16f -> 4L * HOUR_MS
    else -> 6L * HOUR_MS
}

private fun firstAlignedTick(start: Long, interval: Long): Long {
    val remainder = ((start % interval) + interval) % interval
    return if (remainder == 0L) start else start + interval - remainder
}

private fun signedLogTransform(value: Double, linearScale: Double): Double = when {
    value > 0.0 -> ln(1.0 + value / linearScale)
    value < 0.0 -> -ln(1.0 + abs(value) / linearScale)
    else -> 0.0
}

private fun mapSignedLogY(value: Double, minValue: Double, maxValue: Double, plot: RectF): Float {
    val magnitude = max(abs(minValue), abs(maxValue)).coerceAtLeast(0.000001)
    val linearScale = (magnitude * 0.075).coerceAtLeast(0.000001)
    val transformedMin = signedLogTransform(minValue, linearScale)
    val transformedMax = signedLogTransform(maxValue, linearScale)
    val transformedValue = signedLogTransform(value, linearScale)
    val ratio = ((transformedValue - transformedMin) / (transformedMax - transformedMin).coerceAtLeast(0.000001)).coerceIn(0.0, 1.0)
    return plot.bottom - ratio.toFloat() * plot.height()
}

private fun smoothSeries(values: List<Pair<Long, Double>>, radius: Int = 2): List<Pair<Long, Double>> {
    if (values.size < 3 || radius <= 0) return values
    return values.mapIndexed { index, point ->
        val from = max(0, index - radius)
        val to = min(values.lastIndex, index + radius)
        var weightedSum = 0.0
        var weightSum = 0.0
        for (sampleIndex in from..to) {
            val distance = abs(sampleIndex - index)
            val weight = (radius + 1 - distance).toDouble()
            weightedSum += values[sampleIndex].second * weight
            weightSum += weight
        }
        point.first to (weightedSum / weightSum)
    }
}

private fun smoothValuePath(values: List<Pair<Long, Double>>, start: Long, end: Long, plot: RectF, mapValue: (Double) -> Float): Path = Path().apply {
    if (values.isEmpty()) return@apply
    val mapped = values.map { (time, value) -> mapX(time, start, end, plot) to mapValue(value) }
    moveTo(mapped.first().first, mapped.first().second)
    if (mapped.size == 2) {
        lineTo(mapped.last().first, mapped.last().second)
        return@apply
    }
    for (index in 1 until mapped.lastIndex) {
        val current = mapped[index]
        val next = mapped[index + 1]
        val midX = (current.first + next.first) / 2f
        val midY = (current.second + next.second) / 2f
        quadTo(current.first, current.second, midX, midY)
    }
    lineTo(mapped.last().first, mapped.last().second)
}

private fun formatMetabolicScale(value: Double): String = when {
    abs(value) >= 10.0 -> String.format(Locale.getDefault(), "%.0f", value)
    abs(value) >= 1.0 -> String.format(Locale.getDefault(), "%.1f", value)
    else -> String.format(Locale.getDefault(), "%.2f", value)
}

private fun buildActivityProjection(last: Pair<Long, Double>, projectionStart: Long, end: Long): List<Pair<Long, Double>> {
    if (end <= projectionStart || last.second <= 0.0) return emptyList()
    val duration = 3L * HOUR_MS
    return buildList {
        var time = projectionStart
        while (time <= min(end, projectionStart + duration)) {
            val elapsed = (time - projectionStart).toDouble() / duration
            add(time to last.second * (1.0 - elapsed).coerceAtLeast(0.0).pow(2.0))
            time += 5 * 60_000L
        }
    }
}

private fun roundedUpTriangle(cx: Float, baseY: Float, halfWidth: Float, height: Float, radius: Float): Path = Path().apply {
    val apexY = baseY - height
    moveTo(cx - halfWidth + radius, baseY)
    lineTo(cx + halfWidth - radius, baseY)
    quadTo(cx + halfWidth, baseY, cx + halfWidth - radius * 0.7f, baseY - radius)
    lineTo(cx + radius * 0.7f, apexY + radius)
    quadTo(cx, apexY, cx - radius * 0.7f, apexY + radius)
    lineTo(cx - halfWidth + radius * 0.7f, baseY - radius)
    quadTo(cx - halfWidth, baseY, cx - halfWidth + radius, baseY)
    close()
}

private fun mapX(time: Long, start: Long, end: Long, plot: RectF): Float =
    plot.left + ((time - start).toDouble() / (end - start).coerceAtLeast(1L) * plot.width()).toFloat()

private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

internal fun luminousTargetValueColor(targetBandColor: Int): Int = derivedTargetValueArgb(targetBandColor)

private fun View.drawText(
    canvas: Canvas,
    value: String,
    x: Float,
    y: Float,
    sizeSp: Float,
    color: Int,
    align: Paint.Align,
    bold: Boolean = false,
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics)
        this.color = color
        textAlign = align
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    canvas.drawText(value, x, y, paint)
}
