package app.aapswear.complications
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.GoalProgressComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.WeightedElementsComplicationData
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import app.aapswear.model.BasalState
import app.aapswear.model.CarbState
import app.aapswear.model.CgmGraphPolicy
import app.aapswear.model.CgmQuality
import app.aapswear.model.CgmThresholds
import app.aapswear.model.ComplicationPresentationFormatter
import app.aapswear.model.SugarliciousComplicationIds
import app.aapswear.model.DataCapability
import app.aapswear.model.DataSourceId
import app.aapswear.model.DeviceState
import app.aapswear.model.Freshness
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseGraphScale
import app.aapswear.model.GraphTimeWindow
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.InsulinState
import app.aapswear.model.LoopState
import app.aapswear.model.LoopVisualState
import app.aapswear.model.loopPresentation
import app.aapswear.model.ProfileState
import app.aapswear.model.PumpState
import app.aapswear.model.RangeExcursion
import app.aapswear.model.TargetState
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
import app.aapswear.storage.TherapyStateStore
import app.aapswear.protocol.WatchGraphColors
import app.aapswear.protocol.WatchGraphStyle
import kotlinx.coroutines.flow.first

enum class ProviderKind {
    GLUCOSE,
    TREND_ONLY,
    DELTA_ONLY,
    GLUCOSE_PLUS_DELTA,
    GLUCOSE_TREND_DELTA_AGE,
    GLUCOSE_TREND_AGE,
    SENSOR_AGE,
    TIR,
    GLUCOSE_TREND,
    GLUCOSE_DELTA,
    GLUCOSE_TREND_DELTA,
    GLUCOSE_AGE,
    GLUCOSE_IMAGE,
    GLUCOSE_RANGE,
    GLUCOSE_RANGED,
    GRAPH,
    GRAPH_LARGE,
    IOB,
    BOLUS_IOB,
    BASAL_IOB,
    COB,
    IOB_COB,
    IOB_COB_BASAL,
    BASAL,
    TEMP_BASAL,
    TEMP_TARGET,
    LOOP,
    LOOP_LAST,
    PROFILE,
    RESERVOIR,
    PUMP_BATTERY,
    PHONE_BATTERY,
    SOURCE,
    AAPS_STATUS,
    LONG_STATUS,
    DATE,
}

internal fun complicationImageSize(kind: ProviderKind): Pair<Int, Int> = when (kind) {
    // Every WFF consumer presents this as a wide chart. Rendering the bitmap in that native
    // aspect ratio prevents WFF from stretching circular dots and horizontal range bands.
    ProviderKind.GRAPH -> 400 to 140
    ProviderKind.GRAPH_LARGE -> 400 to 240
    ProviderKind.GLUCOSE_IMAGE -> 400 to 200
    else -> 400 to 240
}

