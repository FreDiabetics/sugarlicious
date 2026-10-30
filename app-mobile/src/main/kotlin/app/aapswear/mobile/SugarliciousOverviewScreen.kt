package app.aapswear.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColors
import app.aapswear.model.FreshnessPolicy
import app.aapswear.model.GlucosePrediction
import app.aapswear.model.GlucoseTrendSizing
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.GlucoseVisualSpec
import app.aapswear.model.LoopVisualState
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
import app.aapswear.model.loopPresentation
import app.aapswear.storage.PredictionDisplayTimeline
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun SugarliciousOverviewScreen(
    state: TherapyDisplayState?,
    diagnostics: DiagnosticsSnapshot,
    preferences: DashboardUiPreferences,
    now: Long,
    callbacks: DashboardCallbacks,
) {
    val unit = preferences.unitFor(state)
    val glucose = state?.glucose
    val freshness = FreshnessPolicy.classify(glucose?.measuredAtEpochMs, now)
    val knownGlucose =
        app.aapswear.model.TherapyDisplayFormatter
            .isGlucoseKnown(state)
    val displayable = TherapyDisplayFormatter.isGlucoseDisplayable(state, now)
    val density = LocalDensity.current
    val screenHeightDp =
        with(density) {
            LocalWindowInfo.current.containerSize.height
                .toDp()
                .value
                .roundToInt()
        }
    val metrics = DashboardLayoutMetrics.forScreenHeight(screenHeightDp)
    val gap = 12.dp
    // Extra spacing scrolls with the cards rather than reducing the existing graph heights.
    val referenceGapDp = if (preferences.compact || preferences.showMetabolicGraph) 5 else 8
    val visibility =
        DashboardVisibilityState(
            showTherapyDetails = false,
            showMetabolicGraph = preferences.showMetabolicGraph,
        )
    val preferredCgmGraphHeightDp = maxOf(metrics.glucoseChartHeight, metrics.cgmGraphHeight(visibility.copy(showMetabolicGraph = false), referenceGapDp))
    val metabolicGraphHeightDp = maxOf(metrics.metabolicChartHeight - 10, 104)

    val cgmChartViewport =
        rememberSaveable(
            saver =
                listSaver(
                    save = { viewport ->
                        val state = viewport.savedState()
                        listOf(
                            state.historyHours.toRawBits().toLong(),
                            state.navigationEndEpochMs ?: Long.MIN_VALUE,
                        )
                    },
                    restore = { values ->
                        ChartViewport(preferences.graphHours).apply {
                            restore(
                                GraphViewportSavedState(
                                    historyHours = Float.fromBits(values[0].toInt()),
                                    navigationEndEpochMs = values[1].takeUnless { it == Long.MIN_VALUE },
                                ),
                            )
                        }
                    },
                ),
        ) { ChartViewport(preferences.graphHours) }
    val metabolicChartViewport = cgmChartViewport
    var appliedGraphHours by remember { mutableIntStateOf(preferences.graphHours) }
    var sharedViewportSnapshot by remember(cgmChartViewport, now) {
        mutableStateOf(cgmChartViewport.snapshot(now))
    }
    DisposableEffect(cgmChartViewport, now) {
        val listener = { sharedViewportSnapshot = cgmChartViewport.snapshot(now) }
        cgmChartViewport.addListener(listener)
        listener()
        onDispose { cgmChartViewport.removeListener(listener) }
    }

    val enabledPredictions =
        if (preferences.showCgmGraph && preferences.predictionsEnabled) {
            state
                ?.glucosePredictions
                .orEmpty()
                .filter { it.kind in preferences.enabledPredictionKinds }
        } else {
            emptyList()
        }
    val predictionFutureWindowMs =
        predictionFutureWindowMs(
            enabled = preferences.showCgmGraph && preferences.predictionsEnabled,
            predictions = enabledPredictions,
            now = now,
        )
    val predictionViewportPolicy =
        resolvePredictionViewportPolicy(
            historyHours = cgmChartViewport.hours,
            predictionsEnabled = preferences.showCgmGraph && preferences.predictionsEnabled,
            futureWindowMs = predictionFutureWindowMs,
        )
    val sharedScaleRightBlend =
        predictionViewportPolicy.scaleRightBlendOverride
            ?: predictionScaleRightBlend(
                enabledPredictions,
                now,
                sharedViewportSnapshot.startEpochMs,
                sharedViewportSnapshot.endEpochMs,
            )

    LaunchedEffect(
        preferences.graphHours,
    ) {
        if (preferences.graphHours != appliedGraphHours) {
            cgmChartViewport.setHours(
                preferences.graphHours.toFloat(),
                resetPan = true,
            )
            appliedGraphHours = preferences.graphHours
        }
    }

    LaunchedEffect(
        predictionViewportPolicy.effectiveFutureWindowMs,
    ) {
        cgmChartViewport.setFutureWindow(predictionViewportPolicy.effectiveFutureWindowMs, now)
    }

    val glucoseText = if (knownGlucose && glucose != null) formatGlucose(glucose.valueMgDl, unit) else "—"
    val rangePresentation =
        widgetRangePresentation(
            state = state,
            samples = canonicalWidgetSamples(state, now, 24L * 60L * 60_000L),
            thresholds = preferences.cgmThresholds,
            now = now,
        )
    val glucoseColor =
        (if (TherapyDisplayFormatter.freshness(state, now) == app.aapswear.model.Freshness.SIGNAL_LOSS) Color(0xFF9E9E9E) else SugarliciousColors.color(overviewGlucoseColorRole(rangePresentation.visibleRole))).let { color -> if (displayable || TherapyDisplayFormatter.freshness(state, now) == app.aapswear.model.Freshness.SIGNAL_LOSS) color else color.copy(alpha = 0.62f) }
    val delta = if (displayable) formatDelta(glucose?.deltaMgDl, unit) else "—"
    val age = TherapyDisplayFormatter.ageMinutes(glucose?.measuredAtEpochMs, now)
    val dailyRanges = remember(state, now / 60_000L, java.time.ZoneId.systemDefault()) { dailyGlucoseRanges(state, now) }
    val overviewScroll = rememberScrollState()
    var heroHeightPx by remember { mutableIntStateOf(with(density) { 240.dp.roundToPx() }) }
    var headerHeightPx by remember { mutableIntStateOf(0) }
    var therapyHeightPx by remember { mutableIntStateOf(0) }
    var rangesHeightPx by remember { mutableIntStateOf(0) }
    val compactVisible by remember { derivedStateOf { overviewScroll.value > heroHeightPx - with(density) { 88.dp.toPx() } } }

    fun collapseFraction(): Float = ((overviewScroll.value - heroHeightPx + with(density) { 88.dp.toPx() }) / with(density) { 48.dp.toPx() }).coerceIn(0f, 1f)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val visibleHeights =
            buildList<Float?> {
                if (preferences.showTherapyProgress) add(with(density) { therapyHeightPx.toDp().value })
                if (preferences.showCgmGraph) add(null)
                if (preferences.showMetabolicGraph) add(metabolicGraphHeightDp.toFloat())
                if (preferences.showTir || preferences.showTitr) add(with(density) { rangesHeightPx.toDp().value })
            }
        val cgmGraphHeightDp =
            overviewFittedCgmHeight(
                preferredCgmGraphHeightDp.toFloat(),
                maxHeight.value,
                with(density) { headerHeightPx.toDp().value },
                visibleHeights,
            ).toInt()
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(overviewScroll)
                    .padding(horizontal = 2.dp, vertical = 2.dp),
        ) {
            OverviewInlineHeader(
                onSettings = { callbacks.navigate(DashboardScreen.SETTINGS) },
                diagnostics = diagnostics,
                onWatch = { callbacks.navigate(DashboardScreen.WATCH) },
                modifier = Modifier.onSizeChanged { headerHeightPx = it.height }.overviewHeaderScrollFade(overviewScroll),
                glucoseContent = {
                    Box(Modifier.onSizeChanged { heroHeightPx = it.height }) {
                        OverviewGlucoseHero(
                            glucoseText = glucoseText,
                            glucoseColor = glucoseColor,
                            accentColor = if (displayable && rangePresentation.visibleRole == WidgetColorRole.IN_RANGE) SugarliciousColors.BrandGreen else glucoseColor,
                            trend = if (displayable) glucose?.trend ?: Trend.UNKNOWN else Trend.UNKNOWN,
                            delta = delta,
                            age = age,
                            unitLabel = unitLabel(unit),
                            glucoseScalePercent = preferences.glucoseScalePercent,
                            trendScalePercent = preferences.trendScalePercent,
                        )
                    }
                },
            )

            // Keep the first card close to the action row; preserve spacing between cards.
            Spacer(Modifier.height(2.dp))
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                if (preferences.showTherapyProgress) {
                    Box(Modifier.onSizeChanged { therapyHeightPx = it.height }) {
                        OverviewProgressCard(state.takeIf { displayable }, preferences, now)
                    }
                }

                if (preferences.showCgmGraph) {
                    GlucoseGraphSurface(
                        state = state,
                        preferences = preferences,
                        viewport = cgmChartViewport,
                        chartHeightDp = cgmGraphHeightDp,
                        now = now,
                        onGraphHours = callbacks.setGraphHours,
                    )
                }

                if (preferences.showMetabolicGraph) {
                    MetabolicGraphSurface(
                        state = state,
                        preferences = preferences,
                        viewport = metabolicChartViewport,
                        chartHeightDp = metabolicGraphHeightDp,
                        now = now,
                        scaleRightBlend = sharedScaleRightBlend,
                    )
                }
                if (preferences.showTir || preferences.showTitr) {
                    Box(Modifier.onSizeChanged { rangesHeightPx = it.height }) {
                        OverviewRangeCard(dailyRanges, preferences.showTir, preferences.showTitr)
                    }
                }
            }
        }
        if (compactVisible) {
            OverviewCompactGlucoseHeader(
                glucoseText,
                glucoseColor,
                if (displayable) glucose?.trend ?: Trend.UNKNOWN else Trend.UNKNOWN,
                delta,
                unitLabel(unit),
                age,
                Modifier.align(Alignment.TopCenter).graphicsLayer { alpha = collapseFraction() },
            )
        }
    }
}

