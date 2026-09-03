package app.aapswear.complications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.content.res.Configuration
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.data.TimeRange
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import app.aapswear.model.AppearanceMode
import app.aapswear.model.CgmQuality
import app.aapswear.model.CgmSourceState
import app.aapswear.model.CgmThresholds
import app.aapswear.model.DataSourceId
import app.aapswear.model.Freshness
import app.aapswear.model.FreshnessPolicy
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.GraphTimeWindow
import app.aapswear.model.TargetState
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
import app.aapswear.protocol.WatchGraphColors
import app.aapswear.storage.TrendArrowStylePreferences
import app.aapswear.uishared.SharedWearCgmGraphInput
import app.aapswear.uishared.SharedWearCgmGraphPalette
import app.aapswear.uishared.SharedWearCgmGraphRenderer
import app.aapswear.uishared.SharedWearCgmGraphStyle
import java.time.Instant

internal data class DirectToWatchHeaderPresentation(
    val glucose: String,
    val secondary: String,
    val trend: Trend? = null,
)

internal data class DirectToWatchGraphStatusPresentation(val text: String)

internal object DirectToWatchPresentationFormatter {
    fun header(state: TherapyDisplayState?, nowEpochMs: Long): DirectToWatchHeaderPresentation {
        val freshness = TherapyDisplayFormatter.freshness(state, nowEpochMs)
        if (!isDirect(state) || !TherapyDisplayFormatter.isGlucoseDisplayable(state, nowEpochMs)) {
            return DirectToWatchHeaderPresentation(
                glucose = "—",
                secondary = unavailableLabel(state, freshness),
            )
        }
        val glucose = requireNotNull(state?.glucose)
        val delta = TherapyDisplayFormatter.signedDelta(glucose.deltaMgDl, glucose.displayUnit)
        val unit = if (glucose.displayUnit == GlucoseUnit.MMOL_L) "mmol/L" else "mg/dL"
        return DirectToWatchHeaderPresentation(
            glucose = TherapyDisplayFormatter.glucose(glucose),
            secondary = listOf(delta, unit).filter(String::isNotBlank).joinToString(" "),
            trend = glucose.trend.takeIf { TherapyDisplayFormatter.trendArrow(it).isNotBlank() },
        )
    }

    fun graphStatus(state: TherapyDisplayState?, nowEpochMs: Long, graphHours: Int): DirectToWatchGraphStatusPresentation {
        val freshness = TherapyDisplayFormatter.freshness(state, nowEpochMs)
        val age = TherapyDisplayFormatter.ageMinutesValue(state?.glucose?.measuredAtEpochMs, nowEpochMs)?.let { "$it min" } ?: "—"
        val detail = if (isDirect(state) && state?.glucose != null) age else unavailableLabel(state, freshness)
        return DirectToWatchGraphStatusPresentation("${graphHours}h • $detail")
    }

    fun samples(state: TherapyDisplayState?, nowEpochMs: Long, graphHours: Int): List<GlucoseSample> {
        if (!isDirect(state)) return emptyList()
        val cutoff = nowEpochMs - graphHours * HOUR_MS
        return buildList {
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
        }.asSequence()
            .filter {
                it.source == DataSourceId.DEXCOM_G7_WATCH &&
                    it.quality == CgmQuality.VALID &&
                    it.valueMgDl.isFinite() && it.valueMgDl in 20.0..1_000.0 &&
                    it.measuredAtEpochMs in cutoff..(nowEpochMs + FUTURE_TOLERANCE_MS)
            }
            .distinctBy { listOf(it.sensorId, it.sessionId, it.sequenceNumber, it.measuredAtEpochMs) }
            .sortedBy(GlucoseSample::measuredAtEpochMs)
            .toList()
    }

    fun validTimeRange(state: TherapyDisplayState?, nowEpochMs: Long): TimeRange {
        val freshness = TherapyDisplayFormatter.freshness(state, nowEpochMs)
        if (freshness != Freshness.CURRENT && freshness != Freshness.DELAYED) return TimeRange.ALWAYS
        val measuredAt = state?.glucose?.measuredAtEpochMs ?: return TimeRange.ALWAYS
        return TimeRange.before(Instant.ofEpochMilli(measuredAt + FreshnessPolicy.DELAYED_MAX_MS))
    }