abstract class TherapyComplicationService(
    private val kind: ProviderKind,
    private val declaredType: ComplicationType? = null,
    private val declaredCatalogId: Int? = null,
) : SuspendingComplicationDataSourceService() {

    private val catalogId: Int?
        get() = declaredCatalogId ?: when (kind) {
            ProviderKind.GLUCOSE -> SugarliciousComplicationIds.GLUCOSE
            ProviderKind.TREND_ONLY -> SugarliciousComplicationIds.TREND_ONLY
            ProviderKind.DELTA_ONLY -> SugarliciousComplicationIds.DELTA_ONLY
            ProviderKind.GLUCOSE_AGE -> SugarliciousComplicationIds.GLUCOSE_AGE
            ProviderKind.BASAL -> SugarliciousComplicationIds.BASAL
            ProviderKind.IOB -> SugarliciousComplicationIds.IOB
            ProviderKind.COB -> SugarliciousComplicationIds.COB
            ProviderKind.IOB_COB -> SugarliciousComplicationIds.IOB_COB
            ProviderKind.GLUCOSE_TREND -> SugarliciousComplicationIds.GLUCOSE_TREND
            ProviderKind.GLUCOSE_PLUS_DELTA -> SugarliciousComplicationIds.GLUCOSE_PLUS_DELTA
            ProviderKind.GLUCOSE_DELTA -> SugarliciousComplicationIds.TIME_DELTA
            ProviderKind.GLUCOSE_TREND_AGE -> SugarliciousComplicationIds.GLUCOSE_TREND_AGE
            ProviderKind.GLUCOSE_TREND_DELTA -> SugarliciousComplicationIds.GLUCOSE_TREND_DELTA
            ProviderKind.GLUCOSE_TREND_DELTA_AGE -> SugarliciousComplicationIds.GLUCOSE_TREND_DELTA_AGE
            ProviderKind.IOB_COB_BASAL -> SugarliciousComplicationIds.IOB_COB_BASAL
            ProviderKind.LOOP -> SugarliciousComplicationIds.LOOP
            ProviderKind.RESERVOIR -> SugarliciousComplicationIds.RESERVOIR
            ProviderKind.SENSOR_AGE -> SugarliciousComplicationIds.SENSOR_AGE
            ProviderKind.TIR -> SugarliciousComplicationIds.TIR
            ProviderKind.GRAPH -> SugarliciousComplicationIds.GRAPH
            ProviderKind.DATE -> SugarliciousComplicationIds.DATE
            else -> null
        }

    private val appearanceCatalogId: Int?
        get() = catalogId?.let(SugarliciousComplicationIds::baseId)

    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        super.onComplicationActivated(complicationInstanceId, type)
        ActiveComplicationRegistry.activate(this, complicationInstanceId, catalogId)
    }

    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        ActiveComplicationRegistry.deactivate(this, complicationInstanceId)
        super.onComplicationDeactivated(complicationInstanceId)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData =
        build(declaredType ?: type, preview())

    override suspend fun onComplicationRequest(
        request: ComplicationRequest,
    ): ComplicationData {
        ActiveComplicationRegistry.activate(this, request.complicationInstanceId, catalogId)
        val phoneState = TherapyStateStore(this).state.first()
        return build(
            declaredType ?: request.complicationType,
            G7LocalReadingResolver.resolve(this, phoneState),
        )
    }

    private fun build(
        type: ComplicationType,
        state: TherapyDisplayState?,
    ): ComplicationData {
        val now = System.currentTimeMillis()
        val thresholds = readCgmThresholds()
        val glucose = state?.glucose
        val freshness = TherapyDisplayFormatter.freshness(state, now)
        val fresh = freshness == Freshness.CURRENT || freshness == Freshness.DELAYED
        val displayable = TherapyDisplayFormatter.isGlucoseKnown(state)
        val therapyState = state

        val glucoseText =
            if (displayable && glucose != null) glucose(glucose) else DASH
        val trendText =
            if (fresh && glucose != null) arrow(glucose.trend) else ""
        val deltaText =
            if (fresh && glucose != null) {
                signed(glucose.deltaMgDl, glucose.displayUnit)
            } else {
                ""
            }
        val ageText = glucose?.measuredAtEpochMs
            ?.let { timeAgo(it, now) }
            ?: DASH

        val presentationId = when (kind) {
            ProviderKind.GLUCOSE -> SugarliciousComplicationIds.GLUCOSE
            ProviderKind.TREND_ONLY -> SugarliciousComplicationIds.TREND_ONLY
            ProviderKind.DELTA_ONLY -> SugarliciousComplicationIds.DELTA_ONLY
            ProviderKind.GLUCOSE_PLUS_DELTA -> SugarliciousComplicationIds.GLUCOSE_PLUS_DELTA
            ProviderKind.GLUCOSE_TREND_DELTA_AGE -> SugarliciousComplicationIds.GLUCOSE_TREND_DELTA_AGE
            ProviderKind.GLUCOSE_TREND_AGE -> SugarliciousComplicationIds.GLUCOSE_TREND_AGE
            ProviderKind.SENSOR_AGE -> SugarliciousComplicationIds.SENSOR_AGE
            ProviderKind.TIR -> SugarliciousComplicationIds.TIR
            ProviderKind.GLUCOSE_TREND -> SugarliciousComplicationIds.GLUCOSE_TREND
            ProviderKind.GLUCOSE_DELTA -> SugarliciousComplicationIds.TIME_DELTA
            ProviderKind.GLUCOSE_TREND_DELTA -> SugarliciousComplicationIds.GLUCOSE_TREND_DELTA
            ProviderKind.GLUCOSE_AGE -> SugarliciousComplicationIds.GLUCOSE_AGE
            ProviderKind.IOB -> SugarliciousComplicationIds.IOB
            ProviderKind.COB -> SugarliciousComplicationIds.COB
            ProviderKind.IOB_COB -> SugarliciousComplicationIds.IOB_COB
            ProviderKind.IOB_COB_BASAL -> SugarliciousComplicationIds.IOB_COB_BASAL
            ProviderKind.BASAL -> SugarliciousComplicationIds.BASAL
            ProviderKind.LOOP -> SugarliciousComplicationIds.LOOP
            ProviderKind.RESERVOIR -> SugarliciousComplicationIds.RESERVOIR
            ProviderKind.DATE -> SugarliciousComplicationIds.DATE
            else -> null
        }
        val presentation = presentationId?.let {
            ComplicationPresentationFormatter.format(it, state, now, thresholds)
        }

        val pair: Pair<String, String> = presentation?.let {
            it.text to (it.title ?: it.contentDescription)
        } ?: when (kind) {
            ProviderKind.BOLUS_IOB -> units(therapyState?.insulin?.bolusIob, "U", 2) to "Bolus IOB"
            ProviderKind.BASAL_IOB -> units(therapyState?.insulin?.basalIob, "U", 2) to "Basal IOB"
            ProviderKind.IOB_COB -> "${units(therapyState?.insulin?.totalIob, "U", 1)} ${units(therapyState?.carbs?.cobGrams, "g", 0)}" to "IOB · COB"
            ProviderKind.TEMP_BASAL -> (therapyState?.basal?.displayText ?: therapyState?.basal?.tempPercent?.let { "$it%" } ?: units(therapyState?.basal?.tempAbsoluteUnitsPerHour, "U/h", 2)) to "Temp basal"
            ProviderKind.TEMP_TARGET -> target(therapyState?.target, glucose?.displayUnit ?: GlucoseUnit.MG_DL) to "Target"
            ProviderKind.LOOP_LAST -> timeAgo(therapyState?.loop?.lastRunAtEpochMs, now) to "Last loop"
            ProviderKind.PROFILE -> (therapyState?.profile?.name ?: DASH) to "Profile"
            ProviderKind.PUMP_BATTERY -> percent(therapyState?.pump?.batteryPercent) to "Pump battery"
            ProviderKind.PHONE_BATTERY -> percent(therapyState?.device?.phoneBatteryPercent) to "Phone battery"
            ProviderKind.SOURCE -> when (state?.source) {
                DataSourceId.DEXCOM_G7_WATCH -> "Dexcom G7 Watch"
                DataSourceId.ANDROID_APS -> "AndroidAPS"
                DataSourceId.NIGHTSCOUT -> "Nightscout"
                DataSourceId.XDRIP_PLUS -> "xDrip+"
                DataSourceId.OTHER -> "Other"
                null -> "No data"
            } to freshnessLabel(freshness)
            ProviderKind.AAPS_STATUS -> "$glucoseText$trendText" to compactTherapyStatus(therapyState)
            ProviderKind.LONG_STATUS -> longStatus(glucoseText, trendText, deltaText, ageText, therapyState, freshness) to "Sugarlicious"
            ProviderKind.GLUCOSE_RANGE -> glucoseText to displayRange(glucose, displayable)
            ProviderKind.GLUCOSE_RANGED,
            ProviderKind.GLUCOSE_IMAGE,
            ProviderKind.GRAPH,
            ProviderKind.GRAPH_LARGE -> glucoseText to "Glucose"
            ProviderKind.DATE -> DASH to "Date"
            else -> DASH to "Sugarlicious"
        }

        val description = PlainComplicationText.Builder(presentation?.contentDescription ?: pair.second).build()
        val tap = PendingIntent.getActivity(
            this,
            kind.ordinal,
            packageManager.getLaunchIntentForPackage(packageName) ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE,
        )

        if (kind == ProviderKind.LOOP && type == ComplicationType.MONOCHROMATIC_IMAGE) {
            val image = loopComplicationIcon(therapyState)
            return MonochromaticImageComplicationData.Builder(image, description)
                .setTapAction(tap)
                .build()
        }

        if (kind == ProviderKind.TIR && Build.VERSION.SDK_INT >= 33) {
            val stats = tirStats(state, now, thresholds)
            if (type == ComplicationType.GOAL_PROGRESS) {
                return GoalProgressComplicationData.Builder(
                    stats.inRangePercent,
                    TIR_GOAL_PERCENT,
                    description,
                ).setText(PlainComplicationText.Builder(stats.text).build())
                    .setTapAction(tap)
                    .build()
            }
            if (type == ComplicationType.WEIGHTED_ELEMENTS) {
                val graphColors = readGraphColors()
                val elements = buildList {
                    if (stats.lowPercent > 0f) add(WeightedElementsComplicationData.Element(stats.lowPercent, graphColors.cgmLow))
                    if (stats.inRangePercent > 0f) add(WeightedElementsComplicationData.Element(stats.inRangePercent, graphColors.cgmInRange))
                    if (stats.highPercent > 0f) add(WeightedElementsComplicationData.Element(stats.highPercent, graphColors.cgmHigh))
                    if (isEmpty()) add(WeightedElementsComplicationData.Element(1f, graphColors.axisLabel))
                }
                return WeightedElementsComplicationData.Builder(elements, description)
                    .setText(PlainComplicationText.Builder(stats.text).build())
                    .setTapAction(tap)
                    .build()
            }
        }

        if (
            kind == ProviderKind.GLUCOSE_IMAGE ||
            kind == ProviderKind.GRAPH ||
            kind == ProviderKind.GRAPH_LARGE
        ) {
            val icon = Icon.createWithBitmap(
                renderImage(
                    state = therapyState,
                    kind = kind,
                    now = now,
                ),
            )
            return if (type == ComplicationType.PHOTO_IMAGE) {
                PhotoImageComplicationData.Builder(icon, description)
                    .setTapAction(tap)
                    .build()
            } else {
                SmallImageComplicationData.Builder(
                    SmallImage.Builder(icon, SmallImageType.PHOTO).build(),
                    description,
                )
                    .setTapAction(tap)
                    .build()
            }
        }

        if (type == ComplicationType.RANGED_VALUE) {
            when (kind) {
                ProviderKind.GLUCOSE,
                ProviderKind.GLUCOSE_TREND,
                ProviderKind.GLUCOSE_RANGED -> {
                    val value =
                        if (displayable && glucose != null) {
                            glucose.valueMgDl
                                .toFloat()
                                .coerceIn(
                                    GLUCOSE_GAUGE_MIN,
                                    GLUCOSE_GAUGE_MAX,
                                )
                        } else {
                            GLUCOSE_GAUGE_MIN
                        }

                    val trendValue =
                        trendText.ifBlank { "→" }

                    val exposesTrend =
                        kind == ProviderKind.GLUCOSE_TREND ||
                            kind == ProviderKind.GLUCOSE_RANGED

                    val rangedDescription =
                        PlainComplicationText.Builder(
                            if (exposesTrend) "$glucoseText $trendValue" else glucoseText,
                        ).build()

                    val builder = RangedValueComplicationData.Builder(
                        value,
                        GLUCOSE_GAUGE_MIN,
                        GLUCOSE_GAUGE_MAX,
                        rangedDescription,
                    )
                        .setText(
                            PlainComplicationText.Builder(
                                glucoseText,
                            ).build(),
                        )
                        .setTapAction(tap)

                    presentation?.title?.let { title ->
                        builder.setTitle(
                            PlainComplicationText.Builder(title).build(),
                        )
                    }
                    presentation?.trend?.let { trend ->
                        TrendComplicationIcon.monochromaticImage(this, trend, catalogId = appearanceCatalogId)?.let(builder::setMonochromaticImage)
                    }
                    complicationIcon(kind, therapyState)?.let(builder::setMonochromaticImage)

                    return builder.build()
                }



                ProviderKind.IOB,
                ProviderKind.COB,
                ProviderKind.SENSOR_AGE -> {
                    val triple = when (kind) {
                        ProviderKind.IOB -> Triple(therapyState?.insulin?.totalIob?.toFloat()?.coerceIn(0f, IOB_GAUGE_MAX) ?: 0f, 0f, IOB_GAUGE_MAX)
                        ProviderKind.COB -> Triple(therapyState?.carbs?.cobGrams?.toFloat()?.coerceIn(0f, COB_GAUGE_MAX) ?: 0f, 0f, COB_GAUGE_MAX)
                        else -> Triple(0f, 0f, SENSOR_AGE_GAUGE_MAX_DAYS)
                    }
                    val builder = RangedValueComplicationData.Builder(triple.first, triple.second, triple.third, description)
                        .setText(PlainComplicationText.Builder(pair.first).build())
                        .setTapAction(tap)
                    complicationIcon(kind, therapyState)?.let(builder::setMonochromaticImage)
                    return builder.build()
                }

                ProviderKind.RESERVOIR -> {
                    val value =
                        therapyState?.pump?.reservoirUnits
                            ?.toFloat()
                            ?.coerceIn(0f, 300f)
                            ?: 0f

                    return RangedValueComplicationData.Builder(
                        value,
                        0f,
                        300f,
                        description,
                    )
                        .setText(
                            PlainComplicationText.Builder(
                                pair.first,
                            ).build(),
                        )
                        .setTapAction(tap)
                        .build()
                }

                ProviderKind.PUMP_BATTERY -> {
                    val value =
                        therapyState?.pump?.batteryPercent
                            ?.toFloat()
                            ?.coerceIn(0f, 100f)
                            ?: 0f

                    return RangedValueComplicationData.Builder(
                        value,
                        0f,
                        100f,
                        description,
                    )
                        .setText(
                            PlainComplicationText.Builder(
                                pair.first,
                            ).build(),
                        )
                        .setTapAction(tap)
                        .build()
                }

                ProviderKind.PHONE_BATTERY -> {
                    val value =
                        therapyState?.device?.phoneBatteryPercent
                            ?.toFloat()
                            ?.coerceIn(0f, 100f)
                            ?: 0f

                    return RangedValueComplicationData.Builder(
                        value,
                        0f,
                        100f,
                        description,
                    )
                        .setText(
                            PlainComplicationText.Builder(
                                pair.first,
                            ).build(),
                        )
                        .setTapAction(tap)
                        .build()
                }

                else -> Unit
            }
        }

        if (
            kind == ProviderKind.LONG_STATUS ||
            type == ComplicationType.LONG_TEXT
        ) {
            val longText = presentation?.text ?: pair.first
            val longTitle = presentation?.title ?: pair.second.takeIf { presentation == null }
            val builder = LongTextComplicationData.Builder(
                PlainComplicationText.Builder(longText).build(),
                description,
            ).setTapAction(tap)
            longTitle?.let {
                builder.setTitle(PlainComplicationText.Builder(it).build())
            }
            presentation?.trend?.let { trend ->
                TrendComplicationIcon.monochromaticImage(this, trend, catalogId = appearanceCatalogId)?.let(builder::setMonochromaticImage)
            }
            complicationIcon(kind, therapyState)?.let(builder::setMonochromaticImage)
            return builder.build()
        }

        val shortText = presentation?.text ?: pair.first
        val shortBuilder = ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(
                if (kind == ProviderKind.IOB_COB_BASAL) shortText else shortText.take(16),
            ).build(),
            description,
        ).setTapAction(tap)
        (presentation?.title ?: pair.second.takeIf { presentation == null && (kind == ProviderKind.IOB_COB_BASAL || kind == ProviderKind.RESERVOIR) })?.let {
            shortBuilder.setTitle(PlainComplicationText.Builder(if (kind == ProviderKind.IOB_COB_BASAL) it else it.take(16)).build())
        }
        presentation?.trend?.let { trend ->
            TrendComplicationIcon.monochromaticImage(this, trend, catalogId = appearanceCatalogId)?.let(shortBuilder::setMonochromaticImage)
        }
        complicationIcon(kind, therapyState)?.let(shortBuilder::setMonochromaticImage)
        return shortBuilder.build()
    }
    @SuppressLint("UseKtx")
    private fun renderImage(
        state: TherapyDisplayState?,
        kind: ProviderKind,
        now: Long,
    ): Bitmap {
        val valueOnly = kind == ProviderKind.GLUCOSE_IMAGE
        val (width, height) = complicationImageSize(kind)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)

        val glucose = state?.glucose
        if (valueOnly) {
            drawValueImage(canvas, glucose, height, now)
            return bitmap
        }

        val windowMs =
            if (kind == ProviderKind.GRAPH_LARGE) GRAPH_LARGE_WINDOW_MS
            else readComplicationGraphHours() * 60L * 60_000L

        drawGraphImage(
            canvas = canvas,
            state = state,
            height = height,
            now = now,
            windowMs = windowMs,
        )
        return bitmap
    }

    private fun drawValueImage(
        canvas: Canvas,
        glucose: GlucoseState?,
        height: Int,
        now: Long,
    ) {
        val width = canvas.width
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = glucoseColor(glucose)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 88f
        }
        val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
            textSize = 28f
        }

        val value = glucose?.let { glucose(it) + arrow(it.trend) } ?: DASH
        canvas.drawText(value, width / 2f, height * 0.58f, valuePaint)

        val meta = glucose?.let {
            val delta = signed(it.deltaMgDl, it.displayUnit).ifBlank { DASH }
            "$delta · ${timeAgo(it.measuredAtEpochMs, now)}"
        } ?: "No data"
        canvas.drawText(meta, width / 2f, height * 0.82f, metaPaint)
    }

    private fun drawGraphImage(
        canvas: Canvas,
        state: TherapyDisplayState?,
        height: Int,
        now: Long,
        windowMs: Long,
    ) {
        val width = canvas.width
        val glucose = state?.glucose
        val colors = readGraphColors()
        val graphStyle = readGraphStyle()
        val thresholds = readCgmThresholds()
        val targetLow = thresholds.lowMgDl
        val targetHigh = thresholds.highMgDl
        val density = resources.displayMetrics.density
        val plotLeft = 0f
        val plotRight = width.toFloat()
        val plotTop = 0f
        val plotBottom = height - 1f
        val plotHeight = plotBottom - plotTop

        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.graphBackground
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 22f, 22f, backgroundPaint)

        fun yFor(valueMgDl: Double): Float =
            plotBottom - (GlucoseGraphScale.ratio(valueMgDl) * plotHeight).toFloat()

        val timeWindow = GraphTimeWindow.live(now, windowMs)
        val cutoff = timeWindow.startEpochMs
        val merged = linkedMapOf<Long, GlucoseSample>()
        state?.glucoseHistory.orEmpty().forEach { merged[it.measuredAtEpochMs] = it }
        glucose?.let {
            merged[it.measuredAtEpochMs] =
                GlucoseSample(
                    valueMgDl = it.valueMgDl,
                    measuredAtEpochMs = it.measuredAtEpochMs,
                    source = state.source,
                    sensorId = it.sensorId,
                    sessionId = it.sessionId,
                    sequenceNumber = it.sequenceNumber,
                    receivedAtEpochMs = it.receivedAtEpochMs,
                    quality = it.quality,
                )
        }
        val samples = merged.values.asSequence()
            .filter {
                it.measuredAtEpochMs in cutoff..timeWindow.endEpochMs &&
                    it.quality == CgmQuality.VALID &&
                    it.valueMgDl in 20.0..1000.0
            }
            .sortedBy { it.measuredAtEpochMs }
            .toList()
        val excursion = CgmGraphPolicy.rangeExcursion(samples, targetLow, targetHigh)

        val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        if (excursion == RangeExcursion.HIGH) {
            targetPaint.color = colors.rangeHigh
            canvas.drawRect(plotLeft, plotTop, plotRight, yFor(targetHigh), targetPaint)
        }
        targetPaint.color = colors.rangeInRange
        canvas.drawRect(plotLeft, yFor(targetHigh), plotRight, yFor(targetLow), targetPaint)
        if (excursion == RangeExcursion.LOW) {
            targetPaint.color = colors.rangeLow
            canvas.drawRect(plotLeft, yFor(targetLow), plotRight, plotBottom, targetPaint)
        }

        val targetLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.35f * density
            strokeCap = Paint.Cap.BUTT
        }
        targetLinePaint.color = colors.highLine
        canvas.drawLine(plotLeft, yFor(targetHigh), plotRight, yFor(targetHigh), targetLinePaint)
        targetLinePaint.color = colors.lowLine
        canvas.drawLine(plotLeft, yFor(targetLow), plotRight, yFor(targetLow), targetLinePaint)

        if (samples.isEmpty()) {
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colors.axisLabel
                textAlign = Paint.Align.CENTER
                textSize = 26f
            }
            canvas.drawText("No history", width / 2f, (plotTop + plotBottom) / 2f, emptyPaint)
            return
        }

        fun xFor(timestamp: Long): Float =
            timeWindow.plotX(timestamp, plotLeft, plotRight - plotLeft)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = colors.outline
        }

        samples.forEachIndexed { index, sample ->
            dotPaint.color = when {
                sample.valueMgDl < targetLow -> colors.cgmLow
                sample.valueMgDl > targetHigh -> colors.cgmHigh
                else -> colors.cgmInRange
            }
            val dotRadius = (
                graphStyle.cgmDotRadiusDp.coerceIn(1.5f, 6.0f) +
                    if (index == samples.lastIndex) 0.1f else 0f
                ) * density
            val x = xFor(sample.measuredAtEpochMs)
            val y = yFor(sample.valueMgDl)
            canvas.drawCircle(x, y, dotRadius, dotPaint)
            if (graphStyle.cgmDotOutlineEnabled) {
                val outlineWidth = graphStyle.cgmDotOutlineWidthDp.coerceIn(0.25f, 3.0f) * density
                outlinePaint.strokeWidth = outlineWidth
                canvas.drawCircle(x, y, dotRadius + outlineWidth / 2f, outlinePaint)
            }
        }

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.divider
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        }
        canvas.drawRoundRect(plotLeft, plotTop, plotRight, plotBottom, 22f, 22f, borderPaint)
    }

    private fun readComplicationGraphHours(): Int =
        getSharedPreferences("watch_display", Context.MODE_PRIVATE)
            .getInt("complication_graph_hours", 3)
            .takeIf { it in listOf(1, 2, 6, 12, 24) }
            ?: 3

    private fun readGraphColors(): WatchGraphColors {
        val defaults = WatchGraphColors()
        val preferences = getSharedPreferences("watch_display", Context.MODE_PRIVATE)
        return WatchGraphColors(
            graphBackground = preferences.getInt("graph_color_background", defaults.graphBackground),
            rangeLow = preferences.getInt("graph_color_range_low", defaults.rangeLow),
            rangeInRange = preferences.getInt("graph_color_range_in", defaults.rangeInRange),
            rangeHigh = preferences.getInt("graph_color_range_high", defaults.rangeHigh),
            cgmLow = preferences.getInt("graph_color_cgm_low", defaults.cgmLow),
            cgmInRange = preferences.getInt("graph_color_cgm_in", defaults.cgmInRange),
            cgmHigh = preferences.getInt("graph_color_cgm_high", defaults.cgmHigh),
            divider = preferences.getInt("graph_color_divider", defaults.divider),
            highLine = preferences.getInt("graph_color_high_line", defaults.highLine),
            lowLine = preferences.getInt("graph_color_low_line", defaults.lowLine),
            axisLabel = preferences.getInt("graph_color_axis_label", defaults.axisLabel),
            axisTick = preferences.getInt("graph_color_axis_tick", defaults.axisTick),
            nowLine = preferences.getInt("graph_color_now_line", defaults.nowLine),
            outline = preferences.getInt("graph_color_outline", defaults.outline),
        )
    }

    private fun readGraphStyle(): WatchGraphStyle {
        val defaults = WatchGraphStyle()
        val preferences =
            getSharedPreferences(
                "watch_display",
                Context.MODE_PRIVATE,
            )

        return WatchGraphStyle(
            cgmDotRadiusDp =
                preferences
                    .getFloat(
                        "cgm_dot_radius_dp",
                        defaults.cgmDotRadiusDp,
                    )
                    .coerceIn(1.5f, 6.0f),
            cgmDotOutlineEnabled =
                preferences.getBoolean(
                    "cgm_dot_outline_enabled",
                    defaults.cgmDotOutlineEnabled,
                ),
            cgmDotOutlineWidthDp =
                preferences
                    .getFloat(
                        "cgm_dot_outline_width_dp",
                        defaults.cgmDotOutlineWidthDp,
                    )
                    .coerceIn(0.25f, 3.0f),
        )
    }

    private fun readCgmThresholds(): CgmThresholds {
        val preferences = getSharedPreferences("watch_display", Context.MODE_PRIVATE)
        return CgmThresholds(
            veryHighMgDl = preferences.getFloat("threshold_very_high", 250f).toDouble(),
            highMgDl = preferences.getFloat("threshold_high", 180f).toDouble(),
            lowMgDl = preferences.getFloat("threshold_low", 70f).toDouble(),
            veryLowMgDl = preferences.getFloat("threshold_very_low", 50f).toDouble(),
        ).takeIf(CgmThresholds::isValid) ?: CgmThresholds.DEFAULT
    }

    private fun complicationIcon(
        kind: ProviderKind,
        state: TherapyDisplayState?,
    ): MonochromaticImage? {
        val resource = when (kind) {
            ProviderKind.GLUCOSE -> R.drawable.ic_complication_xdrip
            ProviderKind.BASAL -> basalIconResource(state?.basal)
            ProviderKind.IOB -> R.drawable.ic_complication_iob
            ProviderKind.COB -> R.drawable.ic_complication_carbs
            ProviderKind.LOOP -> return loopComplicationIcon(state)
            else -> return null
        }
        return MonochromaticImage.Builder(Icon.createWithResource(this, resource)).build()
    }

    private fun basalIconResource(basal: BasalState?): Int {
        val absolute = basal?.tempAbsoluteUnitsPerHour
        val base = basal?.currentUnitsPerHour
        val percent = basal?.tempPercent
        return when {
            absolute != null && base != null && absolute > base + BASAL_COMPARE_EPSILON ->
                R.drawable.ic_complication_basal_more
            absolute != null && base != null && absolute < base - BASAL_COMPARE_EPSILON ->
                R.drawable.ic_complication_basal_less
            percent != null && percent > 100 ->
                R.drawable.ic_complication_basal_more
            percent != null && percent < 100 ->
                R.drawable.ic_complication_basal_less
            else -> R.drawable.ic_complication_basal
        }
    }

    private fun loopComplicationIcon(state: TherapyDisplayState?): MonochromaticImage {
        val resource = when (loopPresentation(state).visualState) {
            LoopVisualState.CLOSED -> R.drawable.ic_complication_loop_closed
            LoopVisualState.SUSPENDED -> R.drawable.ic_complication_loop_suspended
            LoopVisualState.DEACTIVATED,
            LoopVisualState.UNKNOWN -> R.drawable.ic_complication_loop_open
            LoopVisualState.PUMP_SUSPENDED -> R.drawable.ic_complication_pump_suspended
        }
        return MonochromaticImage.Builder(Icon.createWithResource(this, resource)).build()
    }
    private fun glucoseColor(glucose: GlucoseState?): Int =
        when {
            glucose == null -> Color.GRAY
            glucose.valueMgDl in DISPLAY_LOW_MGDL..DISPLAY_HIGH_MGDL ->
                Color.WHITE

            else -> Color.rgb(255, 92, 105)
        }

    private data class TirStats(
        val lowPercent: Float,
        val inRangePercent: Float,
        val highPercent: Float,
        val hasData: Boolean,
    ) {
        val text: String get() = if (hasData) "${inRangePercent.toInt()}%" else DASH
    }

    private fun tirStats(
        state: TherapyDisplayState?,
        now: Long,
        thresholds: CgmThresholds,
    ): TirStats {
        val samples = state?.glucoseHistory.orEmpty().filter {
            it.measuredAtEpochMs in (now - TIR_WINDOW_MS)..(now + FUTURE_TOLERANCE_MS)
        }
        if (samples.isEmpty()) return TirStats(0f, 0f, 0f, false)
        val total = samples.size.toFloat()
        val low = samples.count { it.valueMgDl < thresholds.lowMgDl } * 100f / total
        val high = samples.count { it.valueMgDl > thresholds.highMgDl } * 100f / total
        return TirStats(low, (100f - low - high).coerceIn(0f, 100f), high, true)
    }

    private fun tirText(state: TherapyDisplayState?, now: Long): String =
        tirStats(state, now, readCgmThresholds()).text

    private fun compactTherapyStatus(
        state: TherapyDisplayState?,
    ): String =
        "${units(state?.insulin?.totalIob, "U", 1)} · " +
            units(state?.carbs?.cobGrams, "g", 0)

    private fun combinedTherapyText(state: TherapyDisplayState?): String =
        "${units(state?.basal?.currentUnitsPerHour, "U/h", 2)} · " +
            "${units(state?.insulin?.totalIob, "U", 1)} · " +
            units(state?.carbs?.cobGrams, "g", 0)

    private fun longStatus(
        glucoseText: String,
        trendText: String,
        deltaText: String,
        ageText: String,
        state: TherapyDisplayState?,
        freshness: Freshness,
    ): String =
        buildString {
            append(glucoseText)
            append(trendText)
            append(" · Δ ")
            append(deltaText.ifBlank { DASH })
            append(" · ")
            append(ageText)
            append(" · IOB ")
            append(units(state?.insulin?.totalIob, "U", 2))
            append(" · COB ")
            append(units(state?.carbs?.cobGrams, "g", 0))
            append(" · Basal ")
            append(units(state?.basal?.currentUnitsPerHour, "U/h", 2))
            append(" · ")
            append(loopLabel(state?.loop?.status))
            append(" · ")
            append(freshnessLabel(freshness))
        }

    private fun displayRange(
        glucose: GlucoseState?,
        displayable: Boolean,
    ): String =
        when {
            !displayable || glucose == null -> "no data"
            glucose.valueMgDl < DISPLAY_LOW_MGDL -> "low"
            glucose.valueMgDl > DISPLAY_HIGH_MGDL -> "high"
            else -> "in range"
        }

    private fun freshnessLabel(freshness: Freshness): String =
        when (freshness) {
            Freshness.CURRENT -> "live"
            Freshness.DELAYED -> "delayed"
            Freshness.STALE -> "stale"
            Freshness.ERROR -> "sensor error"
            Freshness.NO_DATA -> "no data"
        }

    private fun loopLabel(status: String?): String =
        when (status?.lowercase()) {
            "enacted" -> "Loop active"
            "suggested" -> "Loop suggested"
            null -> DASH
            else -> status
        }

    private fun glucose(g: GlucoseState) =
        TherapyDisplayFormatter.glucose(g)

    private fun signed(v: Double?, u: GlucoseUnit) =
        TherapyDisplayFormatter.signedDelta(v, u)

    private fun arrow(t: Trend) =
        TherapyDisplayFormatter.trendArrow(t)

    private fun units(v: Double?, suffix: String, digits: Int) =
        TherapyDisplayFormatter.units(v, suffix, digits)

    private fun percent(v: Int?) =
        TherapyDisplayFormatter.percent(v)

    private fun timeAgo(t: Long?, now: Long) =
        TherapyDisplayFormatter.ageMinutes(t, now)

    private fun target(t: TargetState?, u: GlucoseUnit) =
        TherapyDisplayFormatter.target(t, u)

    private fun preview(): TherapyDisplayState {
        val now = System.currentTimeMillis()
        val history = (0..36).map { index ->
            val minutesAgo = (36 - index) * 5L
            val wave = when {
                index < 10 -> 108.0 + index * 2.0
                index < 22 -> 128.0 - (index - 10) * 1.3
                else -> 112.0 + (index - 22) * 0.8
            }
            GlucoseSample(
                valueMgDl = wave,
                measuredAtEpochMs = now - minutesAgo * 60_000L,
            )
        }

        return TherapyDisplayState(
            receivedAtEpochMs = now,
            sourceVersion = "Lokale Quelle",
            glucose = GlucoseState(
                valueMgDl = 123.0,
                displayUnit = GlucoseUnit.MG_DL,
                trend = Trend.FORTY_FIVE_UP,
                measuredAtEpochMs = now - 2 * 60_000L,
                deltaMgDl = 5.0,
                averageDeltaMgDl = 3.0,
            ),
            glucoseHistory = history,
            insulin = InsulinState(1.2, 0.8, 0.4),
            carbs = CarbState(15.0, 0.0),
            basal = BasalState(
                currentUnitsPerHour = 0.8,
                tempPercent = 120,
                displayText = "120%",
            ),
            target = TargetState(80.0, 160.0),
            loop = LoopState("enacted", now),
            pump = PumpState("OK", 120.0, 80),
            device = DeviceState(85, 90),
            profile = ProfileState("Default"),
            capabilities = DataCapability.entries.toSet(),
        )
    }

    companion object {
        private const val DASH = "—"

        private const val DISPLAY_LOW_MGDL = 80.0
        private const val DISPLAY_HIGH_MGDL = 160.0

        private const val GLUCOSE_GAUGE_MIN = 40f
        private const val GLUCOSE_GAUGE_MAX = 260f
        private const val IOB_GAUGE_MAX = 10f
        private const val COB_GAUGE_MAX = 150f
        private const val SENSOR_AGE_GAUGE_MAX_DAYS = 14f
        private const val TIR_GOAL_PERCENT = 70f
        private const val TIR_WINDOW_MS = 24 * 60 * 60_000L

        private const val GRAPH_MIN_MGDL = 40.0
        private const val GRAPH_MAX_MGDL = 260.0
        private const val GRAPH_WINDOW_MS = 3 * 60 * 60_000L
        private const val GRAPH_LARGE_WINDOW_MS = 6 * 60 * 60_000L
        private const val FUTURE_TOLERANCE_MS = 5 * 60_000L
        private const val BASAL_COMPARE_EPSILON = 0.001
    }
}