/** Fade content only near the viewport edge, moving through the header with the gesture. */
private fun Modifier.overviewHeaderScrollFade(scroll: ScrollState): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val offset = scroll.value.toFloat()
        if (offset > 0f) {
            val band = minOf(48.dp.toPx(), offset)
            drawRect(
                brush = Brush.verticalGradient(listOf(Color.Transparent, Color.White), startY = offset, endY = offset + band),
                blendMode = BlendMode.DstIn,
            )
        }
    }

internal fun hasVisiblePredictions(
    predictions: List<GlucosePrediction>,
    now: Long,
    viewport: GraphViewportSnapshot,
): Boolean =
    PredictionDisplayTimeline
        .anchor(predictions, now)
        .any { series ->
            series.samples.any { it.measuredAtEpochMs in viewport.startEpochMs..viewport.endEpochMs }
        }

internal fun predictionFutureWindowMs(
    enabled: Boolean,
    predictions: List<GlucosePrediction>,
    now: Long,
): Long = if (enabled) PredictionDisplayTimeline.futureWindowMs(predictions, now) else 0L

@Composable
private fun OverviewRangeCard(ranges: DailyGlucoseRanges, showTir: Boolean, showTitr: Boolean) {
    Row(
        Modifier.fillMaxWidth().background(SugarliciousColors.Surface, RoundedCornerShape(28.dp)).padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        @Composable fun RangeColumn(stats: MeasuredRangeStats, title: String, bounds: String, modifier: Modifier) {
            Column(modifier) {
                Text("Heute", color = SugarliciousColors.TextSecondary, fontSize = 12.sp)
                Text(title, color = SugarliciousColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(bounds, color = SugarliciousColors.TextSecondary, fontSize = 11.sp)
                Text(stats.inRange?.let { "$it%" } ?: "—", color = SugarliciousColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                TirProgressColumn(stats, Modifier.fillMaxWidth())
            }
        }
        if (showTitr) RangeColumn(ranges.titr, "Aktuelle Time in Tight Range", "70–140 mg/dL", Modifier.weight(1f))
        if (showTir) RangeColumn(ranges.tir, "Aktuelle Time in Range", "70–180 mg/dL", Modifier.weight(1f))
    }
}

@Composable
internal fun GlucoseValueAndTrend(
    glucoseText: String,
    glucoseColor: Color,
    trend: Trend,
    visualSpec: GlucoseVisualSpec,
    trendStyle: app.aapswear.model.TrendArrowStyle = MobileTrendArrowAppearance.style,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = glucoseText,
            color = glucoseColor,
            fontSize = visualSpec.glucoseTextSize.sp,
            lineHeight = (visualSpec.glucoseTextSize * GlucoseTrendSizing.REFERENCE_GLUCOSE_LINE_HEIGHT_SP / GlucoseTrendSizing.REFERENCE_GLUCOSE_TEXT_SP).sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.8).sp,
        )
        Spacer(Modifier.width(6.dp))
        SugarliciousTrendIndicator(trend, arrowSize = visualSpec.trendHeight.dp, color = glucoseColor, style = trendStyle)
    }
}

