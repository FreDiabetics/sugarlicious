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
import android.view.VelocityTracker
import android.view.View
import android.widget.OverScroller
import androidx.core.graphics.withClip
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColors
import app.aapswear.mobile.ui.theme.derivedTargetValueArgb
import app.aapswear.model.CanonicalCgmHistory
import app.aapswear.model.CgmGraphPolicy
import app.aapswear.model.CgmGraphScaleMode
import app.aapswear.model.CgmGraphYScale
import app.aapswear.model.CgmRangeClass
import app.aapswear.model.CgmThresholds
import app.aapswear.model.Freshness
import app.aapswear.model.FreshnessPolicy
import app.aapswear.model.GlucoseGraphScale
import app.aapswear.model.GlucosePrediction
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.GraphAxis
import app.aapswear.model.GraphAxisScale
import app.aapswear.model.GraphBounds
import app.aapswear.model.GraphScaleSession
import app.aapswear.model.GraphTimeWindow
import app.aapswear.model.PredictionKind
import app.aapswear.model.RangeExcursion
import app.aapswear.model.RelativeGraphTimeAxis
import app.aapswear.model.TargetSample
import app.aapswear.model.TargetStepTimeline
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.TherapyEvent
import app.aapswear.model.TherapyEventKind
import app.aapswear.model.TherapyHistorySample
import app.aapswear.storage.PredictionDisplayTimeline
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val HOUR_MS = 60L * 60_000L
internal const val MAX_VISIBLE_GRAPH_HOURS = 24f
internal const val MIN_VISIBLE_HISTORY_HOURS = 1f
private const val BASAL_HEIGHT_FRACTION = 0.28f
private const val ACTIVITY_HEIGHT_FRACTION = 0.80f
private const val GRAPH_CORNER_RADIUS_DP = 18f
private const val GLUCOSE_DISPLAY_MAX = 400.0
private const val VALUE_AXIS_WIDTH_DP = 29f
private const val TIME_AXIS_HEIGHT_DP = 24f
private const val OVERVIEW_GRAPH_HOURS_MIGRATION = "graphHoursDefault3MigratedV5"
internal val OVERVIEW_GRAPH_HOUR_OPTIONS = listOf(1, 2, 3, 6, 12, 24)

internal fun sustainedRangeExcursion(
    samples: List<GlucoseSample>,
    lowMgDl: Double,
    highMgDl: Double,
): RangeExcursion? = CgmGraphPolicy.rangeExcursion(samples, lowMgDl, highMgDl)

internal class StaticGraphScaleStore(
    private val preferences: SharedPreferences,
) {
    fun restore(session: GraphScaleSession) {
        listOf(GraphAxis.IOB, GraphAxis.COB, GraphAxis.INSULIN_ACTIVITY).forEach { axis ->
            val minimumKey = key(axis, "minimum")
            val maximumKey = key(axis, "maximum")
            if (!preferences.contains(minimumKey) || !preferences.contains(maximumKey)) return@forEach
            val minimum = Double.fromBits(preferences.getLong(minimumKey, 0L))
            val maximum = Double.fromBits(preferences.getLong(maximumKey, 0L))
            runCatching { GraphBounds(minimum, maximum) }.getOrNull()?.let { session.useConfiguredBounds(axis, it) }
        }
    }

    fun persist(
        axis: GraphAxis,
        scale: GraphAxisScale,
    ) {
        if (scale.mode == CgmGraphScaleMode.DYNAMIC || scale.mode == CgmGraphScaleMode.LOGARITHMIC_DYNAMIC) return
        val bounds = scale.bounds
        preferences
            .edit()
            .putLong(key(axis, "minimum"), bounds.minimum.toBits())
            .putLong(key(axis, "maximum"), bounds.maximum.toBits())
            .apply()
    }

    private fun key(
        axis: GraphAxis,
        suffix: String,
    ) = "graph.static.${axis.name.lowercase(Locale.ROOT)}.$suffix"
}

internal fun availableGlucoseHistoryWindowMs(
    state: TherapyDisplayState?,
    nowEpochMs: Long,
): Long {
    val earliest =
        buildList {
            state?.glucoseHistory.orEmpty().forEach { sample ->
                if (sample.measuredAtEpochMs <= nowEpochMs) add(sample.measuredAtEpochMs)
            }
            state
                ?.glucose
                ?.measuredAtEpochMs
                ?.takeIf { it <= nowEpochMs }
                ?.let(::add)
        }.minOrNull() ?: return 0L
    return (nowEpochMs - earliest).coerceIn(0L, 24L * HOUR_MS)
}

internal fun resolveOverviewGraphHoursPreference(
    preferences: SharedPreferences,
    durationHours: Int,
): Int {
    val normalized = durationHours.takeIf { it in OVERVIEW_GRAPH_HOUR_OPTIONS } ?: 3
    if (preferences.getBoolean(OVERVIEW_GRAPH_HOURS_MIGRATION, false)) return normalized

    val previousAuto24 =
        preferences.getBoolean("graphHoursDefault24MigratedV4", false) &&
            preferences.getInt("graphHours", normalized) == 24
    val resolved =
        when {
            !preferences.contains("graphHours") -> 3
            previousAuto24 -> 3
            else -> normalized
        }
    preferences
        .edit()
        .putInt("graphHours", resolved)
        .putBoolean(OVERVIEW_GRAPH_HOURS_MIGRATION, true)
        .apply()
    return resolved
}

/**
 * The divider is a visual time marker only. Dots keep their timestamp-derived X coordinate instead
 * of being pushed away from Now, matching AndroidAPS' continuous time-axis behaviour.
 */
internal fun graphCenterBeforeDivider(
    dividerX: Float,
    radiusPx: Float,
    outlineWidthPx: Float,
    safetyPx: Float,
): Float = dividerX

internal fun graphCenterAfterDivider(
    dividerX: Float,
    radiusPx: Float,
    outlineWidthPx: Float,
    safetyPx: Float,
): Float = dividerX

internal data class GraphViewportSnapshot(
    val startEpochMs: Long,
    val liveEdgeEpochMs: Long,
    val endEpochMs: Long,
) {
    val durationMs: Long get() = endEpochMs - startEpochMs
    val visibleHours: Float get() = durationMs.toFloat() / HOUR_MS
}

internal data class GraphViewportSavedState(
    val historyHours: Float,
    val navigationEndEpochMs: Long?,
)