class GlucoseComplication :
    TherapyComplicationService(ProviderKind.GLUCOSE, ComplicationType.SHORT_TEXT)
class GlucoseLongTextComplication :
    TherapyComplicationService(
        ProviderKind.GLUCOSE,
        ComplicationType.LONG_TEXT,
        SugarliciousComplicationIds.GLUCOSE_LONG,
    )
class GlucoseRangedValueComplication :
    TherapyComplicationService(
        ProviderKind.GLUCOSE,
        ComplicationType.RANGED_VALUE,
        SugarliciousComplicationIds.GLUCOSE_RANGED,
    )
class TrendOnlyComplication :
    TherapyComplicationService(ProviderKind.TREND_ONLY)
class DeltaOnlyComplication :
    TherapyComplicationService(ProviderKind.DELTA_ONLY)

class GlucosePlusDeltaComplication :
    TherapyComplicationService(ProviderKind.GLUCOSE_PLUS_DELTA, ComplicationType.SHORT_TEXT)
class GlucosePlusDeltaLongTextComplication :
    TherapyComplicationService(
        ProviderKind.GLUCOSE_PLUS_DELTA,
        ComplicationType.LONG_TEXT,
        SugarliciousComplicationIds.GLUCOSE_PLUS_DELTA_LONG,
    )