@Composable
private fun TirProgressColumn(
    stats: MeasuredRangeStats,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        TirProgress(
            modifier = Modifier.fillMaxWidth(),
            percent = stats.above,
            rangeLabel = "Über Ziel",
            accent = SugarliciousColors.color(app.aapswear.mobile.ui.theme.SugarliciousColorRole.PROGRESS_ABOVE),
        )

        TirProgress(
            modifier = Modifier.fillMaxWidth(),
            percent = stats.inRange,
            rangeLabel = "Im Ziel",
            accent = SugarliciousColors.color(app.aapswear.mobile.ui.theme.SugarliciousColorRole.PROGRESS_IN_RANGE),
        )

        TirProgress(
            modifier = Modifier.fillMaxWidth(),
            percent = stats.below,
            rangeLabel = "Unter Ziel",
            accent = SugarliciousColors.color(app.aapswear.mobile.ui.theme.SugarliciousColorRole.PROGRESS_BELOW),
        )
    }
}

@Composable
private fun TirProgress(
    modifier: Modifier,
    percent: Int?,
    rangeLabel: String,
    accent: Color,
) {
    val safePercent = (percent ?: 0).coerceIn(0, 100)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labelStyle = oneUiMenuTypography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold)
    val labelWidth =
        with(density) {
            measurer
                .measure("100%", labelStyle, softWrap = false)
                .size.width
                .toDp()
        } + 4.dp
    val track = SugarliciousColors.SurfaceRaised
    Row(
        modifier =
            modifier.heightIn(min = 24.dp).semantics(mergeDescendants = true) {
                contentDescription = rangeLabel
                stateDescription = percent?.let { "$safePercent%" } ?: "Keine Daten"
                if (percent != null) progressBarRangeInfo = ProgressBarRangeInfo(safePercent.toFloat(), 0f..100f)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Health-style data bars need their own full-height pill geometry: the native
        // horizontal widget keeps a thin drawable even inside a taller view.
        Canvas(Modifier.weight(1f).height(14.dp)) {
            val radius =
                androidx.compose.ui.geometry
                    .CornerRadius(size.height / 2f)
            val remainingStart = if (safePercent == 0) 0f else (size.width * safePercent / 100f + size.height * .25f).coerceAtMost(size.width)
            if (remainingStart < size.width) {
                drawRoundRect(
                    color = track,
                    topLeft =
                        androidx.compose.ui.geometry
                            .Offset(remainingStart, 0f),
                    size =
                        androidx.compose.ui.geometry
                            .Size(size.width - remainingStart, size.height),
                    cornerRadius = radius,
                )
            }
            if (safePercent > 0) {
                drawRoundRect(
                    color = accent,
                    size =
                        androidx.compose.ui.geometry
                            .Size(size.width * safePercent / 100f, size.height),
                    cornerRadius = radius,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = percent?.let { "$safePercent%" } ?: "—",
            modifier = Modifier.width(labelWidth).clearAndSetSemantics { },
            color = SugarliciousColors.TextPrimary,
            style = labelStyle,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
        )
    }
}

internal data class OverviewLoopTileState(
    val iconRes: Int,
    val label: String,
    val accent: Color,
)

internal fun overviewLoopTileState(state: TherapyDisplayState?): OverviewLoopTileState {
    val presentation = loopPresentation(state)
    val (icon, color) =
        when (presentation.visualState) {
            LoopVisualState.PUMP_SUSPENDED -> R.drawable.ic_pump_suspended to SugarliciousColors.Red
            LoopVisualState.SUSPENDED -> R.drawable.ic_loop_suspended to SugarliciousColors.Orange
            LoopVisualState.DEACTIVATED,
            LoopVisualState.UNKNOWN,
            -> R.drawable.ic_loop_deactivated to SugarliciousColors.TextSecondary
            LoopVisualState.CLOSED -> R.drawable.ic_loop_closed to SugarliciousColors.Green
        }
    return OverviewLoopTileState(icon, presentation.label, color)
}

@Composable
private fun GlucoseGraphSurface(
    state: TherapyDisplayState?,
    preferences: DashboardUiPreferences,
    viewport: ChartViewport,
    chartHeightDp: Int,
    now: Long,
    onGraphHours: (Int) -> Unit,
) {
    var visibleHours by remember(viewport) { mutableFloatStateOf(viewport.visibleHours) }
    val scaleTapInteraction = remember { MutableInteractionSource() }
    DisposableEffect(viewport) {
        val listener = { visibleHours = viewport.visibleHours }
        viewport.addListener(listener)
        onDispose { viewport.removeListener(listener) }
    }
    Box(modifier = Modifier.fillMaxWidth().height(chartHeightDp.dp)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                GlucoseDashboardChart(
                    context = it,
                    sharedViewport = viewport,
                )
            },
            update = {
                it.bindOverview(state, preferences, now)
            },
        )
        Text(
            text = formatVisibleGraphHours(visibleHours),
            color = Color.Transparent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .clickable(indication = null, interactionSource = scaleTapInteraction) {
                        val currentIndex = OVERVIEW_GRAPH_HOUR_OPTIONS.indexOf(preferences.graphHours).coerceAtLeast(0)
                        val next = OVERVIEW_GRAPH_HOUR_OPTIONS[(currentIndex + 1) % OVERVIEW_GRAPH_HOUR_OPTIONS.size]
                        onGraphHours(next)
                    }.padding(start = 9.dp, top = 7.dp, end = 8.dp, bottom = 7.dp),
        )
    }
}