internal class ChartViewport(
    initialHours: Int,
) {
    enum class Mode { LIVE_FOLLOW, USER_NAVIGATING }

    private val listeners = LinkedHashSet<() -> Unit>()
    private var availablePastWindowMs = 24L * HOUR_MS
    private var requestedHours = initialHours.toFloat().coerceIn(MIN_VISIBLE_HISTORY_HOURS, MAX_VISIBLE_GRAPH_HOURS)
    var hours = requestedHours
        private set
    var panMs = 0L
        private set
    var futureWindowMs = 0L
        private set
    var mode: Mode = Mode.LIVE_FOLLOW
        private set
    private var navigationEndEpochMs: Long? = null
    var axisIntervalHours: Int? = null
        private set

    val visibleHours: Float get() = hours + futureWindowMs.toFloat() / HOUR_MS

    fun setHours(
        value: Float,
        resetPan: Boolean = false,
    ) {
        requestedHours = value.coerceIn(MIN_VISIBLE_HISTORY_HOURS, MAX_VISIBLE_GRAPH_HOURS)
        val next = requestedHours.coerceAtMost(maximumHours())
        val changed = next != hours || (resetPan && panMs != 0L)
        hours = next
        if (resetPan) {
            panMs = 0L
            mode = Mode.LIVE_FOLLOW
            navigationEndEpochMs = null
        }
        clampNavigation(System.currentTimeMillis())
        if (changed) notifyChanged()
    }

    fun setFutureWindow(
        valueMs: Long,
        referenceNow: Long = System.currentTimeMillis(),
    ) {
        val next = valueMs.coerceIn(0L, 2L * HOUR_MS)
        if (next == futureWindowMs) return
        futureWindowMs = next
        hours = requestedHours.coerceAtMost(maximumHours())
        clampNavigation(referenceNow)
        notifyChanged()
    }

    fun setAvailablePastWindow(
        valueMs: Long,
        referenceNow: Long = System.currentTimeMillis(),
    ) {
        val next = valueMs.coerceIn(0L, MAX_VISIBLE_GRAPH_HOURS.toLong() * HOUR_MS)
        if (next == availablePastWindowMs) return
        availablePastWindowMs = next
        hours = requestedHours.coerceAtMost(maximumHours())
        clampNavigation(referenceNow)
        notifyChanged()
    }

    fun endEpochMs(now: Long): Long = snapshot(now).endEpochMs

    fun snapshot(now: Long): GraphViewportSnapshot {
        val liveEnd = now + futureWindowMs
        val earliestEnd = now - availablePastWindowMs + (hours * HOUR_MS).toLong() + futureWindowMs
        val requestedEnd = if (mode == Mode.USER_NAVIGATING) navigationEndEpochMs ?: liveEnd else liveEnd
        val end = requestedEnd.coerceIn(minOf(earliestEnd, liveEnd), liveEnd)
        val start = end - (visibleHours * HOUR_MS).toLong()
        return GraphViewportSnapshot(start, now, end)
    }

    fun beginScale() {
        axisIntervalHours = RelativeGraphTimeAxis.intervalHours(visibleHours.toDouble())
    }

    fun endScale() {
        axisIntervalHours = null
        notifyChanged()
    }

    fun zoom(
        scaleFactor: Float,
        focalFraction: Float = 1f,
        referenceNow: Long = System.currentTimeMillis(),
    ) {
        val oldSnapshot = snapshot(referenceNow)
        val oldHours = hours
        requestedHours = (hours / scaleFactor.coerceAtLeast(0.05f)).coerceIn(MIN_VISIBLE_HISTORY_HOURS, MAX_VISIBLE_GRAPH_HOURS)
        hours = requestedHours.coerceAtMost(maximumHours())
        if (hours != oldHours) {
            val focal = focalFraction.coerceIn(0f, 1f)
            val focalEpochMs = oldSnapshot.startEpochMs + (oldSnapshot.durationMs * focal).toLong()
            val desiredEnd = focalEpochMs + ((visibleHours * HOUR_MS) * (1f - focal)).toLong()
            navigateToEnd(desiredEnd, referenceNow)
            notifyChanged()
        }
    }

    fun pan(
        deltaPixels: Float,
        width: Float,
        referenceNow: Long = System.currentTimeMillis(),
    ): Boolean {
        if (width <= 0f) return false
        val oldEnd = snapshot(referenceNow).endEpochMs
        val deltaMs = (deltaPixels / width * visibleHours * HOUR_MS).toLong()
        navigateToEnd(oldEnd - deltaMs, referenceNow)
        if (snapshot(referenceNow).endEpochMs != oldEnd) {
            notifyChanged()
            return true
        }
        return false
    }

    fun returnToNow() {
        if (panMs == 0L && mode == Mode.LIVE_FOLLOW) return
        panMs = 0L
        mode = Mode.LIVE_FOLLOW
        navigationEndEpochMs = null
        notifyChanged()
    }

    fun savedState(referenceNow: Long = System.currentTimeMillis()): GraphViewportSavedState =
        GraphViewportSavedState(
            historyHours = requestedHours,
            navigationEndEpochMs = snapshot(referenceNow).endEpochMs.takeIf { mode == Mode.USER_NAVIGATING },
        )

    fun restore(
        savedState: GraphViewportSavedState,
        referenceNow: Long = System.currentTimeMillis(),
    ) {
        requestedHours = savedState.historyHours.coerceIn(MIN_VISIBLE_HISTORY_HOURS, MAX_VISIBLE_GRAPH_HOURS)
        hours = requestedHours.coerceAtMost(maximumHours())
        savedState.navigationEndEpochMs?.let { navigateToEnd(it, referenceNow) } ?: returnToNow()
        clampNavigation(referenceNow)
        notifyChanged()
    }

    internal fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    internal fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun notifyChanged() {
        listeners.toList().forEach { it() }
    }

    private fun maximumHours(): Float =
        minOf(
            availablePastWindowMs.toFloat() / HOUR_MS,
            MAX_VISIBLE_GRAPH_HOURS - futureWindowMs.toFloat() / HOUR_MS,
        ).coerceAtLeast(MIN_VISIBLE_HISTORY_HOURS)

    private fun navigateToEnd(
        desiredEndEpochMs: Long,
        referenceNow: Long,
    ) {
        val liveEnd = referenceNow + futureWindowMs
        val earliestEnd = referenceNow - availablePastWindowMs + (hours * HOUR_MS).toLong() + futureWindowMs
        val resolvedEnd = desiredEndEpochMs.coerceIn(minOf(earliestEnd, liveEnd), liveEnd)
        panMs = resolvedEnd - liveEnd
        navigationEndEpochMs = resolvedEnd.takeIf { it < liveEnd }
        mode = if (navigationEndEpochMs == null) Mode.LIVE_FOLLOW else Mode.USER_NAVIGATING
    }

    private fun clampNavigation(referenceNow: Long) {
        val desiredEnd = navigationEndEpochMs ?: (referenceNow + futureWindowMs + panMs)
        navigateToEnd(desiredEnd, referenceNow)
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
    private var velocityTracker: VelocityTracker? = null
    private val flingScroller = OverScroller(context)
    private var flingX = 0
    private val viewportListener: () -> Unit = { invalidate() }
    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    viewport.beginScale()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    viewport.zoom(detector.scaleFactor, detector.focusX / width.coerceAtLeast(1), System.currentTimeMillis())
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    viewport.endScale()
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
                flingScroller.forceFinished(true)
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
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
                velocityTracker?.addMovement(event)
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
                if (event.actionMasked == MotionEvent.ACTION_UP && moving) {
                    velocityTracker?.apply {
                        addMovement(event)
                        computeCurrentVelocity(1000)
                        val velocity = xVelocity.toInt()
                        if (abs(velocity) >= 250) {
                            flingX = 0
                            flingScroller.fling(0, 0, velocity, 0, Int.MIN_VALUE, Int.MAX_VALUE, 0, 0)
                            postInvalidateOnAnimation()
                        }
                    }
                }
                velocityTracker?.recycle()
                velocityTracker = null
                if (!moving) performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun computeScroll() {
        if (!flingScroller.computeScrollOffset()) return
        val nextX = flingScroller.currX
        val moved = viewport.pan((nextX - flingX).toFloat(), width.toFloat())
        flingX = nextX
        if (moved) postInvalidateOnAnimation() else flingScroller.forceFinished(true)
    }
}

@SuppressLint("DrawAllocation")
internal class GlucoseDashboardChart
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        sharedViewport: ChartViewport = ChartViewport(6),
    ) : InteractiveChartView(context, attrs, sharedViewport) {
        private val density = resources.displayMetrics.density
        private val linePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val dotOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private var state: TherapyDisplayState? = null
        private var unit = GlucoseUnit.MG_DL
        private var showPredictions = false
        private var showTargetRange = true
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
        private var graphMaximumMgDl = GLUCOSE_DISPLAY_MAX
        private var graphMinimumMgDl = CgmGraphYScale.DEFAULT_MINIMUM_MG_DL
        private var graphScaleMode = CgmGraphScaleMode.LOGARITHMIC
        private var renderedYScale: CgmGraphYScale? = null
        private val staticScaleStore = StaticGraphScaleStore(context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE))
        private val axisScaleSession = GraphScaleSession().also(staticScaleStore::restore)
        private var stateSignature: List<Any?>? = null
        private var clockBucket: Long = Long.MIN_VALUE
        private var renderNowEpochMs: Long = System.currentTimeMillis()
        private var boundDurationHours: Int? = null

        fun bind(
            state: TherapyDisplayState?,
            unit: GlucoseUnit,
            showPredictions: Boolean,
            durationHours: Int,
            showTargetRange: Boolean = true,
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
            graphMaximumMgDl: Double = GLUCOSE_DISPLAY_MAX,
            graphMinimumMgDl: Double = CgmGraphYScale.DEFAULT_MINIMUM_MG_DL,
            graphScaleMode: CgmGraphScaleMode = CgmGraphScaleMode.LOGARITHMIC,
            clockEpochMs: Long = System.currentTimeMillis(),
        ) {
            val resolvedRadius = cgmDotRadiusDp.coerceIn(1.5f, 6.0f)
            val resolvedOutlineWidth = cgmDotOutlineWidthDp.coerceIn(0.25f, 3.0f)
            val stylePreferences = context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE)
            val resolvedDurationHours = resolveOverviewGraphHoursPreference(stylePreferences, durationHours)
            val resolvedPredictionRadius = readMobilePredictionDotRadius(stylePreferences)
            val resolvedPredictionOutlineWidth = readMobilePredictionDotOutlineWidth(stylePreferences)
            val resolvedGraphMaximum = graphMaximumMgDl.coerceIn(180.0, 600.0)
            val resolvedGraphMinimum = graphMinimumMgDl.coerceIn(20.0, resolvedGraphMaximum - 20.0)
            val resolvedClockBucket = clockEpochMs / CLOCK_REFRESH_MS
            viewport.setAvailablePastWindow(availableGlucoseHistoryWindowMs(state, clockEpochMs), clockEpochMs)
            val newStateSignature =
                state?.let {
                    buildList {
                        add(it.source)
                        add(
                            it.glucose?.let { glucose ->
                                listOf(
                                    glucose.measuredAtEpochMs,
                                    glucose.receivedAtEpochMs,
                                    glucose.valueMgDl,
                                    glucose.source,
                                    glucose.sensorId,
                                    glucose.sessionId,
                                    glucose.sequenceNumber,
                                )
                            },
                        )
                        add(
                            it.glucoseHistory.map { sample ->
                                listOf(
                                    sample.measuredAtEpochMs,
                                    sample.receivedAtEpochMs,
                                    sample.valueMgDl,
                                    sample.source,
                                    sample.sensorId,
                                    sample.sessionId,
                                    sample.sequenceNumber,
                                )
                            },
                        )
                        add(
                            it.glucosePredictions.map { series ->
                                series.kind to series.samples.map { sample -> sample.measuredAtEpochMs to sample.valueMgDl }
                            },
                        )
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
                    this.graphMaximumMgDl != resolvedGraphMaximum ||
                    this.graphMinimumMgDl != resolvedGraphMinimum ||
                    this.graphScaleMode != graphScaleMode ||
                    clockBucket != resolvedClockBucket

            if (!changed) return

            this.state = state
            stateSignature = newStateSignature
            boundDurationHours = resolvedDurationHours
            this.unit = unit
            this.showPredictions = showPredictions
            // The CGM target range is a permanent part of the Sugarlicious graph. The parameter is
            // retained for binary/source compatibility with older callers, but is intentionally ignored.
            this.showTargetRange = true
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
            this.graphMaximumMgDl = resolvedGraphMaximum
            this.graphMinimumMgDl = resolvedGraphMinimum
            this.graphScaleMode = graphScaleMode
            clockBucket = resolvedClockBucket
            renderNowEpochMs = clockEpochMs

            if (durationChanged) viewport.setHours(resolvedDurationHours.toFloat(), resetPan = true)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val outlineInset = 0.5f.dp
            val targetScaleOnRight = targetScaleOnRight(showPredictions, showTargetValue, showBasal, showActivity)
            val bounds =
                mobileCgmGraphBounds(
                    width.toFloat(),
                    height.toFloat(),
                    outlineInset,
                    TIME_AXIS_HEIGHT_DP.dp,
                    VALUE_AXIS_WIDTH_DP.dp,
                    targetScaleOnRight,
                )
            val scaleContainer = bounds.tile
            val plot = bounds.plot
            val contentBounds = bounds.content
            if (plot.width() <= 24f || plot.height() <= 24f) return
            val radius = GRAPH_CORNER_RADIUS_DP.dp
            val contentClip = Path().apply { addRoundRect(contentBounds, radius + outlineInset, radius + outlineInset, Path.Direction.CW) }

            canvas.withClip(contentClip) {
                fillPaint.color = SugarliciousColors.argb(SugarliciousColorRole.SURFACE)
                canvas.drawRect(contentBounds, fillPaint)
                fillPaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_BACKGROUND)
                canvas.drawRoundRect(plot, radius, radius, fillPaint)

                val now = renderNowEpochMs
                val thresholds = CgmThresholdPreferences.read(context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE))
                val targetLow = thresholds.lowMgDl
                val targetHigh = thresholds.highMgDl
                val freshness = FreshnessPolicy.classify(state?.glucose?.measuredAtEpochMs, now)
                val signalLost = !TherapyDisplayFormatter.isGlucoseDisplayable(state, now)
                val predictions =
                    if (showPredictions) {
                        state?.glucosePredictions.orEmpty().filter {
                            predictionEnabled(
                                it.kind,
                            )
                        }
                    } else {
                        emptyList()
                    }
                // Like AAPS, the viewport is tied to real current time. A new CGM therefore advances
                // the same time axis instead of pinning the latest point while neighbours get squeezed.
                val liveEdge = now
                val viewportSnapshot = viewport.snapshot(liveEdge)
                val timeWindow =
                    GraphTimeWindow(
                        startEpochMs = viewportSnapshot.startEpochMs,
                        liveEdgeEpochMs = viewportSnapshot.liveEdgeEpochMs,
                        endEpochMs = viewportSnapshot.endEpochMs,
                    )
                val start = timeWindow.startEpochMs
                val end = timeWindow.endEpochMs
                val allHistory =
                    CanonicalCgmHistory.merge(
                        samples =
                            buildList {
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
                val visiblePredictions =
                    PredictionDisplayTimeline
                        .anchor(predictions, now)
                        .map { series -> series.copy(samples = series.samples.filter { it.measuredAtEpochMs in start..end }) }
                        .filter { it.samples.isNotEmpty() }
                // Range classification is a Y-axis background, not a time series. AndroidAPS keeps
                // the complete visible axis classified, including the prediction side.
                val targetLeft = plot.left
                val targetRight = plot.right
                val proposedYScale =
                    CgmGraphYScale.resolve(
                        mode = graphScaleMode,
                        visibleValuesMgDl =
                            buildList {
                                addAll(history.map(GlucoseSample::valueMgDl))
                                addAll(visiblePredictions.flatMap { it.samples }.map(GlucoseSample::valueMgDl))
                            },
                        staticMinimumMgDl = graphMinimumMgDl,
                        staticMaximumMgDl = graphMaximumMgDl,
                        requiredValuesMgDl = listOf(targetLow, targetHigh),
                    )
                val yScale = stableCgmScale(renderedYScale, proposedYScale).also { renderedYScale = it }
                val targetTop = mapGlucoseY(targetHigh, plot, yScale)
                val targetBottom = mapGlucoseY(targetLow, plot, yScale)
                // Signal loss changes freshness only. The last confirmed range excursion remains
                // active until a new validated CGM value performs a real range transition.
                val excursion = sustainedRangeExcursion(allHistory, targetLow, targetHigh)
                val graphSave = canvas.save()
                canvas.clipPath(Path().apply { addRoundRect(plot, radius, radius, Path.Direction.CW) })

                if (showTargetRange && targetLeft < targetRight) {
                    if (excursion == RangeExcursion.HIGH) {
                        fillPaint.color = SugarliciousColors.argb(SugarliciousColorRole.RANGE_HIGH)
                        canvas.drawRect(targetLeft, plot.top, targetRight, targetTop, fillPaint)
                    }
                    fillPaint.color = SugarliciousColors.argb(SugarliciousColorRole.RANGE_IN_RANGE)
                    canvas.drawRect(targetLeft, targetTop, targetRight, targetBottom, fillPaint)
                    if (excursion == RangeExcursion.LOW) {
                        fillPaint.color = SugarliciousColors.argb(SugarliciousColorRole.RANGE_LOW)
                        canvas.drawRect(targetLeft, targetBottom, targetRight, plot.bottom, fillPaint)
                    }
                }

                if (signalLost) {
                    val signalStart =
                        state
                            ?.glucose
                            ?.measuredAtEpochMs
                            ?.let { mapX(it, start, end, plot) }
                            ?.coerceIn(plot.left, plot.right) ?: plot.left
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

                if (showTargetRange && targetLeft < targetRight) {
                    linePaint.strokeWidth = 1f.dp
                    linePaint.strokeCap = Paint.Cap.BUTT
                    linePaint.pathEffect = null
                    linePaint.color = opaqueGraphBoundaryColor(SugarliciousColors.argb(SugarliciousColorRole.GRAPH_HIGH_LINE))
                    canvas.drawLine(targetLeft, targetTop, targetRight, targetTop, linePaint)
                    linePaint.color = opaqueGraphBoundaryColor(SugarliciousColors.argb(SugarliciousColorRole.GRAPH_LOW_LINE))
                    canvas.drawLine(targetLeft, targetBottom, targetRight, targetBottom, linePaint)
                    linePaint.strokeCap = Paint.Cap.ROUND
                }

                if (showTargetValue) {
                    val currentTarget = state?.target?.valueMgDl?.takeIf { it.isFinite() && it in 20.0..1_000.0 }
                    val targetSegments =
                        state
                            ?.targetHistory
                            .orEmpty()
                            .asSequence()
                            .filter {
                                it.valueMgDl.isFinite() &&
                                    it.valueMgDl in 20.0..1_000.0 &&
                                    it.startedAtEpochMs <= end &&
                                    it.endsAtEpochMs >= start
                            }.map { sample ->
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
                            }.toList()
                            .ifEmpty {
                                currentTarget
                                    ?.let { value ->
                                        val observedAt = state?.glucose?.measuredAtEpochMs ?: state?.receivedAtEpochMs ?: now
                                        val firstObservedAt = history.minOfOrNull { it.measuredAtEpochMs } ?: observedAt
                                        val temporary = state?.target?.temporary == true
                                        val explicitEnd = state?.target?.endsAtEpochMs?.takeIf { temporary }
                                        if (explicitEnd != null && explicitEnd <= observedAt) {
                                            emptyList()
                                        } else {
                                            listOf(
                                                app.aapswear.model.TargetSample(
                                                    valueMgDl = value,
                                                    // Keep the fallback target attached to actual data time. Using the
                                                    // viewport start makes the dash pattern a sticky screen overlay.
                                                    startedAtEpochMs = minOf(firstObservedAt, observedAt),
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
                    targetStepPaths(targetSegments, start, end).forEach { points ->
                        val dashLength = 3f.dp
                        val firstX = points.firstOrNull()?.let { mapX(it.first, start, end, plot) } ?: plot.left
                        linePaint.pathEffect =
                            DashPathEffect(
                                floatArrayOf(dashLength, dashLength),
                                contentAnchoredDashPhase(firstX, dashLength * 2f),
                            )
                        canvas.drawPath(
                            valuePath(points, start, end, plot) { value -> mapGlucoseY(value, plot, yScale) },
                            linePaint,
                        )
                    }
                    linePaint.pathEffect = null
                }

                if (showBasal) drawBasal(canvas, plot, start, end, state?.therapyHistory.orEmpty())
                if (showActivity) {
                    drawInsulinActivity(
                        canvas,
                        plot,
                        start,
                        end,
                        now,
                        state?.therapyHistory.orEmpty(),
                        graphScaleMode,
                    )
                }

                // The live marker belongs to the clock, not to the last packet. A delayed packet must
                // remain at its measurement timestamp while the whole history keeps moving left.
                val liveTimestamp = timeWindow.liveEdgeEpochMs
                val liveX = timeWindow.plotX(liveTimestamp, plot.left, plot.width())
                // This is a data-space timestamp, not a sticky viewport overlay. It may leave the plot.
                val dividerX = liveX
                val futureLaneVisible = end > now && now in start..end
                val hasCgmOverlay = showTargetValue || showBasal || showActivity || visiblePredictions.isNotEmpty()
                if (now in start..end && hasCgmOverlay) {
                    linePaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_NOW_LINE)
                    linePaint.strokeWidth = 1f.dp
                    linePaint.pathEffect = DashPathEffect(floatArrayOf(4f.dp, 4f.dp), 0f)
                    canvas.drawLine(dividerX, plot.top, dividerX, plot.bottom, linePaint)
                    linePaint.pathEffect = null
                }

                history.forEachIndexed { index, point ->
                    val mappedX = timeWindow.plotX(point.measuredAtEpochMs, plot.left, plot.width())
                    val y = mapGlucoseY(point.valueMgDl, plot, yScale)
                    val current = index == history.lastIndex
                    val dotRadius = (cgmDotRadiusDp + if (current) 0.1f else 0f).dp
                    val outlineWidth = if (cgmDotOutlineEnabled) cgmDotOutlineWidthDp.dp else 0f
                    // Never collapse timestamp positions onto a radius-dependent edge. The rounded
                    // plot clip owns edge clipping; X remains a pure function of timestamp + viewport.
                    // "Current" changes only the dot styling. Its position still belongs to the
                    // reading timestamp, so the gap to the clock grows naturally between readings.
                    val x = mappedX
                    fillPaint.color = dotColor(point.valueMgDl, thresholds)
                    canvas.drawCircle(x, y, dotRadius, fillPaint)
                    if (cgmDotOutlineEnabled) {
                        dotOutlinePaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_CURRENT_OUTLINE)
                        dotOutlinePaint.strokeWidth = outlineWidth
                        canvas.drawCircle(x, y, dotRadius + outlineWidth / 2f, dotOutlinePaint)
                    }
                }

                if (visiblePredictions.isNotEmpty() && futureLaneVisible) {
                    visiblePredictions.forEach {
                        drawPrediction(canvas, it, plot, start, end, yScale)
                    }
                }

                canvas.restoreToCount(graphSave)
                drawGrid(canvas, plot, scaleContainer.bottom, start, end, liveTimestamp, liveX)

                if (showTargetRange && targetLeft < targetRight) {
                    drawTargetScale(
                        canvas,
                        glucoseLabel(targetHigh),
                        glucoseLabel(targetLow),
                        plot,
                        targetTop,
                        targetBottom,
                        targetScaleOnRight,
                    )
                }
                drawGraphMaximumScale(canvas, plot, targetScaleOnRight, yScale.maximumMgDl)

                if (history.size < 2) {
                    drawText(
                        canvas,
                        "Noch kein Verlauf",
                        plot.centerX(),
                        plot.centerY(),
                        10f,
                        SugarliciousColors.argb(SugarliciousColorRole.GRAPH_MUTED),
                        Paint.Align.CENTER,
                    )
                }
            }
            drawRoundedBorder(canvas, scaleContainer, radius)
        }

        private fun drawGrid(
            canvas: Canvas,
            plot: RectF,
            axisBottom: Float,
            start: Long,
            end: Long,
            now: Long,
            nowLineX: Float,
        ) {
            val ticks = RelativeGraphTimeAxis.ticks(start, end, now, viewport.axisIntervalHours)
            linePaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_AXIS_TICK)
            linePaint.strokeWidth = 1f.dp
            linePaint.pathEffect = null
            ticks.forEach { tick ->
                val isNow = tick.hoursBack == 0
                val x = if (isNow) nowLineX else mapX(tick.timestampEpochMs, start, end, plot)
                if (x < plot.left || x > plot.right) return@forEach
                val align =
                    when {
                        tick.timestampEpochMs <= start + 30_000L -> Paint.Align.LEFT
                        isNow -> Paint.Align.RIGHT
                        else -> Paint.Align.CENTER
                    }
                val labelX =
                    when (align) {
                        Paint.Align.LEFT -> plot.left + 3f.dp
                        Paint.Align.RIGHT -> currentTimeLabelAnchor(x, plot.right, 3f.dp)
                        else -> x
                    }
                val labelSize = if (isNow) 12f else 10f
                val tickX = x
                canvas.drawLine(tickX, plot.bottom + 2f.dp, tickX, plot.bottom + 8f.dp, linePaint)
                drawText(
                    canvas,
                    tick.label,
                    labelX,
                    axisBottom - 4f.dp,
                    labelSize,
                    SugarliciousColors.argb(SugarliciousColorRole.GRAPH_LABEL),
                    align,
                    bold = true,
                )
            }
        }

        private fun drawTargetScale(
            canvas: Canvas,
            highValue: String,
            lowValue: String,
            plot: RectF,
            targetTop: Float,
            targetBottom: Float,
            onRight: Boolean,
        ) {
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11.5f, resources.displayMetrics)
                    color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_LABEL)
                    textAlign = if (onRight) Paint.Align.LEFT else Paint.Align.RIGHT
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
            val metrics = paint.fontMetrics
            val highBaseline = targetTop - (metrics.ascent + metrics.descent) / 2f
            val lowBaseline = targetBottom - (metrics.ascent + metrics.descent) / 2f
            val tickGap = 2f.dp
            val tickLength = 5f.dp
            linePaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_AXIS_TICK)
            linePaint.strokeWidth = 1f.dp
            linePaint.pathEffect = null
            if (onRight) {
                canvas.drawLine(plot.right + tickGap, targetTop, plot.right + tickGap + tickLength, targetTop, linePaint)
                canvas.drawLine(plot.right + tickGap, targetBottom, plot.right + tickGap + tickLength, targetBottom, linePaint)
            } else {
                canvas.drawLine(plot.left - tickGap, targetTop, plot.left - tickGap - tickLength, targetTop, linePaint)
                canvas.drawLine(plot.left - tickGap, targetBottom, plot.left - tickGap - tickLength, targetBottom, linePaint)
            }
            val x = if (onRight) plot.right + tickGap + tickLength + 2f.dp else plot.left - tickGap - tickLength - 2f.dp
            canvas.drawText(highValue, x, highBaseline, paint)
            canvas.drawText(lowValue, x, lowBaseline, paint)
        }

        private fun drawGraphMaximumScale(
            canvas: Canvas,
            plot: RectF,
            onRight: Boolean,
            maximumMgDl: Double,
        ) {
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11.5f, resources.displayMetrics)
                    color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_LABEL)
                    textAlign = if (onRight) Paint.Align.LEFT else Paint.Align.RIGHT
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
            val tickGap = 2f.dp
            val tickLength = 5f.dp
            val baseline = plot.top - paint.fontMetrics.ascent + 2f.dp
            val tickY = baseline + paint.fontMetrics.ascent
            linePaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_AXIS_TICK)
            linePaint.strokeWidth = 1f.dp
            linePaint.pathEffect = null
            if (onRight) {
                canvas.drawLine(plot.right + tickGap, tickY, plot.right + tickGap + tickLength, tickY, linePaint)
            } else {
                canvas.drawLine(plot.left - tickGap, tickY, plot.left - tickGap - tickLength, tickY, linePaint)
            }
            val x = if (onRight) plot.right + tickGap + tickLength + 2f.dp else plot.left - tickGap - tickLength - 2f.dp
            canvas.drawText(glucoseLabel(maximumMgDl), x, baseline, paint)
        }

        private fun drawBasal(
            canvas: Canvas,
            plot: RectF,
            start: Long,
            end: Long,
            points: List<TherapyHistorySample>,
        ) {
            val sorted =
                points
                    .filter {
                        it.baseBasalUnitsPerHour != null || it.basalUnitsPerHour != null
                    }.sortedBy { it.measuredAtEpochMs }
            val visible = windowedStepSamples(sorted, start, end)
            if (visible.size < 2) return
            val maxBasal =
                max(
                    0.1,
                    visible.flatMap { listOfNotNull(it.baseBasalUnitsPerHour ?: it.basalUnitsPerHour, effectiveBasal(it)) }.maxOrNull()
                        ?: 0.1,
                )

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
            scaleMode: CgmGraphScaleMode,
        ) {
            val allActual =
                points
                    .mapNotNull { point ->
                        point.insulinActivityUnitsPerMinute?.takeIf { it.isFinite() && it >= 0.0 }?.let { point.measuredAtEpochMs to it }
                    }.sortedBy { it.first }
            val actual = allActual.filter { it.first in start..min(end, now) }
            if (actual.size < 2) return
            val activityScale =
                axisScaleSession.resolve(
                    axis = GraphAxis.INSULIN_ACTIVITY,
                    mode = scaleMode,
                    seedValues = allActual.map { it.second },
                    visibleValues = actual.map { it.second },
                    fallbackBounds = GraphBounds(0.0, 0.01),
                    minimumSpan = 0.001,
                )
            staticScaleStore.persist(GraphAxis.INSULIN_ACTIVITY, activityScale)

            fun activityY(value: Double): Float = band.bottom - activityScale.ratio(value).toFloat() * band.height() * ACTIVITY_HEIGHT_FRACTION
            val yellow = Color.rgb(242, 201, 76)
            linePaint.color = yellow
            linePaint.strokeWidth = 1.35f.dp
            linePaint.pathEffect = null
            canvas.drawPath(valuePath(actual, start, end, band, ::activityY), linePaint)
        }

        private fun predictionEnabled(kind: PredictionKind): Boolean =
            when (kind) {
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
            yScale: CgmGraphYScale,
        ) {
            val color =
                when (series.kind) {
                    PredictionKind.IOB -> SugarliciousColors.argb(SugarliciousColorRole.PREDICTION_IOB)
                    PredictionKind.COB, PredictionKind.ACOB -> SugarliciousColors.argb(SugarliciousColorRole.PREDICTION_COB)
                    PredictionKind.UAM -> SugarliciousColors.argb(SugarliciousColorRole.PREDICTION_UAM)
                    PredictionKind.ZERO_TEMP -> SugarliciousColors.argb(SugarliciousColorRole.PREDICTION_ZERO_TEMP)
                }
            val radius = predictionDotRadiusDp.dp
            val outlineWidth = predictionDotOutlineWidthDp.dp
            val maximumPredictionCenter = plot.right - radius - outlineWidth / 2f - 1f.dp
            series.samples.forEach { point ->
                val x = mapX(point.measuredAtEpochMs, start, end, plot)
                if (x < plot.left - radius) return@forEach
                if (x > maximumPredictionCenter) return@forEach
                val y = mapGlucoseY(point.valueMgDl, plot, yScale)
                if (outlineWidth > 0f) {
                    dotOutlinePaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_CURRENT_OUTLINE)
                    dotOutlinePaint.strokeWidth = outlineWidth
                    canvas.drawCircle(x, y, radius + outlineWidth / 2f, dotOutlinePaint)
                }
                fillPaint.color = color
                canvas.drawCircle(x, y, radius, fillPaint)
            }
        }

        companion object {
            private const val CLOCK_REFRESH_MS = 30_000L
        }

        private fun drawRoundedBorder(
            canvas: Canvas,
            rect: RectF,
            radius: Float,
        ) {
            linePaint.style = Paint.Style.STROKE
            linePaint.pathEffect = null
            linePaint.color = SugarliciousColors.argb(SugarliciousColorRole.BORDER)
            linePaint.strokeWidth = 1f.dp
            canvas.drawRoundRect(rect, radius, radius, linePaint)
        }

        private fun dotColor(
            value: Double,
            thresholds: CgmThresholds,
        ): Int =
            when (thresholds.classify(value)) {
                CgmRangeClass.VERY_LOW -> SugarliciousColors.argb(SugarliciousColorRole.GLUCOSE_VERY_LOW)
                CgmRangeClass.LOW -> SugarliciousColors.argb(SugarliciousColorRole.CGM_DOT_LOW)
                CgmRangeClass.IN_RANGE -> SugarliciousColors.argb(SugarliciousColorRole.CGM_DOT_IN_RANGE)
                CgmRangeClass.HIGH -> SugarliciousColors.argb(SugarliciousColorRole.CGM_DOT_HIGH)
                CgmRangeClass.VERY_HIGH -> SugarliciousColors.argb(SugarliciousColorRole.GLUCOSE_VERY_HIGH)
                null -> SugarliciousColors.argb(SugarliciousColorRole.CGM_DOT_IN_RANGE)
            }

        private fun glucoseLabel(valueMgDl: Double): String = if (unit == GlucoseUnit.MMOL_L) String.format(Locale.getDefault(), "%.1f", valueMgDl / 18.0) else valueMgDl.toInt().toString()

        private val Float.dp get() = this * density
    }

