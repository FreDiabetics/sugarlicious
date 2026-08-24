package app.aapswear.mobile

import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.aapswear.mobile.ui.theme.SugarliciousColors
import app.aapswear.model.Freshness
import app.aapswear.model.FreshnessPolicy
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
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
    val displayable = freshness == Freshness.CURRENT || freshness == Freshness.DELAYED
    val density = LocalDensity.current
    val screenHeightDp = with(density) {
        LocalWindowInfo.current.containerSize.height.toDp().value.roundToInt()
    }
    val metrics = DashboardLayoutMetrics.forScreenHeight(screenHeightDp)
    val gap = if (preferences.compact) 5.dp else 8.dp
    val graphHeightDp = maxOf(
        metrics.metabolicChartHeight - 18,
        96,
    )

    val cgmChartViewport =
        remember {
            ChartViewport(
                preferences.graphHours,
            )
        }
    val metabolicChartViewport = cgmChartViewport

    val predictionFutureWindowMs =
        if (
            preferences.showCgmGraph &&
            preferences.anyCgmPredictionEnabled
        ) {
            val enabledPredictions = state?.glucosePredictions
                .orEmpty()
                .filter { series ->
                    when (
                        series.kind
                    ) {
                        app.aapswear.model.PredictionKind.IOB ->
                            preferences.showCgmPredictionIob

                        app.aapswear.model.PredictionKind.COB,
                        app.aapswear.model.PredictionKind.ACOB,
                        ->
                            preferences.showCgmPredictionCob

                        app.aapswear.model.PredictionKind.UAM ->
                            preferences.showCgmPredictionUam

                        app.aapswear.model.PredictionKind.ZERO_TEMP ->
                            preferences.showCgmPredictionZeroTemp
                    }
                }
            PredictionDisplayTimeline.futureWindowMs(enabledPredictions, now)
        } else {
            0L
        }

    val metabolicFutureWindowMs =
        if (
            preferences.showMetabolicGraph &&
            preferences.anyCgmPredictionEnabled
        ) {
            90L * 60_000L
        } else {
            0L
        }

    LaunchedEffect(
        preferences.graphHours,
    ) {
        cgmChartViewport.setHours(
            preferences.graphHours.toFloat(),
            resetPan = true,
        )
        metabolicChartViewport.setHours(
            preferences.graphHours.toFloat(),
            resetPan = true,
        )
    }

    LaunchedEffect(
        predictionFutureWindowMs,
        metabolicFutureWindowMs,
    ) {
        cgmChartViewport.setFutureWindow(
            maxOf(
                predictionFutureWindowMs,
                metabolicFutureWindowMs,
            ),
        )
    }

    val glucoseText = if (displayable && glucose != null) formatGlucose(glucose.valueMgDl, unit) else "—"
    val targetLow = state?.target?.lowMgDl ?: 80.0
    val targetHigh = state?.target?.highMgDl ?: 160.0
    val glucoseColor = when {
        !displayable || glucose == null -> SugarliciousColors.TextPrimary
        glucose.valueMgDl < targetLow -> SugarliciousColors.GlucoseLow
        glucose.valueMgDl > targetHigh -> SugarliciousColors.GlucoseHigh
        else -> SugarliciousColors.GlucoseInRange
    }
    val delta = if (displayable) formatDelta(glucose?.deltaMgDl, unit) else "—"
    val age = glucose?.measuredAtEpochMs?.let { "${((now - it).coerceAtLeast(0L) / 60_000L)} min" } ?: "—"
    val tirStats = calculateTirStats(state, now)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .menuSwipeNavigation(
                    screen = DashboardScreen.OVERVIEW,
                    onNavigate = callbacks.navigate,
                )
                .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        OverviewWatchFaceTile(
            state = state,
            diagnostics = diagnostics,
            selectedFaceIndex = preferences.watchFaceIndex,
            onSelectedFace = callbacks.setWatchFaceIndex,
            onEdit = { callbacks.navigate(DashboardScreen.WATCH) },
        )

        OverviewInlineHeader(onSettings = { callbacks.navigate(DashboardScreen.SETTINGS) })

        GlucoseHeroCard(
            glucoseText = glucoseText,
            glucoseColor = glucoseColor,
            trend = if (displayable) glucose?.trend ?: Trend.UNKNOWN else Trend.UNKNOWN,
            delta = delta,
            age = age,
            unitLabel = unitLabel(unit),
            tirStats = tirStats,
            heightDp = maxOf(metrics.summaryTileHeight + 18, 108),
        )

        if (preferences.showDetails) {
            QuickStatsRow(
                state = state.takeIf { displayable },
                heightDp = metrics.statTileHeight,
            )
        }

        if (preferences.showCgmGraph) {
            GlucoseGraphSurface(
                state = state,
                preferences = preferences,
                viewport = cgmChartViewport,
                chartHeightDp = graphHeightDp,
                now = now,
            )
        }

        if (preferences.showMetabolicGraph) {
            MetabolicGraphSurface(
                state = state,
                preferences = preferences,
                viewport = metabolicChartViewport,
                chartHeightDp = graphHeightDp,
            )
        }
    }
}