internal fun formatVisibleGraphHours(hours: Float): String {
    val bounded = hours.coerceIn(1f, 24f)
    val rounded = bounded.roundToInt()
    return if (kotlin.math.abs(bounded - rounded) < 0.05f) {
        "${rounded}h"
    } else {
        String.format(Locale.getDefault(), "%.1fh", bounded)
    }
}

internal fun GlucoseDashboardChart.bindOverview(
    state: TherapyDisplayState?,
    preferences: DashboardUiPreferences,
    nowEpochMs: Long,
) {
    bind(
        state = state,
        unit = preferences.unitFor(state),
        showPredictions = preferences.predictionsEnabled,
        durationHours = preferences.graphHours,
        showTargetRange = true,
        showTargetValue = preferences.showCgmTargetValue,
        showCurrentBasal = preferences.showCgmCurrentBasal,
        showBaseBasal = preferences.showCgmBaseBasal,
        showActivity = preferences.showCgmActivity,
        showPredictionIob = preferences.showCgmPredictionIob,
        showPredictionCob = preferences.showCgmPredictionCob,
        showPredictionUam = preferences.showCgmPredictionUam,
        showPredictionZeroTemp = preferences.showCgmPredictionZeroTemp,
        cgmDotRadiusDp = preferences.cgmDotRadiusDp,
        cgmDotOutlineEnabled = preferences.cgmDotOutlineEnabled,
        cgmDotOutlineWidthDp = preferences.cgmDotOutlineWidthDp,
        cgmHistoricalDotOutlineEnabled = preferences.cgmHistoricalDotOutlineEnabled,
        cgmHistoricalDotOutlineWidthDp = preferences.cgmHistoricalDotOutlineWidthDp,
        cgmCurrentDotOutlineWidthDp = preferences.cgmCurrentDotOutlineWidthDp,
        graphMaximumMgDl = preferences.graphMaximumMgDl,
        graphMinimumMgDl = preferences.graphMinimumMgDl,
        graphScaleMode = preferences.graphScaleMode,
        clockEpochMs = nowEpochMs,
    )
}

