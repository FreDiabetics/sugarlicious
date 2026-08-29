package app.aapswear.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.PathParser
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider as DayNightColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColorStore
import app.aapswear.mobile.ui.theme.SugarliciousIconSize
import app.aapswear.mobile.ui.theme.SugarliciousRadius
import app.aapswear.mobile.ui.theme.SugarliciousSpacing
import app.aapswear.model.Freshness
import app.aapswear.model.CanonicalCgmHistory
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.GraphTimeWindow
import app.aapswear.model.CgmGraphPolicy
import app.aapswear.model.CgmRangeClass
import app.aapswear.model.RangeExcursion
import app.aapswear.model.RelativeGraphTimeAxis
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.TrendVisuals
import app.aapswear.model.TrendVisualSpec
import app.aapswear.model.GlucoseTrendSizing
import app.aapswear.uishared.TrendVectorPaths
import app.aapswear.storage.TherapyStateStore
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

private fun coreWidgetColor(role: SugarliciousColorRole): ColorProvider =
    DayNightColorProvider(day = Color(role.lightArgb), night = Color(role.defaultArgb))

private fun widgetColor(argb: Int): ColorProvider =
    DayNightColorProvider(day = Color(argb), night = Color(argb))

private fun blendArgb(base: Int, overlay: Int, fraction: Float): Int {
    val amount = fraction.coerceIn(0f, 1f)
    fun channel(baseChannel: Int, overlayChannel: Int): Int =
        (baseChannel + (overlayChannel - baseChannel) * amount).toInt().coerceIn(0, 255)
    return AndroidColor.argb(
        channel(AndroidColor.alpha(base), AndroidColor.alpha(overlay)),
        channel(AndroidColor.red(base), AndroidColor.red(overlay)),
        channel(AndroidColor.green(base), AndroidColor.green(overlay)),
        channel(AndroidColor.blue(base), AndroidColor.blue(overlay)),
    )
}

private val WidgetCyan = coreWidgetColor(SugarliciousColorRole.SECONDARY)
private val WidgetIob = coreWidgetColor(SugarliciousColorRole.BLUE)
private val WidgetCob = coreWidgetColor(SugarliciousColorRole.ORANGE)
private val WidgetBasal = coreWidgetColor(SugarliciousColorRole.GREEN)
private val WidgetHeartRate = coreWidgetColor(SugarliciousColorRole.RED)

enum class WidgetKind { GLUCOSE, GRAPH, GLUCOSE_GRAPH, METABOLIC, ACTIVITY }

private abstract class SugarliciousWidget : GlanceAppWidget() {
    protected abstract val kind: WidgetKind
    final override val sizeMode: SizeMode = SizeMode.Exact

    @SuppressLint("RestrictedApi")
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = (id as? AppWidgetId)?.appWidgetId ?: android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
        val dashboardPreferences = context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE)
        val appearanceMode = SugarliciousColorStore.activeMode(dashboardPreferences)
        val instance = WidgetInstanceConfigurationStore.read(context, appWidgetId).resolvedAppearance(appearanceMode)
        val state = TherapyStateStore(context).state.first()
        val activitySnapshot = if (kind == WidgetKind.ACTIVITY) HealthConnectIntegration.snapshot(context) else null
        val palette = WidgetColorStore.load(context)
            .with(instance.colorOverrides)
            .with(WidgetColorRole.BACKGROUND, if (instance.backgroundEnabled) instance.backgroundArgb else AndroidColor.TRANSPARENT)
        val thresholds = CgmThresholdPreferences.read(dashboardPreferences)
        provideContent { WidgetShell(kind, state, activitySnapshot, palette, thresholds, instance) }
    }
}

private class GlucoseWidget : SugarliciousWidget() { override val kind = WidgetKind.GLUCOSE }
private class GraphWidget : SugarliciousWidget() { override val kind = WidgetKind.GRAPH }
private class GlucoseGraphWidget : SugarliciousWidget() { override val kind = WidgetKind.GLUCOSE_GRAPH }
private class MetabolicWidget : SugarliciousWidget() { override val kind = WidgetKind.METABOLIC }
private class ActivityWidget : SugarliciousWidget() { override val kind = WidgetKind.ACTIVITY }

private fun deleteWidgetConfigurations(context: Context, ids: IntArray) = ids.forEach { WidgetInstanceConfigurationStore.delete(context, it) }
class GlucoseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GlucoseWidget()
    override fun onDeleted(context: Context, appWidgetIds: IntArray) { deleteWidgetConfigurations(context, appWidgetIds); super.onDeleted(context, appWidgetIds) }
}
class GraphWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GraphWidget()
    override fun onDeleted(context: Context, appWidgetIds: IntArray) { deleteWidgetConfigurations(context, appWidgetIds); super.onDeleted(context, appWidgetIds) }
}
class GlucoseGraphWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GlucoseGraphWidget()
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetInstanceConfigurationStore.delete(context, it) }
        super.onDeleted(context, appWidgetIds)
    }
}
class MetabolicWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MetabolicWidget()
    override fun onDeleted(context: Context, appWidgetIds: IntArray) { deleteWidgetConfigurations(context, appWidgetIds); super.onDeleted(context, appWidgetIds) }
}
class ActivityWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ActivityWidget()
    override fun onDeleted(context: Context, appWidgetIds: IntArray) { deleteWidgetConfigurations(context, appWidgetIds); super.onDeleted(context, appWidgetIds) }
}

internal object SugarliciousWidgets {
    suspend fun update(context: Context) {
        GlucoseWidget().updateAll(context)
        GraphWidget().updateAll(context)
        GlucoseGraphWidget().updateAll(context)
        MetabolicWidget().updateAll(context)
        ActivityWidget().updateAll(context)
    }