@Composable
private fun GlucoseHeroCard(
    glucoseText: String,
    glucoseColor: Color,
    trend: Trend,
    delta: String,
    age: String,
    unitLabel: String,
    tirStats: TirStats,
    heightDp: Int,
) {
    val shape = RoundedCornerShape(28.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .background(SugarliciousColors.Surface, shape)
            .border(
                1.dp,
                SugarliciousColors.Border.copy(alpha = 0.85f),
                shape,
            )
            .clip(shape)
            .padding(
                horizontal = 18.dp,
                vertical = 2.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = glucoseText,
                            color = glucoseColor,
                            fontSize = 42.sp,
                            lineHeight = 44.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.8).sp,
                        )

                        Spacer(Modifier.width(6.dp))

                        SugarliciousTrendIndicator(trend)
                    }

                    Spacer(Modifier.height(3.dp))

                    Text(
                        text = "Δ $delta · $age · $unitLabel",
                        color = SugarliciousColors.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(Modifier.width(20.dp))

            TirProgressColumn(
                stats = tirStats,
                modifier = Modifier.width(194.dp).fillMaxHeight(),
            )
        }
    }
}
private data class TirStats(
    val inRange: Int?,
    val below: Int?,
    val above: Int?,
)

private fun calculateTirStats(
    state: TherapyDisplayState?,
    now: Long,
): TirStats {
    val start = now - 24L * 60L * 60_000L
    val targetLow = state?.target?.lowMgDl ?: 80.0
    val targetHigh = state?.target?.highMgDl ?: 160.0
    val samples = buildList {
        addAll(state?.glucoseHistory.orEmpty())
        state?.glucose?.let {
            add(
                app.aapswear.model.GlucoseSample(
                    valueMgDl = it.valueMgDl,
                    measuredAtEpochMs = it.measuredAtEpochMs,
                ),
            )
        }
    }
        .filter {
            it.measuredAtEpochMs in start..(now + 5 * 60_000L) &&
                it.valueMgDl in 20.0..1000.0
        }
        .associateBy { it.measuredAtEpochMs }
        .values

    if (samples.isEmpty()) {
        return TirStats(null, null, null)
    }

    val total = samples.size.toDouble()
    val below = samples.count { it.valueMgDl < targetLow }
    val inRange = samples.count { it.valueMgDl in targetLow..targetHigh }
    val above = samples.count { it.valueMgDl > targetHigh }

    return TirStats(
        inRange = (inRange / total * 100.0).roundToInt(),
        below = (below / total * 100.0).roundToInt(),
        above = (above / total * 100.0).roundToInt(),
    )
}

@Composable
private fun TirProgressColumn(
    stats: TirStats,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        TirProgress(
            modifier = Modifier.fillMaxWidth(),
            percent = stats.above,
            accent = SugarliciousColors.color(app.aapswear.mobile.ui.theme.SugarliciousColorRole.PROGRESS_ABOVE),
        )

        TirProgress(
            modifier = Modifier.fillMaxWidth(),
            percent = stats.inRange,
            accent = SugarliciousColors.color(app.aapswear.mobile.ui.theme.SugarliciousColorRole.PROGRESS_IN_RANGE),
        )

        TirProgress(
            modifier = Modifier.fillMaxWidth(),
            percent = stats.below,
            accent = SugarliciousColors.color(app.aapswear.mobile.ui.theme.SugarliciousColorRole.PROGRESS_BELOW),
        )
    }
}