class GlucoseTrendDeltaAgeComplication :
    TherapyComplicationService(ProviderKind.GLUCOSE_TREND_DELTA_AGE, ComplicationType.SHORT_TEXT)
class GlucoseTrendDeltaAgeLongTextComplication :
    TherapyComplicationService(
        ProviderKind.GLUCOSE_TREND_DELTA_AGE,
        ComplicationType.LONG_TEXT,
        SugarliciousComplicationIds.GLUCOSE_TREND_DELTA_AGE_LONG,
    )
class GlucoseTrendAgeComplication :
    TherapyComplicationService(ProviderKind.GLUCOSE_TREND_AGE, ComplicationType.SHORT_TEXT)
class GlucoseTrendAgeLongTextComplication :
    TherapyComplicationService(
        ProviderKind.GLUCOSE_TREND_AGE,
        ComplicationType.LONG_TEXT,
        SugarliciousComplicationIds.GLUCOSE_TREND_AGE_LONG,
    )
class SensorAgeComplication :
    TherapyComplicationService(ProviderKind.SENSOR_AGE, ComplicationType.SHORT_TEXT)
class SensorAgeRangedValueComplication :
    TherapyComplicationService(
        ProviderKind.SENSOR_AGE,
        ComplicationType.RANGED_VALUE,
        SugarliciousComplicationIds.SENSOR_AGE_RANGED,
    )