    @SuppressLint("RestrictedApi")
    suspend fun update(context: Context, appWidgetId: Int, kind: ConfigurableWidgetKind) {
        val id = AppWidgetId(appWidgetId)
        when (kind) {
            ConfigurableWidgetKind.GLUCOSE -> GlucoseWidget().update(context, id)
            ConfigurableWidgetKind.GRAPH -> GraphWidget().update(context, id)
            ConfigurableWidgetKind.GLUCOSE_GRAPH -> GlucoseGraphWidget().update(context, id)
            ConfigurableWidgetKind.METABOLIC -> MetabolicWidget().update(context, id)
            ConfigurableWidgetKind.ACTIVITY -> ActivityWidget().update(context, id)
        }
    }
}

@Composable
private fun WidgetShell(
    kind: WidgetKind,
    state: TherapyDisplayState?,
    activitySnapshot: HealthConnectSnapshot?,
    palette: WidgetPalette,
    thresholds: app.aapswear.model.CgmThresholds,
    instance: WidgetInstanceConfiguration,
) {
    val size = LocalSize.current
    val layout = responsiveWidgetLayout(size.width.value, size.height.value)
    val context = LocalContext.current
    val resolvedRadius = resolveWidgetCornerRadiusDp(
        instance,
        heightDp = size.height.value,
        systemDefaultDp = systemWidgetCornerRadiusDp(context),
        pillAllowed = kind == WidgetKind.GLUCOSE,
    )
    val renderInstance = instance.copy(cornerRadiusDp = resolvedRadius.roundToInt())
    val launchComponent = WidgetLaunchTargetStore.launchComponent(context, instance.launchPackage)
    val compact = layout.widthClass <= WidgetWidthClass.NARROW || layout.heightClass == WidgetHeightClass.LOW
    val background = widgetColor(palette.argb(WidgetColorRole.BACKGROUND))
    val outerBackground =
        if (kind == WidgetKind.GRAPH || kind == WidgetKind.GLUCOSE || kind == WidgetKind.GLUCOSE_GRAPH) {
            widgetColor(if (instance.backgroundEnabled) instance.backgroundArgb else AndroidColor.TRANSPARENT)
        } else {
            background
        }
    val contentPadding = if (kind == WidgetKind.GRAPH || kind == WidgetKind.GLUCOSE_GRAPH) 0.dp else layout.paddingDp.dp

    Column(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .background(outerBackground)
                .cornerRadius(resolvedRadius.dp)
                .padding(contentPadding)
                .clickable(actionStartActivity(launchComponent)),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        when (kind) {
            WidgetKind.GLUCOSE -> GlucoseWidgetContent(state, palette, thresholds, layout, renderInstance)
            WidgetKind.GRAPH -> GraphWidgetContent(state, palette, thresholds, layout, renderInstance)
            WidgetKind.GLUCOSE_GRAPH -> GlucoseGraphWidgetContent(state, palette, thresholds, layout, renderInstance)
            WidgetKind.METABOLIC,
            WidgetKind.ACTIVITY,
            -> {
                WidgetHeader(kind, state, compact, palette)
                Spacer(GlanceModifier.height(if (compact) SugarliciousSpacing.Xs else SugarliciousSpacing.Md))
                if (kind == WidgetKind.METABOLIC) {
                    MetabolicWidgetContent(state, compact, palette)
                } else {
                    ActivityWidgetContent(activitySnapshot, compact, palette)
                }
            }
        }
    }
}

@Composable
private fun WidgetHeader(kind: WidgetKind, state: TherapyDisplayState?, compact: Boolean, palette: WidgetPalette) {
    val freshness = TherapyDisplayFormatter.freshness(state, System.currentTimeMillis())
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Image(
            ImageProvider(R.mipmap.ic_launcher),
            null,
            GlanceModifier.size(if (compact) SugarliciousIconSize.Small else SugarliciousIconSize.Default),
        )
        Spacer(GlanceModifier.width(SugarliciousSpacing.Sm))
        Column {
            Text(
                "Sugarlicious",
                style = TextStyle(color = widgetColor(palette.argb(WidgetColorRole.TEXT)), fontWeight = FontWeight.Bold, fontSize = if (compact) 13.sp else 15.sp),
            )
            if (!compact && kind != WidgetKind.ACTIVITY) {
                Text(
                    TherapyDisplayFormatter.freshnessLabel(freshness),
                    style = TextStyle(color = statusColor(freshness, palette), fontWeight = FontWeight.Bold, fontSize = 10.sp),
                )
            }
        }
    }
}

@Composable
private fun GlucoseWidgetContent(
    state: TherapyDisplayState?,
    palette: WidgetPalette,
    thresholds: app.aapswear.model.CgmThresholds,
    layout: ResponsiveWidgetLayout,
    instance: WidgetInstanceConfiguration,
) {
    val size = LocalSize.current
    val pixelDensity = LocalContext.current.resources.displayMetrics.density.coerceAtLeast(1f)
    val bitmap = renderMinimalGlucoseWidget(
        state = state,
        palette = palette,
        width = (size.width.value * pixelDensity).roundToInt().coerceAtLeast(48),
        height = (size.height.value * pixelDensity).roundToInt().coerceAtLeast(48),
        thresholds = thresholds,
        layout = layout,
        pixelDensity = pixelDensity,
        options = GlucoseWidgetRenderOptions(
            glucoseScale = configurationScale(instance.glucoseScalePercent),
            trendScale = configurationScale(instance.trendScalePercent),
            outlineEnabled = instance.outlineEnabled,
            outlineArgb = instance.outlineArgb,
            cornerRadiusDp = instance.cornerRadiusDp.takeIf { it > 0 }?.toFloat() ?: layout.cornerRadiusDp,
        ),
    )
    Image(ImageProvider(bitmap), "Glukose und Trend", GlanceModifier.fillMaxSize(), contentScale = ContentScale.Fit)
}