@Composable
private fun TirProgress(
    modifier: Modifier,
    percent: Int?,
    accent: Color,
) {
    val safePercent = (percent ?: 0).coerceIn(0, 100)
    val progress = safePercent / 100f

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(
                    SugarliciousColors.SurfaceRaised,
                    RoundedCornerShape(999.dp),
                ),
        ) {
            if (progress > 0f) {
                val fillWidth =
                    (maxWidth * progress)
                        .coerceAtLeast(12.dp)
                        .coerceAtMost(maxWidth)

                Box(
                    modifier = Modifier
                        .width(fillWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            accent,
                            RoundedCornerShape(999.dp),
                        ),
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        Text(
            text = percent?.let { "$it%" } ?: "—",
            modifier = Modifier.width(42.dp),
            color = SugarliciousColors.TextPrimary,
            fontSize = 12.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}
@Composable
private fun QuickStatsRow(
    state: TherapyDisplayState?,
    heightDp: Int,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickStatCard(Modifier.weight(1f), R.drawable.ic_iob, "IOB", formatNumber(state?.insulin?.totalIob, 2), "IE", SugarliciousColors.Blue, heightDp)
        QuickStatCard(Modifier.weight(1f), R.drawable.ic_carbs, "COB", formatNumber(state?.carbs?.cobGrams, 0), "g", SugarliciousColors.Orange, heightDp)
        QuickStatCard(Modifier.weight(1f), R.drawable.ic_basal, "BASAL", formatNumber(state?.basal?.currentUnitsPerHour, 2), "IE/h", SugarliciousColors.Secondary, heightDp)
    }
}

@Composable
private fun QuickStatCard(
    modifier: Modifier,
    iconRes: Int,
    title: String,
    value: String,
    suffix: String,
    accent: Color,
    heightDp: Int,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .height(heightDp.dp)
            .background(SugarliciousColors.Surface, shape)
            .border(1.dp, SugarliciousColors.Border.copy(alpha = 0.72f), shape)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SugarliciousIcon(
                drawableRes = iconRes,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = accent,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                color = SugarliciousColors.TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        Text(
            text = value,
            color = SugarliciousColors.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = suffix,
            color = SugarliciousColors.TextSecondary,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun GlucoseGraphSurface(
    state: TherapyDisplayState?,
    preferences: DashboardUiPreferences,
    viewport: ChartViewport,
    chartHeightDp: Int,
    now: Long,
) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(chartHeightDp.dp),
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
}

internal fun GlucoseDashboardChart.bindOverview(
    state: TherapyDisplayState?,
    preferences: DashboardUiPreferences,
    nowEpochMs: Long,
) {
    bind(
        state = state,
        unit = preferences.unitFor(state),
        showPredictions = preferences.anyCgmPredictionEnabled,
        durationHours = preferences.graphHours,
        showTargetRange = preferences.showCgmTargetRange,
        showTargetValue = preferences.showCgmTargetValue,
        showBasal = preferences.showCgmBasal,
        showActivity = preferences.showCgmActivity,
        showPredictionIob = preferences.showCgmPredictionIob,
        showPredictionCob = preferences.showCgmPredictionCob,
        showPredictionUam = preferences.showCgmPredictionUam,
        showPredictionZeroTemp = preferences.showCgmPredictionZeroTemp,
        cgmDotRadiusDp = preferences.cgmDotRadiusDp,
        cgmDotOutlineEnabled = preferences.cgmDotOutlineEnabled,
        cgmDotOutlineWidthDp = preferences.cgmDotOutlineWidthDp,
        clockEpochMs = nowEpochMs,
    )
}

@Composable
private fun MetabolicGraphSurface(
    state: TherapyDisplayState?,
    preferences: DashboardUiPreferences,
    viewport: ChartViewport,
    chartHeightDp: Int,
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
            )
        },
    )
}

private fun formatGlucose(valueMgDl: Double, unit: GlucoseUnit): String =
    if (unit == GlucoseUnit.MMOL_L) String.format(Locale.getDefault(), "%.1f", valueMgDl / 18.0)
    else valueMgDl.roundToInt().toString()

private fun formatDelta(valueMgDl: Double?, unit: GlucoseUnit): String {
    if (valueMgDl == null) return "—"
    val converted = if (unit == GlucoseUnit.MMOL_L) valueMgDl / 18.0 else valueMgDl
    val prefix = if (converted >= 0.0) "+" else ""
    val body = if (unit == GlucoseUnit.MMOL_L) String.format(Locale.getDefault(), "%.1f", converted)
    else converted.roundToInt().toString()
    return prefix + body
}

private fun formatNumber(value: Double?, digits: Int): String =
    value?.let { String.format(Locale.getDefault(), "%.${digits}f", it) } ?: "—"

private fun unitLabel(unit: GlucoseUnit): String =
    if (unit == GlucoseUnit.MMOL_L) "mmol/L" else "mg/dL"