class TirComplication :
    TherapyComplicationService(ProviderKind.TIR, ComplicationType.SHORT_TEXT)
class TirGoalProgressComplication :
    TherapyComplicationService(
        ProviderKind.TIR,
        ComplicationType.GOAL_PROGRESS,
        SugarliciousComplicationIds.TIR_GOAL,
    )
class TirWeightedElementsComplication :
    TherapyComplicationService(
        ProviderKind.TIR,
        ComplicationType.WEIGHTED_ELEMENTS,
        SugarliciousComplicationIds.TIR_WEIGHTED,
    )

class GlucoseTrendComplication :
    TherapyComplicationService(ProviderKind.GLUCOSE_TREND, ComplicationType.SHORT_TEXT)
class GlucoseTrendLongTextComplication :
    TherapyComplicationService(
        ProviderKind.GLUCOSE_TREND,
        ComplicationType.LONG_TEXT,
        SugarliciousComplicationIds.GLUCOSE_TREND_LONG,
    )
class GlucoseTrendRangedValueComplication :
    TherapyComplicationService(
        ProviderKind.GLUCOSE_TREND,
        ComplicationType.RANGED_VALUE,
        SugarliciousComplicationIds.GLUCOSE_TREND_RANGED,
    )
class GlucoseTrendTextComplication :
    TherapyComplicationService(ProviderKind.GLUCOSE_TREND, ComplicationType.SHORT_TEXT)