internal const val SAMSUNG_WIDGET_RADIUS_FALLBACK_DP = 28f
internal const val DEFAULT_WIDGET_GRAPH_CORNER_RADIUS_DP = 16
internal const val MIN_WIDGET_GRAPH_CORNER_RADIUS_DP = 4
internal const val MAX_WIDGET_GRAPH_CORNER_RADIUS_DP = 32
internal const val DEFAULT_COMBINED_WIDGET_VALUE_PERCENT = 38
internal const val MIN_COMBINED_WIDGET_VALUE_PERCENT = 34
internal const val MAX_COMBINED_WIDGET_VALUE_PERCENT = 44

internal fun resolveWidgetCornerRadiusDp(
    configuration: WidgetInstanceConfiguration,
    heightDp: Float,
    systemDefaultDp: Float = SAMSUNG_WIDGET_RADIUS_FALLBACK_DP,
    pillAllowed: Boolean = true,
): Float = when {
    pillAllowed && configuration.shapeMode == WidgetShapeMode.PILL -> heightDp.coerceAtLeast(1f) / 2f
    configuration.cornerRadiusDp > 0 -> configuration.cornerRadiusDp.toFloat()
    else -> systemDefaultDp
}

private fun systemWidgetCornerRadiusDp(context: Context): Float {
    val resources = context.resources
    val identifier = resources.getIdentifier("system_app_widget_background_radius", "dimen", "android")
    if (identifier == 0) return SAMSUNG_WIDGET_RADIUS_FALLBACK_DP
    return runCatching { resources.getDimension(identifier) / resources.displayMetrics.density }
        .getOrDefault(SAMSUNG_WIDGET_RADIUS_FALLBACK_DP)
        .takeIf { it > 0f }
        ?: SAMSUNG_WIDGET_RADIUS_FALLBACK_DP
}

@Composable
private fun GraphWidgetContent(
    state: TherapyDisplayState?,
    palette: WidgetPalette,
    thresholds: app.aapswear.model.CgmThresholds,
    layout: ResponsiveWidgetLayout,
    instance: WidgetInstanceConfiguration,
) {
    val size = LocalSize.current
    val pixelDensity = LocalContext.current.resources.displayMetrics.density.coerceAtLeast(1f)
    val bitmap = renderWidgetGraph(
        state = state,
        palette = palette,
        width = (size.width.value * pixelDensity).roundToInt().coerceAtLeast(96),
        height = (size.height.value * pixelDensity).roundToInt().coerceAtLeast(72),
        thresholds = thresholds,
        layout = layout,
        pixelDensity = pixelDensity,
        configuration = instance,
    )
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = "Sugarlicious CGM-Graph",
        modifier = GlanceModifier.fillMaxSize(),
        contentScale = ContentScale.FillBounds,
    )
}

@Composable
private fun MetabolicWidgetContent(state: TherapyDisplayState?, compact: Boolean, palette: WidgetPalette) {
    val now = System.currentTimeMillis()
    val freshness = TherapyDisplayFormatter.freshness(state, now)
    val displayable = TherapyDisplayFormatter.isGlucoseDisplayable(state, now)
    val horizontalInset = if (compact) 16.dp else 32.dp
    val width = ((LocalSize.current.width - horizontalInset) / 3).coerceAtLeast(if (compact) 48.dp else 56.dp)

    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        FlatMetric("IOB", state?.insulin?.totalIob?.takeIf { displayable }?.let { String.format(Locale.US, "%.1f U", it) } ?: "–", WidgetIob, GlanceModifier.width(width), compact, palette)
        FlatMetric("COB", state?.carbs?.cobGrams?.takeIf { displayable }?.let { String.format(Locale.US, "%.0f g", it) } ?: "–", WidgetCob, GlanceModifier.width(width), compact, palette)
        FlatMetric("BASAL", state?.basal?.currentUnitsPerHour?.takeIf { displayable }?.let { String.format(Locale.US, "%.2f", it) } ?: "–", WidgetBasal, GlanceModifier.width(width), compact, palette)
    }
    Spacer(GlanceModifier.height(if (compact) SugarliciousSpacing.Xs else SugarliciousSpacing.Sm))
    Text(
        if (displayable) widgetStatusLine(state, freshness, now, compact) else TherapyDisplayFormatter.freshnessLabel(freshness),
        style = TextStyle(color = statusColor(freshness, palette), fontWeight = FontWeight.Medium, fontSize = if (compact) 9.sp else 11.sp),
    )
}

@Composable
private fun FlatMetric(
    label: String,
    value: String,
    accent: ColorProvider,
    modifier: GlanceModifier,
    compact: Boolean,
    palette: WidgetPalette,
) {
    Column(modifier = modifier.padding(horizontal = SugarliciousSpacing.Xs)) {
        Text(label, style = TextStyle(color = accent, fontWeight = FontWeight.Bold, fontSize = if (compact) 9.sp else 11.sp))
        Text(value, style = TextStyle(color = widgetColor(palette.argb(WidgetColorRole.TEXT)), fontWeight = FontWeight.Bold, fontSize = if (compact) 15.sp else 19.sp))
    }
}

@Composable
private fun ActivityWidgetContent(snapshot: HealthConnectSnapshot?, compact: Boolean, palette: WidgetPalette) {
    val horizontalInset = if (compact) 16.dp else 32.dp
    val width = ((LocalSize.current.width - horizontalInset) / 2).coerceAtLeast(if (compact) 72.dp else 80.dp)
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        FlatMetric("SCHRITTE", snapshot?.steps?.toString() ?: "–", WidgetCyan, GlanceModifier.width(width), compact, palette)
        FlatMetric("PULS", snapshot?.latestHeartRate?.let { "$it bpm" } ?: "–", WidgetHeartRate, GlanceModifier.width(width), compact, palette)
    }
    Spacer(GlanceModifier.height(if (compact) SugarliciousSpacing.Sm else SugarliciousSpacing.Md))
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        FlatMetric("AKTIV", snapshot?.activeMinutes?.let { "$it min" } ?: "–", WidgetBasal, GlanceModifier.width(width), compact, palette)
        FlatMetric("KCAL", snapshot?.activeCaloriesKcal?.let { String.format(Locale.US, "%.0f", it) } ?: "–", WidgetCob, GlanceModifier.width(width), compact, palette)
    }
}