@SuppressLint("DrawAllocation")
internal class MetabolicDashboardChart
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        sharedViewport: ChartViewport = ChartViewport(6),
    ) : InteractiveChartView(context, attrs, sharedViewport) {
        private val density = resources.displayMetrics.density
        private val linePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private var state: TherapyDisplayState? = null
        private var boundDurationHours: Int? = null
        private var stateSignature: List<Any?>? = null
        private var markerVisibility = TreatmentMarkerVisibility()
        private var renderNowEpochMs: Long = System.currentTimeMillis()
        private var scaleOnRight = false
        private var showTimeAxis = false
        private var graphScaleMode = CgmGraphScaleMode.LOGARITHMIC
        private val staticScaleStore = StaticGraphScaleStore(context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE))
        private val axisScaleSession = GraphScaleSession().also(staticScaleStore::restore)

        fun bind(
            state: TherapyDisplayState?,
            durationHours: Int,
            markerVisibility: TreatmentMarkerVisibility = TreatmentMarkerVisibility(),
            scaleOnRight: Boolean = false,
            showTimeAxis: Boolean = false,
            graphScaleMode: CgmGraphScaleMode = CgmGraphScaleMode.LOGARITHMIC,
            clockEpochMs: Long = System.currentTimeMillis(),
        ) {
            val clockBucket = clockEpochMs / 30_000L
            val newStateSignature =
                listOf(
                    state?.glucose,
                    state?.therapyHistory,
                    state?.therapyEvents,
                    markerVisibility,
                    scaleOnRight,
                    showTimeAxis,
                    graphScaleMode,
                    clockBucket,
                )
            if (stateSignature == newStateSignature && boundDurationHours == durationHours) return
            this.state = state
            stateSignature = newStateSignature
            boundDurationHours = durationHours
            this.markerVisibility = markerVisibility
            this.scaleOnRight = scaleOnRight
            this.showTimeAxis = showTimeAxis
            this.graphScaleMode = graphScaleMode
            renderNowEpochMs = clockEpochMs
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
                val chartNow = renderNowEpochMs
                val viewportSnapshot = viewport.snapshot(chartNow)
                val end = viewportSnapshot.endEpochMs
                val start = viewportSnapshot.startEpochMs
                val allPoints = state?.therapyHistory.orEmpty()
                val points = allPoints.filter { it.measuredAtEpochMs in start..end }
                val valueAxisWidth = VALUE_AXIS_WIDTH_DP.dp
                val left = if (scaleOnRight) outer.left else outer.left + valueAxisWidth
                val right = if (scaleOnRight) outer.right - valueAxisWidth else outer.right
                val top = outer.top
                val bottom = if (showTimeAxis) outer.bottom - TIME_AXIS_HEIGHT_DP.dp else outer.bottom
                val gap = 14f.dp
                val half = (bottom - top - gap) / 2f
                val iobPlot = RectF(left, top, right, top + half)
                val cobLanePlot = RectF(left, top + half + gap, right, bottom)
                val markerHeadroom = min(32f.dp, half * 0.4f)
                val iobDataPlot = RectF(iobPlot.left, iobPlot.top + markerHeadroom, iobPlot.right, iobPlot.bottom)
                val cobPlot = RectF(cobLanePlot.left, cobLanePlot.top + markerHeadroom, cobLanePlot.right, cobLanePlot.bottom)
                // AndroidAPS calculates IOB/COB samples at five-minute timestamps and draws those
                // actual samples. Sugarlicious must not fabricate a future decay from two observations.
                val scales = resolveMetabolicScales(axisScaleSession, graphScaleMode, allPoints, points)
                staticScaleStore.persist(GraphAxis.IOB, scales.iob)
                staticScaleStore.persist(GraphAxis.COB, scales.cob)
                staticScaleStore.persist(GraphAxis.INSULIN_ACTIVITY, scales.activity)
                val iobRange = scales.iob
                val cobRange = scales.cob
                val dividerTimestamp = viewportSnapshot.liveEdgeEpochMs
                // Keep the semantic boundary in data coordinates. A historical viewport may move it
                // completely off-screen; clamping it to an edge would turn it into a sticky overlay.
                val dividerX = mapX(dividerTimestamp, start, end, iobDataPlot)
                val graphSave = canvas.save()
                canvas.clipPath(
                    Path().apply {
                        addRoundRect(iobPlot, radius, radius, Path.Direction.CW)
                        addRoundRect(cobLanePlot, radius, radius, Path.Direction.CW)
                    },
                )
                drawLane(canvas, iobDataPlot, points, start, end, iob = true, range = iobRange, drawScale = false)
                drawInsulinActivity(canvas, iobDataPlot, points, start, end, scales.activity)
                drawLane(canvas, cobPlot, points, start, end, iob = false, range = cobRange, drawScale = false)
                if (!scaleOnRight && dividerTimestamp in start..end) {
                    linePaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_DIVIDER)
                    linePaint.strokeWidth = 1f.dp
                    linePaint.pathEffect = DashPathEffect(floatArrayOf(4f.dp, 4f.dp), 0f)
                    canvas.drawLine(dividerX, iobPlot.top, dividerX, cobPlot.bottom, linePaint)
                    linePaint.pathEffect = null
                }

                drawTreatmentMarkers(
                    canvas,
                    iobDataPlot,
                    cobPlot,
                    iobPlot.top,
                    allPoints,
                    state?.therapyEvents.orEmpty(),
                    points,
                    start,
                    end,
                    iobRange,
                    cobRange,
                )
                if (points.none { it.totalIob != null || it.cobGrams != null }) {
                    drawText(
                        canvas,
                        "Noch kein IOB/COB-Verlauf",
                        (left + right) / 2f,
                        (top + bottom) / 2f,
                        10f,
                        SugarliciousColors.argb(SugarliciousColorRole.GRAPH_MUTED),
                        Paint.Align.CENTER,
                    )
                }
                canvas.restoreToCount(graphSave)
                drawMetabolicScale(canvas, iobDataPlot, iobRange, scaleOnRight)
                drawMetabolicScale(canvas, cobPlot, cobRange, scaleOnRight)
                if (showTimeAxis) {
                    drawSharedGrid(canvas, iobDataPlot, cobPlot, outer.bottom, start, end, chartNow, dividerX)
                }
            }
            linePaint.color = SugarliciousColors.argb(SugarliciousColorRole.BORDER)
            linePaint.strokeWidth = 1f.dp
            linePaint.pathEffect = null
            canvas.drawRoundRect(outer, radius, radius, linePaint)
        }

        private fun drawSharedGrid(
            canvas: Canvas,
            iob: RectF,
            cob: RectF,
            axisBottom: Float,
            start: Long,
            end: Long,
            now: Long,
            nowLineX: Float,
        ) {
            val ticks = RelativeGraphTimeAxis.ticks(start, end, now, viewport.axisIntervalHours)
            linePaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_AXIS_TICK)
            linePaint.strokeWidth = 1f.dp
            linePaint.pathEffect = null
            ticks.forEach { tick ->
                val isNow = tick.hoursBack == 0
                val x = if (isNow) nowLineX else mapX(tick.timestampEpochMs, start, end, cob)
                if (x < cob.left || x > cob.right) return@forEach
                val align =
                    when {
                        tick.timestampEpochMs <= start + 30_000L -> Paint.Align.LEFT
                        isNow -> Paint.Align.CENTER
                        else -> Paint.Align.CENTER
                    }
                val labelX =
                    when (align) {
                        Paint.Align.LEFT -> cob.left + 3f.dp
                        Paint.Align.RIGHT -> cob.right - 3f.dp
                        else -> x
                    }
                val labelSize = if (isNow) 12f else 10f
                val tickX = timeLabelCenterX(tick.label, labelX, align, labelSize)
                canvas.drawLine(tickX, cob.bottom + 2f.dp, tickX, cob.bottom + 8f.dp, linePaint)
                drawText(
                    canvas,
                    tick.label,
                    labelX,
                    axisBottom - 4f.dp,
                    labelSize,
                    SugarliciousColors.argb(SugarliciousColorRole.GRAPH_LABEL),
                    align,
                    bold = true,
                )
            }
        }

        private fun drawLane(
            canvas: Canvas,
            plot: RectF,
            points: List<TherapyHistorySample>,
            start: Long,
            end: Long,
            iob: Boolean,
            range: GraphAxisScale,
            drawScale: Boolean = true,
        ) {
            val actual =
                points
                    .mapNotNull { point ->
                        (if (iob) point.totalIob else point.cobGrams)?.takeIf { it.isFinite() }?.let { point.measuredAtEpochMs to it }
                    }.sortedBy { it.first }
            if (actual.isEmpty()) return

            fun y(value: Double) = mapAxisY(value, range, plot)
            val zeroY = y(0.0)
            val color = SugarliciousColors.argb(if (iob) SugarliciousColorRole.GRAPH_IOB else SugarliciousColorRole.GRAPH_COB)
            val area = valuePathClosedAt(actual, start, end, plot, ::y, zeroY)
            fillPaint.shader =
                LinearGradient(0f, plot.top, 0f, plot.bottom, withAlpha(color, 112), withAlpha(color, 7), Shader.TileMode.CLAMP)
            canvas.drawPath(area, fillPaint)
            fillPaint.shader = null
            linePaint.color = color
            linePaint.strokeWidth = 2.35f.dp
            linePaint.pathEffect = null
            canvas.drawPath(
                valuePath(actual, start, end, plot, ::y),
                linePaint,
            )
            if (drawScale) drawMetabolicScale(canvas, plot, range, scaleOnRight)
        }

        private fun drawMetabolicScale(
            canvas: Canvas,
            plot: RectF,
            range: GraphAxisScale,
            onRight: Boolean,
        ) {
            val tickGap = 2f.dp
            val tickLength = 5f.dp
            val labelX = if (onRight) plot.right + tickGap + tickLength + 2f.dp else plot.left - tickGap - tickLength - 2f.dp
            val labelColor = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_LABEL)
            val scaleTextSizeSp = if (onRight) 10f else 11.5f
            val maximumBaseline = plot.top + 13f.dp
            val minimumBaseline = plot.bottom - 7f.dp
            val scalePaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, scaleTextSizeSp, resources.displayMetrics)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
            val scaleMetrics = scalePaint.fontMetrics
            val align = if (onRight) Paint.Align.LEFT else Paint.Align.RIGHT
            drawText(
                canvas,
                formatMetabolicScale(range.bounds.maximum),
                labelX,
                maximumBaseline,
                scaleTextSizeSp,
                labelColor,
                align,
                bold = true,
            )
            drawMetabolicScaleTick(canvas, plot, maximumBaseline + (scaleMetrics.ascent + scaleMetrics.descent) / 2f, onRight)
            if (range.bounds.minimum < -0.01) {
                drawText(
                    canvas,
                    formatMetabolicScale(range.bounds.minimum),
                    labelX,
                    minimumBaseline,
                    scaleTextSizeSp,
                    labelColor,
                    align,
                    bold = true,
                )
                drawMetabolicScaleTick(canvas, plot, minimumBaseline + (scaleMetrics.ascent + scaleMetrics.descent) / 2f, onRight)
            }
        }

        private fun drawMetabolicScaleTick(
            canvas: Canvas,
            plot: RectF,
            centerY: Float,
            onRight: Boolean,
        ) {
            linePaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_AXIS_TICK)
            linePaint.strokeWidth = 1f.dp
            linePaint.pathEffect = null
            if (onRight) {
                canvas.drawLine(plot.right + 2f.dp, centerY, plot.right + 7f.dp, centerY, linePaint)
            } else {
                canvas.drawLine(plot.left - 2f.dp, centerY, plot.left - 7f.dp, centerY, linePaint)
            }
        }

        private fun drawInsulinActivity(
            canvas: Canvas,
            plot: RectF,
            visiblePoints: List<TherapyHistorySample>,
            start: Long,
            end: Long,
            activityScale: GraphAxisScale,
        ) {
            val actual =
                visiblePoints
                    .mapNotNull { point ->
                        point.insulinActivityUnitsPerMinute?.takeIf { it.isFinite() && it >= 0.0 }?.let { point.measuredAtEpochMs to it }
                    }.sortedBy { it.first }
            if (actual.size < 2) return

            fun y(value: Double) = plot.bottom - activityScale.ratio(value).toFloat() * plot.height() * ACTIVITY_HEIGHT_FRACTION
            linePaint.color = Color.rgb(242, 201, 76)
            linePaint.strokeWidth = 1.6f.dp
            linePaint.pathEffect = null
            canvas.drawPath(valuePath(actual, start, end, plot, ::y), linePaint)
        }

        private fun drawTreatmentMarkers(
            canvas: Canvas,
            iobData: RectF,
            cobData: RectF,
            iobLaneTop: Float,
            history: List<TherapyHistorySample>,
            events: List<TherapyEvent>,
            legacyPoints: List<TherapyHistorySample>,
            start: Long,
            end: Long,
            iobRange: GraphAxisScale,
            cobRange: GraphAxisScale,
        ) {
            val visible =
                events
                    .flatMap(::expandECarbSimulation)
                    .filter {
                        it.timestampEpochMs in start..end &&
                            it.amount.isFinite() &&
                            it.amount > 0.0 &&
                            markerVisibility.shows(it.kind)
                    }.filterNot { it.kind == TherapyEventKind.ECARBS && it.amount < 1.0 }
            val explicitSmbTimes = visible.filter { it.kind == TherapyEventKind.SMB }.map { it.timestampEpochMs }.toSet()
            val legacySmb =
                legacyPoints.mapNotNull { point ->
                    point.smbUnits
                        ?.takeIf {
                            it > 0.0 &&
                                explicitSmbTimes.none { time ->
                                    kotlin.math.abs(time - point.measuredAtEpochMs) < 1_000L
                                }
                        }?.let {
                            TherapyEvent(
                                "legacy-smb:${point.measuredAtEpochMs}:$it",
                                TherapyEventKind.SMB,
                                point.measuredAtEpochMs,
                                it,
                            )
                        }
                }
            val zeroY = mapAxisY(0.0, iobRange, iobData)
            val cobZeroY = mapAxisY(0.0, cobRange, cobData)
            (visible + legacySmb).forEach { event ->
                val side = treatmentMarkerSide(event.kind, event.amount).dp
                val half = side / 2f
                val height = side * (sqrt(3.0).toFloat() / 2f)
                val eventPlot =
                    when (event.kind) {
                        // COB labels may use the dedicated separator above the COB
                        // lane. The marker and curve still remain inside cobData.
                        TherapyEventKind.MEAL_CARBS, TherapyEventKind.ECARBS ->
                            RectF(cobData.left, iobData.bottom, cobData.right, cobData.bottom)
                        else -> RectF(iobData.left, iobLaneTop, iobData.right, iobData.bottom)
                    }
                canvas.withClip(eventPlot) {
                    when (event.kind) {
                        TherapyEventKind.SMB -> {
                            fillPaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_IOB)
                            val x = mapX(event.timestampEpochMs, start, end, iobData)
                            drawPath(roundedUpTriangle(x, (zeroY + height).coerceAtMost(iobData.bottom), half, height, 2.4f.dp), fillPaint)
                        }
                        TherapyEventKind.MANUAL_CORRECTION ->
                            drawBolusMarkerAboveZero(
                                this,
                                event,
                                iobData,
                                start,
                                end,
                                zeroY,
                                side,
                                showAmount = false,
                            )
                        TherapyEventKind.MEAL_BOLUS ->
                            drawCurveMarker(
                                this,
                                event,
                                iobData,
                                history,
                                start,
                                end,
                                iobRange,
                                true,
                                side,
                                "U",
                                labelGapDp = 2f,
                            )
                        TherapyEventKind.MEAL_CARBS ->
                            drawCurveMarker(
                                this,
                                event,
                                cobData,
                                history,
                                start,
                                end,
                                cobRange,
                                false,
                                side,
                                "g",
                                labelGapDp = 2f,
                            )
                        TherapyEventKind.ECARBS -> drawCarbMarkerAtZero(this, event, cobData, start, end, cobZeroY, side)
                    }
                }
            }
        }

        private fun drawBolusMarkerAboveZero(
            canvas: Canvas,
            event: TherapyEvent,
            plot: RectF,
            start: Long,
            end: Long,
            zeroY: Float,
            side: Float,
            showAmount: Boolean,
        ) {
            val half = side / 2f
            val height = side * (sqrt(3.0).toFloat() / 2f)
            val x = mapX(event.timestampEpochMs, start, end, plot)
            fillPaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_IOB)
            canvas.drawPath(roundedDownTriangle(x, zeroY, half, height, 2.4f.dp), fillPaint)
            if (showAmount) {
                drawText(
                    canvas,
                    formatEventAmount(event.amount, "U"),
                    x,
                    (zeroY - height - 3f.dp).coerceAtLeast(plot.top + 10f.dp),
                    8f,
                    fillPaint.color,
                    Paint.Align.CENTER,
                    bold = true,
                )
            }
        }

        private fun drawCarbMarkerAtZero(
            canvas: Canvas,
            event: TherapyEvent,
            plot: RectF,
            start: Long,
            end: Long,
            zeroY: Float,
            side: Float,
        ) {
            val half = side / 2f
            val height = side * (sqrt(3.0).toFloat() / 2f)
            val x = mapX(event.timestampEpochMs, start, end, plot)
            fillPaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_COB)
            canvas.drawPath(roundedDownTriangle(x, zeroY, half, height, 2.4f.dp), fillPaint)
            drawMarkerLabel(
                canvas = canvas,
                value = formatEventAmount(event.amount, "g"),
                markerCenterX = x,
                markerTopY = zeroY - height,
                gapDp = 2f,
                sizeSp = 7f,
                color = fillPaint.color,
            )
        }

        private fun drawCurveMarker(
            canvas: Canvas,
            event: TherapyEvent,
            plot: RectF,
            history: List<TherapyHistorySample>,
            start: Long,
            end: Long,
            range: GraphAxisScale,
            iob: Boolean,
            side: Float,
            unit: String?,
            labelGapDp: Float = 3f,
        ) {
            val value = interpolateTherapyValue(history, event.timestampEpochMs, iob) ?: 0.0
            val half = side / 2f
            val height = side * (sqrt(3.0).toFloat() / 2f)
            val x = mapX(event.timestampEpochMs, start, end, plot)
            val y = mapAxisY(value, range, plot)
            fillPaint.color = SugarliciousColors.argb(if (iob) SugarliciousColorRole.GRAPH_IOB else SugarliciousColorRole.GRAPH_COB)
            canvas.drawPath(roundedDownTriangle(x, y, half, height, 2.4f.dp), fillPaint)
            unit?.let {
                drawMarkerLabel(
                    canvas = canvas,
                    value = formatEventAmount(event.amount, it),
                    markerCenterX = x,
                    markerTopY = y - height,
                    gapDp = labelGapDp,
                    sizeSp = 8f,
                    color = fillPaint.color,
                )
            }
        }

        private fun drawMarkerLabel(
            canvas: Canvas,
            value: String,
            markerCenterX: Float,
            markerTopY: Float,
            gapDp: Float,
            sizeSp: Float,
            color: Int,
        ) {
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp * 1.15f, resources.displayMetrics)
                    this.color = color
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    textAlign = Paint.Align.LEFT
                }
            val visibleBounds = android.graphics.Rect()
            paint.getTextBounds(value, 0, value.length, visibleBounds)
            val left = markerCenterX - visibleBounds.exactCenterX()
            val baseline = markerTopY - gapDp.dp - visibleBounds.bottom
            canvas.drawText(value, left, baseline, paint)
        }

        private val Float.dp get() = this * density
    }