@Composable
private fun MetabolicGraphSurface(
    state: TherapyDisplayState?,
    preferences: DashboardUiPreferences,
    viewport: ChartViewport,
    chartHeightDp: Int,
    now: Long,
    scaleRightBlend: Float,
) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(chartHeightDp.dp),
        factory = {
            MetabolicDashboardChart(
                context = it,
                sharedViewport = viewport,
            )
        },
        update = {
            it.bind(
                state,
                preferences.graphHours,
                TreatmentMarkerVisibility(
                    mealBolus = preferences.showMealBolusMarkers,
                    correction = preferences.showCorrectionMarkers,
                    smb = preferences.showSmbMarkers,
                    mealCarbs = preferences.showMealCarbMarkers,
                    eCarbs = preferences.showECarbMarkers,
                ),
                scaleRightBlend = scaleRightBlend,
                showTimeAxis = !preferences.showCgmGraph,
                iobGraphScaleMode = preferences.iobGraphScaleMode,
                cobGraphScaleMode = preferences.cobGraphScaleMode,
                iobMaximumUnits = preferences.iobGraphMaximumUnits,
                cobMaximumGrams = preferences.cobGraphMaximumGrams,
                showPredictionDivider = preferences.showPredictionDivider,
                clockEpochMs = now,
            )
        },
    )
}