private fun widgetStatusLine(state: TherapyDisplayState?, freshness: Freshness, now: Long, compact: Boolean): String {
    val source = TherapyDisplayFormatter.sourceName(state?.source)
    val age = TherapyDisplayFormatter.ageMinutesValue(state?.glucose?.measuredAtEpochMs, now)?.let { "$it min" }
    val parts = if (compact) listOf(source, age.orEmpty()) else listOf(source, age.orEmpty(), TherapyDisplayFormatter.freshnessLabel(freshness))
    return parts.filter(String::isNotBlank).joinToString(" · ")
}

internal fun widgetAge(state: TherapyDisplayState?, now: Long): String =
    TherapyDisplayFormatter.ageMinutesValue(state?.glucose?.measuredAtEpochMs, now)?.let { "$it min" }.orEmpty()

internal fun widgetFreshnessStatus(freshness: Freshness): String = TherapyDisplayFormatter.freshnessLabel(freshness)

private fun statusColor(freshness: Freshness, palette: WidgetPalette): ColorProvider =
    widgetColor(
        palette.argb(
            when (freshness) {
                Freshness.CURRENT -> WidgetColorRole.IN_RANGE
                Freshness.DELAYED -> WidgetColorRole.HIGH
                Freshness.STALE, Freshness.ERROR, Freshness.NO_DATA -> WidgetColorRole.URGENT_LOW
            },
        ),
    )

/**
 * Produces the real Glance graph surface. It deliberately uses the same canonical history and
 * fixed logarithmic glucose axis as the in-app graph, while retaining an independent palette.
 */
internal fun renderWidgetGraph(
    state: TherapyDisplayState?,
    palette: WidgetPalette,
    width: Int = 800,
    height: Int = 360,
    now: Long = System.currentTimeMillis(),
    thresholds: app.aapswear.model.CgmThresholds = app.aapswear.model.CgmThresholds.DEFAULT,
    layout: ResponsiveWidgetLayout = responsiveWidgetLayout(width.toFloat(), height.toFloat()),
    pixelDensity: Float = 1f,
    configuration: WidgetInstanceConfiguration = WidgetInstanceConfiguration(showTimeAxis = true),
    clipToWidgetShape: Boolean = true,
    graphLeftInsetDp: Float? = null,
): Bitmap {
    val safeWidth = width.coerceAtLeast(96)
    val safeHeight = height.coerceAtLeast(72)
    val bitmap = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    if (clipToWidgetShape) {
        clipWidgetCanvas(canvas, safeWidth, safeHeight, configuration.cornerRadiusDp.takeIf { it > 0 }?.toFloat() ?: SAMSUNG_WIDGET_RADIUS_FALLBACK_DP, pixelDensity)
    }
    val outerBackground = if (clipToWidgetShape && configuration.backgroundEnabled) configuration.backgroundArgb else AndroidColor.TRANSPARENT
    val graphBackground = palette.argb(WidgetColorRole.GRAPH_BACKGROUND)
    val text = palette.argb(WidgetColorRole.AXIS)
    canvas.drawColor(outerBackground)

    // LocalSize is expressed in dp while an ImageProvider bitmap is pixel based. Rendering at
    // dp resolution makes launchers upscale the bitmap, producing the blurred/stretched result.
    val density = pixelDensity.coerceIn(1f, 4f)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = text
        textSize = layout.graphAxisTextSp * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val metrics = widgetGraphMetrics(
        safeWidth,
        safeHeight,
        density,
        layout,
        textPaint,
        configuration.showTimeAxis,
        configuration.graphCornerRadiusDp.toFloat(),
        graphLeftInsetDp,
    )
    val plot = metrics.plot
    val graphBounds = metrics.graphBounds
    val targetLow = thresholds.lowMgDl
    val targetHigh = thresholds.highMgDl
    val windowMs = configuration.graphHours.toLong() * 60L * 60_000L
    val allSamples = canonicalWidgetSamples(state, now, windowMs)
    val displayable = TherapyDisplayFormatter.isGlucoseDisplayable(state, now)
    val samples = if (displayable) allSamples else emptyList()
    val excursion = if (displayable) CgmGraphPolicy.rangeExcursion(allSamples, thresholds) else null
    val yScale = widgetYScale(configuration.scaleMode, allSamples.map(GlucoseSample::valueMgDl), targetLow, targetHigh)
    val targetTop = yScale.map(targetHigh, plot)
    val targetBottom = yScale.map(targetLow, plot)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val graphClip = widgetGraphClipPath(metrics)
    canvas.save()
    canvas.clipPath(graphClip)
    fill.color = graphBackground
    canvas.drawRect(graphBounds, fill)
    if (excursion == RangeExcursion.HIGH) {
        fill.color = palette.argb(WidgetColorRole.RANGE_HIGH)
        canvas.drawRect(graphBounds.left, graphBounds.top, graphBounds.right, targetTop, fill)
    }
    fill.color = palette.argb(WidgetColorRole.RANGE_IN_RANGE)
    canvas.drawRect(graphBounds.left, targetTop, graphBounds.right, targetBottom, fill)
    if (excursion == RangeExcursion.LOW) {
        fill.color = palette.argb(WidgetColorRole.RANGE_LOW)
        canvas.drawRect(graphBounds.left, targetBottom, graphBounds.right, graphBounds.bottom, fill)
    }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = metrics.lineWidthPx
    }
    line.color = palette.argb(WidgetColorRole.HIGH_LINE)
    canvas.drawLine(graphBounds.left, targetTop, graphBounds.right, targetTop, line)
    line.color = palette.argb(WidgetColorRole.LOW_LINE)
    canvas.drawLine(graphBounds.left, targetBottom, graphBounds.right, targetBottom, line)

    val timeWindow = GraphTimeWindow.live(now, windowMs)
    val start = timeWindow.startEpochMs
    fun x(timestamp: Long): Float = widgetGraphPointX(timestamp, timeWindow, plot)
    val liveX = x(timeWindow.liveEdgeEpochMs)
    val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = metrics.outlineWidthPx
        color = palette.argb(WidgetColorRole.DOT_OUTLINE)
    }
    samples.forEach { sample ->
        // The widget is redrawn as one bitmap. Do not pin multiple samples to an inner edge:
        // clipping is handled by the widget shape and every X position remains timestamp-derived.
        val pointX = x(sample.measuredAtEpochMs)
        val y = yScale.map(sample.valueMgDl, plot)
        pointPaint.color = palette.argb(
            when (widgetGlucoseColorRole(sample.valueMgDl, thresholds)) {
                WidgetColorRole.HIGH, WidgetColorRole.VERY_HIGH -> WidgetColorRole.DOT_HIGH
                WidgetColorRole.LOW, WidgetColorRole.URGENT_LOW -> WidgetColorRole.DOT_LOW
                else -> WidgetColorRole.DOT_IN_RANGE
            },
        )
        val radius = metrics.dotRadiusPx
        canvas.drawCircle(pointX, y, radius, pointPaint)
        canvas.drawCircle(pointX, y, radius + outlinePaint.strokeWidth / 2f, outlinePaint)
    }
    canvas.restore()

    textPaint.textAlign = Paint.Align.CENTER
    line.strokeWidth = metrics.lineWidthPx
    if (configuration.showTimeAxis) RelativeGraphTimeAxis.ticks(start, now, now, RelativeGraphTimeAxis.intervalHours(configuration.graphHours.toDouble())).forEach { tick ->
        val tickX = if (tick.hoursBack == 0) liveX else x(tick.timestampEpochMs)
        if (tickX in plot.left..plot.right) {
            val labelSize = if (tick.hoursBack == 0) metrics.nowTextSizePx else metrics.axisTextSizePx
            textPaint.textSize = labelSize
            val labelHalfWidth = textPaint.measureText(tick.label) / 2f
            val labelX = tickX.coerceIn(labelHalfWidth + metrics.edgeGapPx, safeWidth - labelHalfWidth - metrics.edgeGapPx)
            line.color = palette.argb(if (tick.hoursBack == 0) WidgetColorRole.DIVIDER else WidgetColorRole.AXIS_TICK)
            canvas.drawLine(labelX, graphBounds.bottom + 2f * density, labelX, graphBounds.bottom + 7f * density, line)
            canvas.drawText(tick.label, labelX, metrics.axisBaselinePx, textPaint)
            textPaint.textSize = metrics.axisTextSizePx
        }
    }
    textPaint.textAlign = Paint.Align.LEFT
    canvas.drawText(targetHigh.roundToInt().toString(), graphBounds.right + 4f * density, targetTop - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f, textPaint)
    canvas.drawText(targetLow.roundToInt().toString(), graphBounds.right + 4f * density, targetBottom - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f, textPaint)
    drawWidgetOutline(canvas, safeWidth, safeHeight, configuration, density)
    return bitmap
}