internal data class MobileMetabolicScales(
    val iob: GraphAxisScale,
    val cob: GraphAxisScale,
    val activity: GraphAxisScale,
)

/** Resolves every secondary axis through the same active mode without mixing their units. */
internal fun resolveMetabolicScales(
    session: GraphScaleSession,
    mode: CgmGraphScaleMode,
    allPoints: List<TherapyHistorySample>,
    visiblePoints: List<TherapyHistorySample>,
): MobileMetabolicScales =
    MobileMetabolicScales(
        iob =
            session.resolve(
                axis = GraphAxis.IOB,
                mode = mode,
                seedValues = allPoints.mapNotNull { it.totalIob },
                visibleValues = visiblePoints.mapNotNull { it.totalIob },
                fallbackBounds = GraphBounds(0.0, 1.0),
                minimumSpan = 0.1,
                maxTickCount = 3,
            ),
        cob =
            session.resolve(
                axis = GraphAxis.COB,
                mode = mode,
                seedValues = allPoints.mapNotNull { it.cobGrams },
                visibleValues = visiblePoints.mapNotNull { it.cobGrams },
                fallbackBounds = GraphBounds(0.0, 10.0),
                minimumSpan = 1.0,
                maxTickCount = 5,
            ),
        activity =
            session.resolve(
                axis = GraphAxis.INSULIN_ACTIVITY,
                mode = mode,
                seedValues = allPoints.mapNotNull { it.insulinActivityUnitsPerMinute },
                visibleValues = visiblePoints.mapNotNull { it.insulinActivityUnitsPerMinute },
                fallbackBounds = GraphBounds(0.0, 0.01),
                minimumSpan = 0.001,
                maxTickCount = 5,
            ),
    )