class GlucoseDeltaComplication :
    TherapyComplicationService(ProviderKind.GLUCOSE_DELTA)

class GlucoseTrendDeltaComplication :
    TherapyComplicationService(ProviderKind.GLUCOSE_TREND_DELTA)

class GlucoseAgeComplication :
    TherapyComplicationService(ProviderKind.GLUCOSE_AGE)

class GlucoseImageComplication :
    TherapyComplicationService(ProviderKind.GLUCOSE_IMAGE)

class GlucoseRangeComplication :
    TherapyComplicationService(ProviderKind.GLUCOSE_RANGE)

class GlucoseRangedComplication :
    TherapyComplicationService(ProviderKind.GLUCOSE_RANGED)

class GlucoseGraphComplication :
    TherapyComplicationService(ProviderKind.GRAPH, ComplicationType.SMALL_IMAGE)

class GlucoseGraphLargeComplication :
    TherapyComplicationService(
        ProviderKind.GRAPH_LARGE,
        ComplicationType.PHOTO_IMAGE,
        SugarliciousComplicationIds.GRAPH_LARGE,
    )

class IobComplication :
    TherapyComplicationService(ProviderKind.IOB, ComplicationType.SHORT_TEXT)
class IobRangedValueComplication :
    TherapyComplicationService(
        ProviderKind.IOB,
        ComplicationType.RANGED_VALUE,
        SugarliciousComplicationIds.IOB_RANGED,
    )