internal fun widgetGraphPointX(timestampEpochMs: Long, timeWindow: GraphTimeWindow, plot: RectF): Float =
    timeWindow.plotX(timestampEpochMs, plot.left, plot.width())

internal data class WidgetGraphMetrics(
    val graphBounds: RectF,
    val plot: RectF,
    val graphCornerRadiusPx: Float,
    val axisTextSizePx: Float,
    val nowTextSizePx: Float,
    val axisBaselinePx: Float,
    val dotRadiusPx: Float,
    val outlineWidthPx: Float,
    val lineWidthPx: Float,
    val edgeGapPx: Float,
)

internal fun widgetGraphClipPath(metrics: WidgetGraphMetrics): Path = Path().apply {
    addRoundRect(
        metrics.graphBounds.left,
        metrics.graphBounds.top,
        metrics.graphBounds.right,
        metrics.graphBounds.bottom,
        metrics.graphCornerRadiusPx,
        metrics.graphCornerRadiusPx,
        Path.Direction.CW,
    )
}

internal fun widgetGraphMetrics(
    widthPx: Int,
    heightPx: Int,
    density: Float,
    layout: ResponsiveWidgetLayout,
    textPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = layout.graphAxisTextSp * density.coerceIn(1f, 4f)
    },
    showTimeAxis: Boolean = true,
    graphCornerRadiusDp: Float = DEFAULT_WIDGET_GRAPH_CORNER_RADIUS_DP.toFloat(),
    graphLeftInsetDp: Float? = null,
): WidgetGraphMetrics {
    val safeDensity = density.coerceIn(1f, 4f)
    val lowSurface = heightPx / safeDensity < 110f
    val axisText = (if (lowSurface) minOf(layout.graphAxisTextSp, 8f) else layout.graphAxisTextSp) * safeDensity
    val nowText = (layout.graphAxisTextSp + 2f).coerceAtMost(13f) * safeDensity
    textPaint.textSize = axisText
    // Axis text sits inside the full-bleed surface, but must also clear the launcher's rounded
    // clipping mask. A tiny edge clamp is insufficient near the lower corners (notably for 3h).
    val edgeGap = (if (lowSurface) 4f else 8f) * safeDensity
    val dotRadius = layout.graphDotRadiusDp.coerceIn(2.4f, 2.5f) * safeDensity
    val outline = layout.graphOutlineDp.coerceIn(0.8f, 1f) * safeDensity
    val lineWidth = layout.graphLineDp.coerceIn(1f, 1.2f) * safeDensity
    val yLabelWidth = maxOf(textPaint.measureText("160"), textPaint.measureText("80"))
    textPaint.textSize = nowText
    val nowLabelHalf = textPaint.measureText("jetzt") / 2f
    textPaint.textSize = axisText
    val fontHeight = textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent
    val bottomBand = if (showTimeAxis) {
        maxOf(fontHeight + (if (lowSurface) 4f else 7f) * safeDensity, (if (lowSurface) 15f else 19f) * safeDensity)
            .coerceAtMost(heightPx * 0.28f)
    } else {
        2f * safeDensity
    }
    val graphLeftInset = (graphLeftInsetDp ?: if (lowSurface) 4f else 8f) * safeDensity
    val graphVerticalInset = (if (lowSurface) 4f else 8f) * safeDensity
    val graphLeft = graphLeftInset
    val graphTop = graphVerticalInset
    val graphRightInset = maxOf(yLabelWidth + 10f * safeDensity, nowLabelHalf + edgeGap) + graphLeftInset
    val graphRight = (widthPx - graphRightInset).coerceAtLeast(graphLeft + 24f * safeDensity)
    val graphBottom = (heightPx - bottomBand - graphVerticalInset).coerceAtLeast(graphTop + 24f * safeDensity)
    val graphBounds = RectF(graphLeft, graphTop, graphRight, graphBottom)
    val pointInset = (dotRadius + outline / 2f).coerceAtMost(minOf(graphBounds.width(), graphBounds.height()) * 0.12f)
    val plot = RectF(
        graphBounds.left + pointInset,
        graphBounds.top + pointInset,
        graphBounds.right - pointInset,
        graphBounds.bottom - pointInset,
    )
    val cornerRadius = (graphCornerRadiusDp.coerceIn(
        MIN_WIDGET_GRAPH_CORNER_RADIUS_DP.toFloat(),
        MAX_WIDGET_GRAPH_CORNER_RADIUS_DP.toFloat(),
    ) * safeDensity).coerceAtMost(minOf(graphBounds.width(), graphBounds.height()) / 2f)
    val baseline = if (showTimeAxis) heightPx - textPaint.fontMetrics.descent - 1f * safeDensity else heightPx.toFloat()
    return WidgetGraphMetrics(
        graphBounds = graphBounds,
        plot = plot,
        graphCornerRadiusPx = cornerRadius,
        axisTextSizePx = axisText,
        nowTextSizePx = nowText,
        axisBaselinePx = baseline,
        dotRadiusPx = dotRadius,
        outlineWidthPx = outline,
        lineWidthPx = lineWidth,
        edgeGapPx = edgeGap,
    )
}