internal fun toolkitSmbMarkerSide(units: Double): Float = bolusMarkerSide(units)

internal fun bolusMarkerSide(units: Double): Float =
    when {
        abs(units) <= 0.1 -> 7f
        abs(units) <= 0.2 -> 9f
        abs(units) <= 0.5 -> 11f
        abs(units) <= 1.5 -> 13f
        else -> 16f
    }

internal fun treatmentMarkerSide(
    kind: TherapyEventKind,
    amount: Double,
): Float =
    when (kind) {
        TherapyEventKind.SMB, TherapyEventKind.MANUAL_CORRECTION, TherapyEventKind.MEAL_BOLUS -> bolusMarkerSide(amount)
        TherapyEventKind.MEAL_CARBS -> 15f
        TherapyEventKind.ECARBS -> 11f
    }

internal fun expandECarbSimulation(event: TherapyEvent): List<TherapyEvent> {
    if (event.kind != TherapyEventKind.ECARBS) return listOf(event)
    val duration = event.durationMinutes?.takeIf { it > 0 } ?: return listOf(event)
    if (!event.amount.isFinite() || event.amount < 1.0) return emptyList()
    // A marker every five minutes makes labels overlap even on a 3 h view.
    // Fifteen-minute portions keep the simulation distributed over its full
    // duration while leaving each amount and triangle readable.
    val timelineStepCount = ((duration + 14) / 15).coerceAtLeast(1)
    val wholeGrams =
        kotlin.math
            .floor(event.amount)
            .toInt()
            .coerceAtLeast(1)
    val gramLimitedStepCount = wholeGrams
    val stepCount = min(timelineStepCount, gramLimitedStepCount)
    val intervalMs = duration * 60_000L / stepCount
    val baseGrams = wholeGrams / stepCount
    val extraWholeGrams = wholeGrams % stepCount
    val fractionalRemainder = event.amount - wholeGrams
    return List(stepCount) { index ->
        val grams =
            baseGrams.toDouble() +
                (if (index < extraWholeGrams) 1.0 else 0.0) +
                (if (index == stepCount - 1) fractionalRemainder else 0.0)
        event.copy(
            id = "${event.id}:simulation:$index",
            timestampEpochMs = event.timestampEpochMs + index * intervalMs,
            amount = grams,
            carbsGrams = grams,
        )
    }
}