private fun formatGlucose(
    valueMgDl: Double,
    unit: GlucoseUnit,
): String =
    if (unit == GlucoseUnit.MMOL_L) {
        String.format(Locale.getDefault(), "%.1f", valueMgDl / 18.0)
    } else {
        valueMgDl.roundToInt().toString()
    }

private fun formatDelta(
    valueMgDl: Double?,
    unit: GlucoseUnit,
): String {
    if (valueMgDl == null) return "—"
    val converted = if (unit == GlucoseUnit.MMOL_L) valueMgDl / 18.0 else valueMgDl
    val prefix = if (converted >= 0.0) "+" else ""
    val body =
        if (unit == GlucoseUnit.MMOL_L) {
            String.format(Locale.getDefault(), "%.1f", converted)
        } else {
            converted.roundToInt().toString()
        }
    return prefix + body
}

private fun formatNumber(
    value: Double?,
    digits: Int,
): String = value?.let { String.format(Locale.getDefault(), "%.${digits}f", it) } ?: "—"

private fun unitLabel(unit: GlucoseUnit): String = if (unit == GlucoseUnit.MMOL_L) "mmol/L" else "mg/dL"

internal fun overviewGlucoseColorRole(role: WidgetColorRole): SugarliciousColorRole =
    when (role) {
        WidgetColorRole.VERY_HIGH -> SugarliciousColorRole.GLUCOSE_VERY_HIGH
        WidgetColorRole.URGENT_LOW -> SugarliciousColorRole.GLUCOSE_VERY_LOW
        WidgetColorRole.HIGH -> SugarliciousColorRole.GLUCOSE_HIGH
        WidgetColorRole.LOW -> SugarliciousColorRole.GLUCOSE_LOW
        WidgetColorRole.IN_RANGE -> SugarliciousColorRole.GLUCOSE_IN_RANGE
        else -> SugarliciousColorRole.TEXT_PRIMARY
    }