@Composable
private fun GlucoseGraphWidgetContent(
    state: TherapyDisplayState?,
    palette: WidgetPalette,
    thresholds: app.aapswear.model.CgmThresholds,
    layout: ResponsiveWidgetLayout,
    instance: WidgetInstanceConfiguration,
) {
    val size = LocalSize.current
    val density = LocalContext.current.resources.displayMetrics.density.coerceAtLeast(1f)
    val bitmap = renderGlucoseGraphWidget(
        state, palette,
        (size.width.value * density).roundToInt().coerceAtLeast(96),
        (size.height.value * density).roundToInt().coerceAtLeast(96),
        System.currentTimeMillis(), thresholds, layout, density, instance,
    )
    Image(ImageProvider(bitmap), "Glukose, Trend und CGM-Graph", GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
}

internal fun renderMinimalGlucoseWidget(
    state: TherapyDisplayState?,
    palette: WidgetPalette,
    width: Int,
    height: Int,
    now: Long = System.currentTimeMillis(),
    thresholds: app.aapswear.model.CgmThresholds = app.aapswear.model.CgmThresholds.DEFAULT,
    layout: ResponsiveWidgetLayout = responsiveWidgetLayout(width.toFloat(), height.toFloat()),
    pixelDensity: Float = 1f,
    options: GlucoseWidgetRenderOptions = GlucoseWidgetRenderOptions(
        glucoseScale = configurationScale(100),
        trendScale = configurationScale(100),
    ),
): Bitmap {
    val bitmap = Bitmap.createBitmap(width.coerceAtLeast(48), height.coerceAtLeast(48), Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    clipWidgetCanvas(canvas, bitmap.width, bitmap.height, options.cornerRadiusDp, pixelDensity)
    canvas.drawColor(palette.argb(WidgetColorRole.BACKGROUND))
    val displayable = TherapyDisplayFormatter.isGlucoseDisplayable(state, now)
    val samples = canonicalWidgetSamples(state, now)
    val presentation = widgetRangePresentation(state, samples, thresholds, now)
    val glucose = state?.glucose
    val value = if (displayable && glucose != null) TherapyDisplayFormatter.glucose(glucose) else "–"
    val color = palette.argb(if (displayable) presentation.visibleRole else WidgetColorRole.TEXT)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = layout.glucoseTextSp * pixelDensity.coerceIn(1f, 4f) * options.glucoseScale
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val textWidth = paint.measureText(value)
    val textBounds = Rect().also { paint.getTextBounds(value, 0, value.length, it) }
    val textHeight = textBounds.height().toFloat().coerceAtLeast(paint.textSize * 0.65f)
    val spec = if (displayable) glucose?.trend?.let(TrendVisuals::spec) else null
    // The widget arrow intentionally sits 4 dp below the Mobile value height. It remains the
    // canonical Mobile vector and is uniformly scaled for every rotation.
    val arrowTargetHeight = widgetTrendArrowTargetHeight(textHeight, options.trendScale, pixelDensity)
    val arrowGeometry = spec?.let { trendArrowGeometry(arrowTargetHeight, it) }
    val arrowSize = arrowGeometry?.scalePx ?: 0f
    val arrowWidth = arrowGeometry?.groupWidthPx ?: 0f
    val gap = if (spec != null) (paint.textSize * (6f / 42f)).coerceAtLeast(3f) else 0f
    val groupWidth = textWidth + gap + arrowWidth
    val startX = if (options.leftAligned) options.leftInsetDp * pixelDensity else (bitmap.width - groupWidth) / 2f
    val baseline = bitmap.height / 2f - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
    canvas.drawText(value, startX, baseline, paint)
    if (spec != null) {
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = palette.argb(
                when (presentation.visibleRole) {
                    WidgetColorRole.HIGH, WidgetColorRole.VERY_HIGH -> WidgetColorRole.TREND_HIGH
                    WidgetColorRole.LOW, WidgetColorRole.URGENT_LOW -> WidgetColorRole.TREND_LOW
                    else -> WidgetColorRole.TREND_IN_RANGE
                },
            )
            style = Paint.Style.FILL
        }
        val left = startX + textWidth + gap
        val top = bitmap.height / 2f - arrowTargetHeight / 2f
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(arrowSize, arrowSize)
        TrendVectorPaths.forAsset(spec.asset).forEach { pathData ->
            val path = PathParser.createPathFromPathData(pathData)
            canvas.drawPath(path, arrowPaint)
        }
        canvas.restore()
    }
    drawWidgetOutline(canvas, bitmap.width, bitmap.height, options, pixelDensity)
    return bitmap
}

internal data class GlucoseWidgetRenderOptions(
    val glucoseScale: Float = 1f,
    val trendScale: Float = 1f,
    val leftAligned: Boolean = false,
    val leftInsetDp: Float = 12f,
    val outlineEnabled: Boolean = false,
    val outlineArgb: Int = AndroidColor.DKGRAY,
    val cornerRadiusDp: Float = 20f,
)

private fun configurationScale(percent: Int): Float = percent.coerceIn(70, 130) / 100f

internal fun widgetTrendArrowTargetHeight(textHeightPx: Float, trendScale: Float, pixelDensity: Float): Float =
    GlucoseTrendSizing.arrowHeightForGlucoseHeight(textHeightPx, trendScale)
        .coerceAtLeast(8f * pixelDensity.coerceIn(1f, 4f))

private fun drawWidgetOutline(
    canvas: AndroidCanvas,
    width: Int,
    height: Int,
    configuration: WidgetInstanceConfiguration,
    density: Float,
) {
    if (!configuration.outlineEnabled) return
    val stroke = density.coerceIn(1f, 4f)
    val radius = (configuration.cornerRadiusDp.takeIf { it > 0 } ?: 20) * density
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = configuration.outlineArgb
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }
    canvas.drawRoundRect(stroke / 2f, stroke / 2f, width - stroke / 2f, height - stroke / 2f, radius, radius, paint)
}