internal fun interpolateTherapyValue(
    points: List<TherapyHistorySample>,
    timestamp: Long,
    iob: Boolean,
): Double? {
    val values =
        points
            .mapNotNull { point ->
                (if (iob) point.totalIob else point.cobGrams)?.takeIf(Double::isFinite)?.let { point.measuredAtEpochMs to it }
            }.sortedBy { it.first }
    if (values.isEmpty()) return null
    val before = values.lastOrNull { it.first <= timestamp }
    val after = values.firstOrNull { it.first >= timestamp }
    if (before == null) return after?.second
    if (after == null || before.first == after.first) return before.second
    val ratio = (timestamp - before.first).toDouble() / (after.first - before.first).toDouble()
    return before.second + (after.second - before.second) * ratio
}

internal fun formatEventAmount(
    amount: Double,
    unit: String,
): String {
    val rounded = if (kotlin.math.abs(amount - kotlin.math.round(amount)) < 0.0001) "%.0f" else "%.1f"
    return String.format(Locale.getDefault(), "$rounded %s", amount, unit)
}

internal fun glucoseLogRatio(valueMgDl: Double): Double = GlucoseGraphScale.ratio(valueMgDl)

internal data class MobileCgmGraphBounds(
    val content: RectF,
    val tile: RectF,
    val plot: RectF,
    val timeAxis: RectF,
    val valueAxis: RectF,
)