    fun isDirect(state: TherapyDisplayState?): Boolean =
        state?.source == DataSourceId.DEXCOM_G7_WATCH &&
            G7LocalReadingResolver.sourceState(state) in setOf(CgmSourceState.WATCH_DIRECT, CgmSourceState.NO_SOURCE)

    private fun unavailableLabel(state: TherapyDisplayState?, freshness: Freshness): String = when {
        state?.glucose?.quality == CgmQuality.SENSOR_ERROR -> "SIGNAL LOSS"
        freshness == Freshness.STALE -> "STALE"
        else -> "NO_SOURCE"
    }

    const val HOUR_MS = 60L * 60_000L
    private const val FUTURE_TOLERANCE_MS = 5L * 60_000L
}

object DirectToWatchPreferences {
    const val NAME = "direct_to_watch"
    private const val KEY_GRAPH_HOURS = "graph.hours"
    val graphHourOptions = listOf(1, 3, 6, 12, 24)

    fun graphHours(context: Context): Int =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getInt(KEY_GRAPH_HOURS, 3).takeIf { it in graphHourOptions } ?: 3

    fun cycleGraphHours(context: Context): Int {
        val current = graphHours(context)
        val next = graphHourOptions[(graphHourOptions.indexOf(current) + 1) % graphHourOptions.size]
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putInt(KEY_GRAPH_HOURS, next).apply()
        return next
    }

    fun trendStyle(context: Context, mode: AppearanceMode) =
        TrendArrowStylePreferences.read(
            context.getSharedPreferences(NAME, Context.MODE_PRIVATE),
            mode,
            Color.WHITE,
        )

    fun saveTrendStyle(context: Context, mode: AppearanceMode, style: app.aapswear.model.TrendArrowStyle) {
        TrendArrowStylePreferences.write(context.getSharedPreferences(NAME, Context.MODE_PRIVATE), mode, style)
        requestUpdates(context)
    }

    fun resetTrendStyle(context: Context, mode: AppearanceMode) {
        TrendArrowStylePreferences.reset(context.getSharedPreferences(NAME, Context.MODE_PRIVATE), mode)
        requestUpdates(context)
    }

    fun requestUpdates(context: Context) {
        listOf(
            DirectToWatchHeaderComplication::class.java,
            DirectToWatchGraphComplication::class.java,
            DirectToWatchStatusComplication::class.java,
        ).forEach { service ->
            ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, service)).requestUpdateAll()
        }
    }
}

abstract class DirectToWatchComplicationService : SuspendingComplicationDataSourceService() {
    final override fun getPreviewData(type: ComplicationType): ComplicationData = build(previewState(), System.currentTimeMillis())

    final override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData =
        build(G7LocalReadingResolver.resolveWatchDirect(this), System.currentTimeMillis())

    protected abstract fun build(state: TherapyDisplayState?, nowEpochMs: Long): ComplicationData