private fun drawWidgetOutline(canvas: AndroidCanvas, width: Int, height: Int, options: GlucoseWidgetRenderOptions, density: Float) {
    if (!options.outlineEnabled) return
    val stroke = density.coerceIn(1f, 4f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = options.outlineArgb
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }
    val radius = options.cornerRadiusDp * density
    canvas.drawRoundRect(stroke / 2f, stroke / 2f, width - stroke / 2f, height - stroke / 2f, radius, radius, paint)
}

private fun clipWidgetCanvas(canvas: AndroidCanvas, width: Int, height: Int, radiusDp: Float, density: Float) {
    val radius = radiusDp * density.coerceIn(1f, 4f)
    val clip = Path().apply {
        addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, Path.Direction.CW)
    }
    canvas.clipPath(clip)
}

internal data class TrendArrowGeometry(
    val scalePx: Float,
    val groupWidthPx: Float,
)

internal fun trendArrowGeometry(targetVisibleHeightPx: Float, spec: TrendVisualSpec): TrendArrowGeometry {
    val scale = targetVisibleHeightPx / spec.canvasHeight
    return TrendArrowGeometry(
        scalePx = scale,
        groupWidthPx = spec.canvasWidth * scale,
    )
}

internal data class WidgetYScale(val mode: WidgetScaleMode, val minimum: Double, val maximum: Double) {
    fun map(value: Double, plot: RectF): Float {
        val ratio = when (mode) {
            WidgetScaleMode.LOGARITHMIC -> {
                val safe = value.coerceAtLeast(1.0)
                ((kotlin.math.ln(safe) - kotlin.math.ln(minimum)) /
                    (kotlin.math.ln(maximum) - kotlin.math.ln(minimum))).coerceIn(0.0, 1.0)
            }
            else -> ((value - minimum) / (maximum - minimum)).coerceIn(0.0, 1.0)
        }
        return plot.bottom - ratio.toFloat() * plot.height()
    }
}

internal fun widgetYScale(
    mode: WidgetScaleMode,
    values: List<Double>,
    targetLow: Double,
    targetHigh: Double,
): WidgetYScale = when (mode) {
    WidgetScaleMode.STATIC -> WidgetYScale(mode, 40.0, 400.0)
    WidgetScaleMode.LOGARITHMIC -> WidgetYScale(mode, 40.0, 400.0)
    WidgetScaleMode.DYNAMIC -> {
        val valid = values.filter { it.isFinite() && it in 20.0..1_000.0 } + listOf(targetLow, targetHigh)
        val low = ((valid.minOrNull() ?: 40.0) - 20.0).coerceAtMost(40.0).coerceAtLeast(20.0)
        val high = ((valid.maxOrNull() ?: 400.0) + 20.0).coerceAtLeast(180.0).coerceAtMost(1_000.0)
        WidgetYScale(mode, low, high.coerceAtLeast(low + 20.0))
    }
}

