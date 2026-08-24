package app.aapswear.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
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
import app.aapswear.mobile.ui.theme.SugarliciousIconSize
import app.aapswear.mobile.ui.theme.SugarliciousRadius
import app.aapswear.mobile.ui.theme.SugarliciousSpacing
import app.aapswear.model.Freshness
import app.aapswear.model.CanonicalCgmHistory
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
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

enum class WidgetKind { GLUCOSE, GRAPH, METABOLIC, ACTIVITY }

private abstract class SugarliciousWidget : GlanceAppWidget() {
    protected abstract val kind: WidgetKind

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = TherapyStateStore(context).state.first()
        val activitySnapshot = if (kind == WidgetKind.ACTIVITY) HealthConnectIntegration.snapshot(context) else null
        val palette = WidgetColorStore.load(context)
        val graphBitmap = if (kind == WidgetKind.GRAPH) renderWidgetGraph(state, palette) else null
        provideContent { WidgetShell(kind, state, activitySnapshot, palette, graphBitmap) }
    }
}

private class GlucoseWidget : SugarliciousWidget() { override val kind = WidgetKind.GLUCOSE }
private class GraphWidget : SugarliciousWidget() { override val kind = WidgetKind.GRAPH }
private class MetabolicWidget : SugarliciousWidget() { override val kind = WidgetKind.METABOLIC }
private class ActivityWidget : SugarliciousWidget() { override val kind = WidgetKind.ACTIVITY }

class GlucoseWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = GlucoseWidget() }
class GraphWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = GraphWidget() }
class MetabolicWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = MetabolicWidget() }
class ActivityWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = ActivityWidget() }

internal object SugarliciousWidgets {
    suspend fun update(context: Context) {
        GlucoseWidget().updateAll(context)
        GraphWidget().updateAll(context)
        MetabolicWidget().updateAll(context)
        ActivityWidget().updateAll(context)
    }
}