    protected fun collectorTapAction(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(G7_PACKAGE)?.apply {
            component = ComponentName(G7_PACKAGE, G7_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } ?: Intent().setComponent(ComponentName(G7_PACKAGE, G7_ACTIVITY))
        return PendingIntent.getActivity(this, 701, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    protected fun graphScaleTapAction(): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            702,
            Intent(this, DirectToWatchGraphScaleReceiver::class.java).setAction(DirectToWatchGraphScaleReceiver.ACTION_CYCLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    protected fun readThresholds(): CgmThresholds {
        val preferences = getSharedPreferences("watch_display", MODE_PRIVATE)
        return CgmThresholds(
            veryHighMgDl = preferences.getFloat("threshold_very_high", 250f).toDouble(),
            highMgDl = preferences.getFloat("threshold_high", 180f).toDouble(),
            lowMgDl = preferences.getFloat("threshold_low", 70f).toDouble(),
            veryLowMgDl = preferences.getFloat("threshold_very_low", 50f).toDouble(),
        ).takeIf(CgmThresholds::isValid) ?: CgmThresholds.DEFAULT
    }

    private fun previewState(): TherapyDisplayState {
        val now = System.currentTimeMillis()
        val history = (0..36).map { index ->
            GlucoseSample(
                valueMgDl = 105.0 + index * 1.3,
                measuredAtEpochMs = now - (36 - index) * 5L * 60_000L,
                source = DataSourceId.DEXCOM_G7_WATCH,
            )
        }
        return TherapyDisplayState(
            source = DataSourceId.DEXCOM_G7_WATCH,
            sourceContract = "CANONICAL_CGM_V2:WATCH_DIRECT:preview",
            sourceVersion = "G7 Watch Collector",
            receivedAtEpochMs = now,
            glucose = GlucoseState(
                valueMgDl = 152.0,
                displayUnit = GlucoseUnit.MG_DL,
                trend = Trend.FLAT,
                measuredAtEpochMs = now - 2 * 60_000L,
                deltaMgDl = 1.0,
                source = DataSourceId.DEXCOM_G7_WATCH,
            ),
            glucoseHistory = history,
            target = TargetState(lowMgDl = 70.0, highMgDl = 180.0),
        )
    }

    companion object {
        private const val G7_PACKAGE = "app.aapswear.g7watch"
        private const val G7_ACTIVITY = "app.aapswear.g7watch.G7WatchActivity"
    }
}

class DirectToWatchHeaderComplication : DirectToWatchComplicationService() {
    override fun build(state: TherapyDisplayState?, nowEpochMs: Long): ComplicationData {
        val presentation = DirectToWatchPresentationFormatter.header(state, nowEpochMs)
        val bitmap = renderHeader(presentation)
        return SmallImageComplicationData.Builder(
            SmallImage.Builder(Icon.createWithBitmap(bitmap), SmallImageType.PHOTO).build(),
            PlainComplicationText.Builder("Direct to Watch ${presentation.glucose}, ${presentation.secondary}").build(),
        )
            .setTapAction(collectorTapAction())
            .setValidTimeRange(DirectToWatchPresentationFormatter.validTimeRange(state, nowEpochMs))
            .build()
    }

    internal fun renderHeader(presentation: DirectToWatchHeaderPresentation): Bitmap {
        val width = 310
        val height = 108
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 61f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }
        val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SECONDARY_TEXT
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val mode = if ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) AppearanceMode.DARK else AppearanceMode.LIGHT
        val style = DirectToWatchPreferences.trendStyle(this, mode)
        val arrow = presentation.trend?.let {
            TrendComplicationIcon.renderScaled(this, it, 34, style.sizePercent, style = style)
        }
        val gap = if (arrow == null) 0f else 8f
        val valueWidth = valuePaint.measureText(presentation.glucose)
        val arrowWidth = arrow?.width?.toFloat() ?: 0f
        val groupLeft = ((width - valueWidth - gap - arrowWidth) / 2f).coerceAtLeast(0f)
        canvas.drawText(presentation.glucose, groupLeft, 58f, valuePaint)
        arrow?.let { canvas.drawBitmap(it, groupLeft + valueWidth + gap, 0f, null) }
        canvas.drawText(presentation.secondary, width / 2f, 96f, secondaryPaint)
        return bitmap
    }

    private companion object { const val SECONDARY_TEXT = 0xFFA8A8BA.toInt() }
}

class DirectToWatchStatusComplication : DirectToWatchComplicationService() {
    override fun build(state: TherapyDisplayState?, nowEpochMs: Long): ComplicationData {
        val text = DirectToWatchPresentationFormatter.graphStatus(state, nowEpochMs, DirectToWatchPreferences.graphHours(this)).text
        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(text).build(),
            PlainComplicationText.Builder(text).build(),
        ).setTapAction(graphScaleTapAction())
            .setValidTimeRange(DirectToWatchPresentationFormatter.validTimeRange(state, nowEpochMs))
            .build()
    }
}

class DirectToWatchGraphComplication : DirectToWatchComplicationService() {
    override fun build(state: TherapyDisplayState?, nowEpochMs: Long): ComplicationData {
        val hours = DirectToWatchPreferences.graphHours(this)
        val bitmap = renderGraph(state, nowEpochMs, hours)
        return SmallImageComplicationData.Builder(
            SmallImage.Builder(Icon.createWithBitmap(bitmap), SmallImageType.PHOTO).build(),
            PlainComplicationText.Builder("$hours Stunden Direct-to-Watch-Glukoseverlauf").build(),
        ).setTapAction(graphScaleTapAction())
            .setValidTimeRange(DirectToWatchPresentationFormatter.validTimeRange(state, nowEpochMs))
            .build()
    }