class BolusIobComplication :
    TherapyComplicationService(ProviderKind.BOLUS_IOB)

class BasalIobComplication :
    TherapyComplicationService(ProviderKind.BASAL_IOB)

class CobComplication :
    TherapyComplicationService(ProviderKind.COB, ComplicationType.SHORT_TEXT)
class CobRangedValueComplication :
    TherapyComplicationService(
        ProviderKind.COB,
        ComplicationType.RANGED_VALUE,
        SugarliciousComplicationIds.COB_RANGED,
    )

class IobCobComplication :
    TherapyComplicationService(ProviderKind.IOB_COB, ComplicationType.SHORT_TEXT)

class IobCobLongTextComplication :
    TherapyComplicationService(
        ProviderKind.IOB_COB,
        ComplicationType.LONG_TEXT,
        SugarliciousComplicationIds.IOB_COB_LONG,
    )

class IobCobBasalComplication :
    TherapyComplicationService(ProviderKind.IOB_COB_BASAL, ComplicationType.SHORT_TEXT)
class IobCobBasalLongTextComplication :
    TherapyComplicationService(
        ProviderKind.IOB_COB_BASAL,
        ComplicationType.LONG_TEXT,
        SugarliciousComplicationIds.IOB_COB_BASAL_LONG,
    )