internal fun renderGlucoseGraphWidget(
    state: TherapyDisplayState?,
    palette: WidgetPalette,
    width: Int,
    height: Int,
    now: Long = System.currentTimeMillis(),
    thresholds: app.aapswear.model.CgmThresholds = app.aapswear.model.CgmThresholds.DEFAULT,
    layout: ResponsiveWidgetLayout = responsiveWidgetLayout(width.toFloat(), height.toFloat()),
    pixelDensity: Float = 1f,
    configuration: WidgetInstanceConfiguration = WidgetInstanceConfiguration(),
): Bitmap {
    val safeWidth = width.coerceAtLeast(96)
    val safeHeight = height.coerceAtLeast(96)
    val combinedMetrics = combinedWidgetMetrics(
        safeWidth,
        safeHeight,
        pixelDensity,
        configuration.glucoseGraphValuePercent,
    )
    val topHeight = combinedMetrics.headerHeightPx
    val graphWidth = combinedMetrics.graphFrame.width()
    val graphHeight = combinedMetrics.graphFrame.height()
    val topLayout = responsiveWidgetLayout(safeWidth / pixelDensity, topHeight / pixelDensity)
    val top = renderMinimalGlucoseWidget(
        state, palette, safeWidth, topHeight, now, thresholds, topLayout, pixelDensity,
        GlucoseWidgetRenderOptions(
            glucoseScale = configurationScale(configuration.glucoseScalePercent) * 0.78f,
            // The reference ratio was measured from this 2x2 composition. Scaling the glucose
            // typography keeps the accepted reference appearance; the arrow then follows it.
            trendScale = configurationScale(configuration.trendScalePercent),
            leftAligned = true,
            leftInsetDp = 12f,
        ),
    )
    val graph = renderWidgetGraph(
        state,
        palette,
        graphWidth,
        graphHeight,
        now,
        thresholds,
        responsiveWidgetLayout(graphWidth / pixelDensity, graphHeight / pixelDensity),
        pixelDensity,
        configuration.copy(outlineEnabled = false),
        clipToWidgetShape = false,
        graphLeftInsetDp = 12f,
    )
    val bitmap = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    clipWidgetCanvas(
        canvas, safeWidth, safeHeight,
        configuration.cornerRadiusDp.takeIf { it > 0 }?.toFloat() ?: SAMSUNG_WIDGET_RADIUS_FALLBACK_DP,
        pixelDensity,
    )
    canvas.drawColor(if (configuration.backgroundEnabled) configuration.backgroundArgb else AndroidColor.TRANSPARENT)
    canvas.drawBitmap(top, 0f, 0f, null)
    canvas.drawBitmap(graph, combinedMetrics.graphFrame.left.toFloat(), combinedMetrics.graphFrame.top.toFloat(), null)

    val unit = when (state?.glucose?.displayUnit) {
        GlucoseUnit.MMOL_L -> "mmol/L"
        GlucoseUnit.MG_DL -> "mg/dL"
        null -> ""
    }
    if (configuration.showGlucoseUnit && unit.isNotBlank() && TherapyDisplayFormatter.isGlucoseDisplayable(state, now)) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.argb(WidgetColorRole.TEXT)
            alpha = 170
            textAlign = Paint.Align.LEFT
            textSize = (10f * pixelDensity).coerceAtMost(topHeight * 0.15f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(unit, 12f * pixelDensity, topHeight - 2f * pixelDensity, paint)
    }
    drawWidgetOutline(canvas, safeWidth, safeHeight, configuration, pixelDensity)
    return bitmap
}

internal fun combinedWidgetTopHeight(heightPx: Int, valuePercent: Int): Int {
    val safeHeight = heightPx.coerceAtLeast(96)
    return (safeHeight * valuePercent.coerceIn(MIN_COMBINED_WIDGET_VALUE_PERCENT, MAX_COMBINED_WIDGET_VALUE_PERCENT) / 100f)
        .roundToInt()
        .coerceIn(40, safeHeight - 64)
}

internal data class CombinedWidgetMetrics(
    val headerHeightPx: Int,
    val graphFrame: Rect,
)

internal fun combinedWidgetMetrics(
    widthPx: Int,
    heightPx: Int,
    density: Float,
    valuePercent: Int,
): CombinedWidgetMetrics {
    val safeWidth = widthPx.coerceAtLeast(96)
    val safeHeight = heightPx.coerceAtLeast(96)
    val safeDensity = density.coerceIn(1f, 4f)
    val headerHeight = combinedWidgetTopHeight(safeHeight, valuePercent)
    val sectionGap = (2f * safeDensity).roundToInt().coerceAtLeast(1)
    val graphTop = (headerHeight + sectionGap).coerceAtMost(safeHeight - 64)
    return CombinedWidgetMetrics(
        headerHeightPx = headerHeight,
        graphFrame = Rect(0, graphTop, safeWidth, safeHeight),
    )
}

internal fun canonicalWidgetSamples(
    state: TherapyDisplayState?,
    now: Long,
    windowMs: Long = 3L * 60L * 60_000L,
): List<GlucoseSample> {
    val currentSample = state?.glucose?.let { glucose ->
        GlucoseSample(
            valueMgDl = glucose.valueMgDl,
            measuredAtEpochMs = glucose.measuredAtEpochMs,
            source = glucose.source,
            sensorId = glucose.sensorId,
            sessionId = glucose.sessionId,
            sequenceNumber = glucose.sequenceNumber,
            receivedAtEpochMs = glucose.receivedAtEpochMs,
            quality = glucose.quality,
        )
    }
    return CanonicalCgmHistory.merge(
        samples = state?.glucoseHistory.orEmpty() + listOfNotNull(currentSample),
        nowEpochMs = now,
        preferredSource = state?.source,
        windowMs = windowMs,
    ).filter { it.measuredAtEpochMs in (now - windowMs)..now }
}