internal fun mobileCgmGraphBounds(
    width: Float,
    height: Float,
    outlineInset: Float,
    timeAxisHeight: Float,
    valueAxisWidth: Float,
    scaleOnRight: Boolean,
): MobileCgmGraphBounds {
    val content = RectF(0f, 0f, width, height)
    val tile = RectF(outlineInset, outlineInset, width - outlineInset, height - outlineInset)
    val plot =
        if (scaleOnRight) {
            RectF(tile.left, tile.top, tile.right - valueAxisWidth, tile.bottom - timeAxisHeight)
        } else {
            RectF(tile.left + valueAxisWidth, tile.top, tile.right, tile.bottom - timeAxisHeight)
        }
    val timeAxis = RectF(plot.left, plot.bottom, plot.right, tile.bottom)
    val valueAxis =
        if (scaleOnRight) {
            RectF(plot.right, plot.top, tile.right, plot.bottom)
        } else {
            RectF(tile.left, plot.top, plot.left, plot.bottom)
        }
    return MobileCgmGraphBounds(content, tile, plot, timeAxis, valueAxis)
}

private fun mapGlucoseY(
    valueMgDl: Double,
    plot: RectF,
    scale: CgmGraphYScale,
): Float = plot.bottom - scale.ratio(valueMgDl).toFloat() * plot.height()