class BasalComplication :
    TherapyComplicationService(ProviderKind.BASAL)

class TempBasalComplication :
    TherapyComplicationService(ProviderKind.TEMP_BASAL)

class TempTargetComplication :
    TherapyComplicationService(ProviderKind.TEMP_TARGET)

class LoopComplication :
    TherapyComplicationService(ProviderKind.LOOP, ComplicationType.SHORT_TEXT)
class LoopIconComplication :
    TherapyComplicationService(
        ProviderKind.LOOP,
        ComplicationType.MONOCHROMATIC_IMAGE,
        SugarliciousComplicationIds.LOOP_ICON,
    )

class LastLoopComplication :
    TherapyComplicationService(ProviderKind.LOOP_LAST)

class ProfileComplication :
    TherapyComplicationService(ProviderKind.PROFILE)

class ReservoirComplication :
    TherapyComplicationService(ProviderKind.RESERVOIR, ComplicationType.SHORT_TEXT)
class ReservoirRangedValueComplication :
    TherapyComplicationService(
        ProviderKind.RESERVOIR,
        ComplicationType.RANGED_VALUE,
        SugarliciousComplicationIds.RESERVOIR_RANGED,
    )

class PumpBatteryComplication :
    TherapyComplicationService(ProviderKind.PUMP_BATTERY)

class PhoneBatteryComplication :
    TherapyComplicationService(ProviderKind.PHONE_BATTERY)

class SourceComplication :
    TherapyComplicationService(ProviderKind.SOURCE)

class AapsStatusComplication :
    TherapyComplicationService(ProviderKind.AAPS_STATUS)

class LongStatusComplication :
    TherapyComplicationService(ProviderKind.LONG_STATUS)

class DateComplication :
    TherapyComplicationService(ProviderKind.DATE, ComplicationType.SHORT_TEXT)

object AllProviders {
    val classes = listOf(
        GlucoseComplication::class.java,
        GlucoseLongTextComplication::class.java,
        GlucoseRangedValueComplication::class.java,
        GlucoseTrendComplication::class.java,
        GlucoseTrendLongTextComplication::class.java,
        GlucoseTrendRangedValueComplication::class.java,
        GlucosePlusDeltaComplication::class.java,
        GlucosePlusDeltaLongTextComplication::class.java,
        GlucoseTrendAgeComplication::class.java,
        GlucoseTrendAgeLongTextComplication::class.java,
        GlucoseTrendDeltaComplication::class.java,
        GlucoseTrendDeltaAgeComplication::class.java,
        GlucoseTrendDeltaAgeLongTextComplication::class.java,
        GlucoseGraphComplication::class.java,
        GlucoseGraphLargeComplication::class.java,
        TrendOnlyComplication::class.java,
        DeltaOnlyComplication::class.java,
        GlucoseAgeComplication::class.java,
        GlucoseDeltaComplication::class.java,
        SensorAgeComplication::class.java,
        SensorAgeRangedValueComplication::class.java,
        BasalComplication::class.java,
        IobComplication::class.java,
        IobRangedValueComplication::class.java,
        CobComplication::class.java,
        CobRangedValueComplication::class.java,
        IobCobComplication::class.java,
        IobCobLongTextComplication::class.java,
        IobCobBasalComplication::class.java,
        IobCobBasalLongTextComplication::class.java,
        LoopComplication::class.java,
        LoopIconComplication::class.java,
        ReservoirComplication::class.java,
        ReservoirRangedValueComplication::class.java,
        TirComplication::class.java,
        TirGoalProgressComplication::class.java,
        TirWeightedElementsComplication::class.java,
        DateComplication::class.java,
        PumpBatteryComplication::class.java,
        PhoneBatteryComplication::class.java,
        AapsStatusComplication::class.java,
    )
}