@Composable
private fun WidgetShell(
    kind: WidgetKind,
    state: TherapyDisplayState?,
    activitySnapshot: HealthConnectSnapshot?,
    palette: WidgetPalette,
    graphBitmap: Bitmap?,
) {
    val size = LocalSize.current
    val compact = size.width < 210.dp || size.height < 130.dp
    val background = widgetColor(palette.argb(WidgetColorRole.BACKGROUND))

    Column(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .background(background)
                .cornerRadius(SugarliciousRadius.Navigation)
                .padding(if (compact) SugarliciousSpacing.Sm else SugarliciousSpacing.Lg)
                .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        when (kind) {
            WidgetKind.GLUCOSE -> GlucoseWidgetContent(state, compact, palette)
            WidgetKind.GRAPH -> GraphWidgetContent(graphBitmap)
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
private fun GlucoseWidgetContent(state: TherapyDisplayState?, compact: Boolean, palette: WidgetPalette) {
    val now = System.currentTimeMillis()
    val glucose = state?.glucose
    val freshness = TherapyDisplayFormatter.freshness(state, now)
    val displayable = TherapyDisplayFormatter.isGlucoseDisplayable(state, now)
    val value = if (displayable && glucose != null) TherapyDisplayFormatter.glucose(glucose) else "–"
    val arrow = if (displayable && glucose != null) TherapyDisplayFormatter.trendArrow(glucose.trend) else ""
    val delta = if (displayable && glucose != null) {
        TherapyDisplayFormatter.signedDelta(glucose.deltaMgDl, glucose.displayUnit).ifBlank { "–" }
    } else "–"
    val unit = when (glucose?.displayUnit) {
        GlucoseUnit.MMOL_L -> "mmol/L"
        GlucoseUnit.MG_DL -> "mg/dL"
        null -> ""
    }
    val low = state?.target?.lowMgDl ?: 80.0
    val high = state?.target?.highMgDl ?: 160.0
    val valueColor =
        if (displayable && glucose != null) {
            widgetColor(palette.argb(widgetGlucoseColorRole(glucose.valueMgDl, low, high)))
        } else {
            widgetColor(palette.argb(WidgetColorRole.TEXT))
        }
    val textColor = widgetColor(palette.argb(WidgetColorRole.TEXT))
    val trendColor = widgetColor(palette.argb(WidgetColorRole.TREND))

    Column(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(
                value,
                style = TextStyle(color = valueColor, fontWeight = FontWeight.Bold, fontSize = if (compact) 42.sp else 54.sp),
            )
            Spacer(GlanceModifier.width(if (compact) SugarliciousSpacing.Sm else SugarliciousSpacing.Md))
            Text(
                arrow,
                style = TextStyle(color = if (displayable) trendColor else textColor, fontWeight = FontWeight.Bold, fontSize = if (compact) 24.sp else 30.sp),
            )
        }
        Spacer(GlanceModifier.height(SugarliciousSpacing.Xs))
        Text(
            listOf(delta, widgetAge(state, now), unit).filter(String::isNotBlank).joinToString(" · "),
            style = TextStyle(color = textColor, fontWeight = FontWeight.Medium, fontSize = if (compact) 11.sp else 14.sp),
        )
        Text(
            widgetFreshnessStatus(freshness),
            style = TextStyle(color = statusColor(freshness, palette), fontWeight = FontWeight.Bold, fontSize = if (compact) 9.sp else 11.sp),
        )
    }
}

@Composable
private fun GraphWidgetContent(bitmap: Bitmap?) {
    if (bitmap == null) return
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
): Bitmap {
    val safeWidth = width.coerceAtLeast(160)
    val safeHeight = height.coerceAtLeast(100)
    val bitmap = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val background = palette.argb(WidgetColorRole.BACKGROUND)
    val text = palette.argb(WidgetColorRole.TEXT)
    canvas.drawColor(background)

    val density = safeWidth / 400f
    val plot = RectF(50f * density, 20f * density, safeWidth - 16f * density, safeHeight - 44f * density)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = text
        textSize = 16f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val subtleGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = blendArgb(background, text, 0.22f)
        strokeWidth = 1f * density
    }
    listOf(40.0, 80.0, 160.0, 400.0).forEach { value ->
        val y = widgetGlucoseY(value, plot)
        canvas.drawLine(plot.left, y, plot.right, y, subtleGrid)
        canvas.drawText(value.roundToInt().toString(), 4f * density, y - 3f * density, textPaint)
    }

    val targetLow = state?.target?.lowMgDl?.takeIf(Double::isFinite)
    val targetHigh = state?.target?.highMgDl?.takeIf(Double::isFinite)
    if (targetLow != null && targetHigh != null && targetHigh >= targetLow) {
        val top = widgetGlucoseY(targetHigh, plot)
        val bottom = widgetGlucoseY(targetLow, plot)
        val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = blendArgb(background, palette.argb(WidgetColorRole.IN_RANGE), 0.18f)
            style = Paint.Style.FILL
        }
        canvas.drawRect(plot.left, top, plot.right, bottom, targetPaint)
    }

    val windowMs = 3L * 60L * 60_000L
    val samples = canonicalWidgetSamples(state, now, windowMs)
    val low = targetLow ?: 80.0
    val high = targetHigh ?: 160.0
    val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    samples.forEach { sample ->
        val x = plot.left + ((sample.measuredAtEpochMs - (now - windowMs)).toFloat() / windowMs) * plot.width()
        val y = widgetGlucoseY(sample.valueMgDl, plot)
        pointPaint.color = palette.argb(widgetGlucoseColorRole(sample.valueMgDl, low, high))
        canvas.drawCircle(x, y, 4.1f * density, pointPaint)
    }

    val freshness = TherapyDisplayFormatter.freshness(state, now)
    val displayable = TherapyDisplayFormatter.isGlucoseDisplayable(state, now)
    if (!displayable || samples.isEmpty()) {
        val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = blendArgb(background, AndroidColor.BLACK, if (AndroidColor.luminance(background) > 0.5f) 0.10f else 0.34f)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(plot, 18f * density, 18f * density, scrim)
        val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.argb(WidgetColorRole.URGENT_LOW)
            textSize = 24f * density
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(widgetFreshnessStatus(freshness), plot.centerX(), plot.centerY(), statusPaint)
    } else {
        val latest = state?.glucose
        if (latest != null) {
            val summary = "${TherapyDisplayFormatter.glucose(latest)}  ${TherapyDisplayFormatter.trendArrow(latest.trend)}  ${widgetAge(state, now)}"
            textPaint.textSize = 20f * density
            canvas.drawText(summary, plot.left, safeHeight - 13f * density, textPaint)
        }
    }
    return bitmap
}

private fun widgetGlucoseY(valueMgDl: Double, plot: RectF): Float =
    plot.bottom - glucoseLogRatio(valueMgDl).toFloat() * plot.height()

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