    internal fun renderGraph(state: TherapyDisplayState?, nowEpochMs: Long, hours: Int): Bitmap {
        val width = 410
        val height = 184
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val density = resources.displayMetrics.density
        val radius = 20f * density
        canvas.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, Path.Direction.CW) })
        val colors = readGraphColors()
        val thresholds = readThresholds()
        SharedWearCgmGraphRenderer.render(
            canvas = canvas,
            widthPx = width,
            heightPx = height,
            density = density,
            scaledDensity = resources.displayMetrics.scaledDensity,
            input = SharedWearCgmGraphInput(
                history = DirectToWatchPresentationFormatter.samples(state, nowEpochMs, hours),
                timeWindow = GraphTimeWindow.live(nowEpochMs, hours * DirectToWatchPresentationFormatter.HOUR_MS),
                nowEpochMs = nowEpochMs,
                thresholds = thresholds,
                palette = colors.toSharedPalette(),
                style = SharedWearCgmGraphStyle(cornerRadiusDp = 20f),
                emptyLabel = DirectToWatchPresentationFormatter.header(state, nowEpochMs).secondary,
            ),
        )
        return bitmap
    }

    private fun readGraphColors(): WatchGraphColors {
        val defaults = WatchGraphColors()
        val p = getSharedPreferences(DirectToWatchPreferences.NAME, MODE_PRIVATE)
        return WatchGraphColors(
            graphBackground = p.getInt("graph_color_background", defaults.graphBackground),
            rangeLow = p.getInt("graph_color_range_low", defaults.rangeLow),
            rangeInRange = p.getInt("graph_color_range_in", defaults.rangeInRange),
            rangeHigh = p.getInt("graph_color_range_high", defaults.rangeHigh),
            cgmLow = p.getInt("graph_color_cgm_low", defaults.cgmLow),
            cgmInRange = p.getInt("graph_color_cgm_in", defaults.cgmInRange),
            cgmHigh = p.getInt("graph_color_cgm_high", defaults.cgmHigh),
            divider = p.getInt("graph_color_divider", defaults.divider),
            highLine = p.getInt("graph_color_high_line", defaults.highLine),
            lowLine = p.getInt("graph_color_low_line", defaults.lowLine),
            axisLabel = p.getInt("graph_color_axis_label", defaults.axisLabel),
            axisTick = p.getInt("graph_color_axis_tick", defaults.axisTick),
            nowLine = p.getInt("graph_color_now_line", defaults.nowLine),
            outline = p.getInt("graph_color_outline", defaults.outline),
            predictionIob = defaults.predictionIob,
            predictionCob = defaults.predictionCob,
            predictionUam = defaults.predictionUam,
            predictionZeroTemp = defaults.predictionZeroTemp,
            targetValue = p.getInt("graph_color_target_value", defaults.targetValue),
            signalLoss = p.getInt("graph_color_signal_loss", defaults.signalLoss),
        )
    }

    private fun WatchGraphColors.toSharedPalette() = SharedWearCgmGraphPalette(
        background = graphBackground,
        targetArea = rangeInRange,
        highArea = rangeHigh,
        lowArea = rangeLow,
        highLine = highLine,
        lowLine = lowLine,
        dotHigh = cgmHigh,
        dotInRange = cgmInRange,
        dotLow = cgmLow,
        dotOutline = outline,
        axisText = axisLabel,
        axisTick = axisTick,
        nowLine = nowLine,
        border = divider,
        predictionIob = predictionIob,
        predictionCob = predictionCob,
        predictionUam = predictionUam,
        predictionZeroTemp = predictionZeroTemp,
    )
}

class DirectToWatchGraphScaleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_CYCLE) return
        DirectToWatchPreferences.cycleGraphHours(context)
        DirectToWatchPreferences.requestUpdates(context)
    }

    companion object { const val ACTION_CYCLE = "app.aapswear.complications.DIRECT_TO_WATCH_CYCLE_GRAPH" }
}