internal fun glucoseLogRatio(
    valueMgDl: Double,
    maximumMgDl: Double,
): Double = GlucoseGraphScale.ratio(valueMgDl, maximumMgDl)

internal fun stableCgmScale(
    previous: CgmGraphYScale?,
    proposed: CgmGraphYScale,
): CgmGraphYScale {
    if (previous == null || previous.mode != proposed.mode) return proposed
    if (proposed.mode == CgmGraphScaleMode.STATIC || proposed.mode == CgmGraphScaleMode.LOGARITHMIC) return proposed
    if (proposed.minimumMgDl < previous.minimumMgDl || proposed.maximumMgDl > previous.maximumMgDl) {
        return proposed.copy(
            minimumMgDl = minOf(previous.minimumMgDl, proposed.minimumMgDl),
            maximumMgDl = maxOf(previous.maximumMgDl, proposed.maximumMgDl),
        )
    }
    val previousSpan = previous.maximumMgDl - previous.minimumMgDl
    val proposedSpan = proposed.maximumMgDl - proposed.minimumMgDl
    return if (proposedSpan <= previousSpan * 0.72) proposed else previous
}

private fun windowedStepSamples(
    points: List<TherapyHistorySample>,
    start: Long,
    end: Long,
): List<TherapyHistorySample> {
    if (points.isEmpty()) return emptyList()
    val seed = points.lastOrNull { it.measuredAtEpochMs <= start }
    val visible = points.filter { it.measuredAtEpochMs in (start + 1)..end }
    val combined =
        buildList {
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
): Path =
    Path().apply {
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

internal fun contentAnchoredDashPhase(
    pathStartX: Float,
    period: Float,
): Float = 0f

internal fun currentTimeLabelAnchor(
    dividerX: Float,
    plotRight: Float,
    edgePadding: Float,
): Float = minOf(dividerX, plotRight - edgePadding)

private fun valuePath(
    values: List<Pair<Long, Double>>,
    start: Long,
    end: Long,
    plot: RectF,
    mapValue: (Double) -> Float,
): Path =
    Path().apply {
        values.forEachIndexed { index, (time, value) ->
            val x = mapX(time, start, end, plot)
            val y = mapValue(value)
            if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
    }

private fun valuePathClosedAt(
    values: List<Pair<Long, Double>>,
    start: Long,
    end: Long,
    plot: RectF,
    mapValue: (Double) -> Float,
    baseline: Float,
): Path =
    Path().apply {
        if (values.isEmpty()) return@apply
        moveTo(mapX(values.first().first, start, end, plot), baseline)
        values.forEach { (time, value) -> lineTo(mapX(time, start, end, plot), mapValue(value)) }
        lineTo(mapX(values.last().first, start, end, plot), baseline)
        close()
    }

private fun mapAxisY(
    value: Double,
    scale: GraphAxisScale,
    plot: RectF,
): Float = plot.bottom - scale.ratio(value).toFloat() * plot.height()

internal fun continuousActivitySeries(
    actual: List<Pair<Long, Double>>,
    future: List<Pair<Long, Double>>,
    boundaryTime: Long,
): Pair<List<Pair<Long, Double>>, List<Pair<Long, Double>>> {
    val samples = (actual.filter { it.first < boundaryTime } + future).distinctBy { it.first }.sortedBy { it.first }
    return samples.filter { it.first <= boundaryTime } to samples.filter { it.first >= boundaryTime }
}

private fun formatMetabolicScale(value: Double): String =
    when {
        abs(value) >= 10.0 -> String.format(Locale.getDefault(), "%.0f", value)
        abs(value) >= 1.0 -> String.format(Locale.getDefault(), "%.1f", value)
        else -> String.format(Locale.getDefault(), "%.2f", value)
    }

private fun roundedUpTriangle(
    cx: Float,
    baseY: Float,
    halfWidth: Float,
    height: Float,
    radius: Float,
): Path =
    Path().apply {
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

internal fun timeToXFraction(
    time: Long,
    start: Long,
    end: Long,
): Float = ((time - start).toDouble() / (end - start).coerceAtLeast(1L)).toFloat()

internal fun targetScaleOnRight(
    showPredictions: Boolean,
    showTargetValue: Boolean,
    showBasal: Boolean,
    showActivity: Boolean,
): Boolean = !showPredictions && !showTargetValue && !showBasal && !showActivity

private fun roundedDownTriangle(
    cx: Float,
    apexY: Float,
    halfWidth: Float,
    height: Float,
    radius: Float,
): Path =
    Path().apply {
        val topY = apexY - height
        val safeRadius = radius.coerceAtMost(min(halfWidth, height) * 0.35f)
        moveTo(cx - halfWidth + safeRadius, topY)
        lineTo(cx + halfWidth - safeRadius, topY)
        quadTo(cx + halfWidth, topY, cx + halfWidth - safeRadius * 0.55f, topY + safeRadius)
        lineTo(cx + safeRadius * 0.55f, apexY - safeRadius)
        quadTo(cx, apexY, cx - safeRadius * 0.55f, apexY - safeRadius)
        lineTo(cx - halfWidth + safeRadius * 0.55f, topY + safeRadius)
        quadTo(cx - halfWidth, topY, cx - halfWidth + safeRadius, topY)
        close()
    }

internal data class TreatmentMarkerVisibility(
    val mealBolus: Boolean = true,
    val correction: Boolean = true,
    val smb: Boolean = true,
    val mealCarbs: Boolean = true,
    val eCarbs: Boolean = true,
) {
    fun shows(kind: TherapyEventKind) =
        when (kind) {
            TherapyEventKind.MEAL_BOLUS -> mealBolus
            TherapyEventKind.MANUAL_CORRECTION -> correction
            TherapyEventKind.SMB -> smb
            TherapyEventKind.MEAL_CARBS -> mealCarbs
            TherapyEventKind.ECARBS -> eCarbs
        }
}

private fun mapX(
    time: Long,
    start: Long,
    end: Long,
    plot: RectF,
): Float = plot.left + timeToXFraction(time, start, end) * plot.width()

private fun withAlpha(
    color: Int,
    alpha: Int,
): Int = Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

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
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp * 1.15f, resources.displayMetrics)
            this.color = color
            textAlign = align
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    canvas.drawText(value, x, y, paint)
}

private fun View.timeLabelCenterX(
    value: String,
    anchorX: Float,
    align: Paint.Align,
    sizeSp: Float,
): Float {
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp * 1.15f, resources.displayMetrics)
        }
    val halfWidth = paint.measureText(value) / 2f
    return when (align) {
        Paint.Align.LEFT -> anchorX + halfWidth
        Paint.Align.RIGHT -> anchorX - halfWidth
        Paint.Align.CENTER -> anchorX
    }
}
