package app.aapswear.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.ColorFilter
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.Image
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.ModifiersBuilders.Background
import androidx.wear.protolayout.ModifiersBuilders.Border
import androidx.wear.protolayout.ModifiersBuilders.Corner
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ModifiersBuilders.Padding
import androidx.wear.protolayout.ProtoLayoutScope
import androidx.wear.protolayout.ResourceBuilders.AndroidImageResourceByResId
import androidx.wear.protolayout.ResourceBuilders.IMAGE_FORMAT_RGB_565
import androidx.wear.protolayout.ResourceBuilders.ImageResource
import androidx.wear.protolayout.ResourceBuilders.InlineImageResource
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import app.aapswear.complications.G7LocalReadingResolver
import app.aapswear.model.AapsDisplayField
import app.aapswear.model.CgmRangeClass
import app.aapswear.model.CgmThresholds
import app.aapswear.model.Freshness
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseTrendSizing
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.GraphTimeWindow
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.TherapyEventKind
import app.aapswear.model.TherapyIndicatorGeometry
import app.aapswear.model.TherapyIndicatorIcon
import app.aapswear.model.TherapyProgressAppearance
import app.aapswear.model.TherapyProgressSemantics
import app.aapswear.model.TherapyRingGeometry
import app.aapswear.model.Trend
import app.aapswear.model.TrendVisualAsset
import app.aapswear.model.TrendVisuals
import app.aapswear.model.basalIndicatorIcon
import app.aapswear.model.effectiveBasalPresentation
import app.aapswear.protocol.WatchUiColors
import app.aapswear.storage.TherapyStateStore
import app.aapswear.uishared.SharedWearCgmGraphInput
import app.aapswear.uishared.SharedWearCgmGraphPalette
import app.aapswear.uishared.SharedWearCgmGraphRenderer
import app.aapswear.uishared.SharedWearCgmGraphStyle
import app.aapswear.uishared.TrendDrawableResources
import app.aapswear.uishared.toRgb565Image
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

// Bump when visual resources/typography change so Wear OS cannot reuse an older cached tile tree.
private const val TILE_RESOURCES_VERSION = "sugarlicious-18-shared-therapy-proportions"
private const val TILE_GRAPH_RESOURCE_ID = "live_cgm_graph"
private const val TILE_GRAPH_WIDTH_PX = 296
private const val TILE_GRAPH_HEIGHT_PX = 120

/** Use the same real bold weight as native, bitmap and Compose rendering. */
internal fun sugarliciousTileWeight(emphasized: Boolean): Int =
    app.aapswear.model.SystemTypographyTokens
        .weight(emphasized)

internal data class WearGlucoseTilePresentation(
    val value: String,
    val meta: String,
    val footer: String,
    val status: String,
    val valueColor: Int,
    val statusColor: Int,
    val trend: Trend? = null,
)

internal fun wearGlucoseTilePresentation(
    state: TherapyDisplayState?,
    colors: WatchUiColors,
    now: Long,
    thresholds: CgmThresholds = CgmThresholds.DEFAULT,
): WearGlucoseTilePresentation {
    val freshness = TherapyDisplayFormatter.freshness(state, now)
    val glucose = state?.glucose
    val retainsLastState = TherapyDisplayFormatter.isGlucoseDisplayable(state, now) && glucose != null
    val displayable = TherapyDisplayFormatter.isGlucoseKnown(state) && glucose != null
    val statusColor =
        when (freshness) {
            Freshness.CURRENT -> colors.accent
            Freshness.DELAYED -> colors.glucoseHigh
            Freshness.SIGNAL_LOSS -> android.graphics.Color.WHITE
            Freshness.STALE, Freshness.ERROR, Freshness.NO_DATA -> colors.glucoseLow
        }
    if (!displayable) {
        val message =
            when (freshness) {
                Freshness.STALE -> "KEINE AKTUELLEN CGM-DATEN"
                Freshness.SIGNAL_LOSS -> "SIGNALVERLUST"
                Freshness.ERROR -> "G7 SENSORFEHLER"
                Freshness.NO_DATA -> "KEINE CGM-DATEN"
                else -> TherapyDisplayFormatter.freshnessLabel(freshness)
            }
        return WearGlucoseTilePresentation(
            value = "—",
            meta = message,
            footer = TherapyDisplayFormatter.sourceName(state?.source),
            status = TherapyDisplayFormatter.freshnessLabel(freshness),
            valueColor = colors.textPrimary,
            statusColor = statusColor,
        )
    }
    val valueColor =
        if (freshness == Freshness.SIGNAL_LOSS) {
            0xFF9E9E9E.toInt()
        } else {
            when (thresholds.classify(glucose.valueMgDl)) {
                CgmRangeClass.VERY_LOW -> colors.glucoseVeryLow
                CgmRangeClass.LOW -> colors.glucoseLow
                CgmRangeClass.IN_RANGE -> colors.glucoseInRange
                CgmRangeClass.HIGH -> colors.glucoseHigh
                CgmRangeClass.VERY_HIGH -> colors.glucoseVeryHigh
                null -> colors.glucoseInRange
            }
        }
    val delta = TherapyDisplayFormatter.signedDelta(glucose.deltaMgDl, glucose.displayUnit).ifBlank { "—" }
    val unit = if (glucose.displayUnit == GlucoseUnit.MMOL_L) "mmol/L" else "mg/dL"
    val age = TherapyDisplayFormatter.ageMinutes(glucose.measuredAtEpochMs, now).takeUnless { it == "—" }.orEmpty()
    return WearGlucoseTilePresentation(
        value = TherapyDisplayFormatter.glucose(glucose),
        meta = "$delta  ·  $unit",
        footer = listOf(TherapyDisplayFormatter.sourceName(state.source), age).filter(String::isNotBlank).joinToString("  ·  "),
        status = TherapyDisplayFormatter.freshnessLabel(freshness),
        valueColor = valueColor,
        statusColor = statusColor,
        trend = glucose.trend.takeIf { retainsLastState && it != Trend.UNKNOWN },
    )
}

internal data class WearTherapyTilePresentation(
    val iob: String,
    val cob: String,
    val basal: String,
    val iobSecondary: String? = null,
    val basalSecondary: String? = null,
    val status: String,
    val footer: String,
    val displayable: Boolean,
)

internal fun wearTherapyTilePresentation(
    state: TherapyDisplayState?,
    now: Long,
): WearTherapyTilePresentation {
    val freshness = TherapyDisplayFormatter.freshness(state, now)
    val displayable = state?.let { it.insulin != null || it.carbs != null || it.basal != null } == true
    return WearTherapyTilePresentation(
        iob = TherapyDisplayFormatter.aaps(AapsDisplayField.IOB, state?.insulin?.totalIob, state),
        cob = TherapyDisplayFormatter.aaps(AapsDisplayField.COB, state?.carbs?.cobGrams, state),
        basal = TherapyDisplayFormatter.aaps(AapsDisplayField.BASAL, effectiveBasalPresentation(state, now)?.unitsPerHour, state),
        iobSecondary = wearLatestBolusPresentation(state, now),
        basalSecondary = effectiveBasalPresentation(state, now)?.percent?.takeIf { it != 100 }?.let { "@$it%" },
        status = TherapyDisplayFormatter.freshnessLabel(freshness),
        footer =
            if (displayable) {
                buildList {
                    add(TherapyDisplayFormatter.sourceName(state.source))
                    state.loop
                        ?.status
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::add)
                    if (freshness != Freshness.CURRENT) add("letzter Stand")
                }.joinToString("  ·  ")
            } else {
                when (freshness) {
                    Freshness.STALE -> "THERAPIEDATEN AUSGEBLENDET"
                    Freshness.SIGNAL_LOSS -> "SIGNALVERLUST"
                    Freshness.ERROR -> "SENSORFEHLER"
                    Freshness.NO_DATA -> "KEINE THERAPIEDATEN"
                    else -> TherapyDisplayFormatter.freshnessLabel(freshness)
                }
            },
        displayable = displayable,
    )
}

private fun wearLatestBolusPresentation(
    state: TherapyDisplayState?,
    now: Long,
): String? {
    val kinds = setOf(TherapyEventKind.MEAL_BOLUS, TherapyEventKind.MANUAL_CORRECTION, TherapyEventKind.SMB)
    val event =
        state
            ?.therapyEvents
            .orEmpty()
            .asSequence()
            .filter { it.kind in kinds && it.timestampEpochMs <= now && it.amount.isFinite() && it.amount > 0.0 }
            .maxWithOrNull(compareBy({ it.timestampEpochMs }, { it.id }))
            ?: return null
    val amount = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.GERMANY)).format(event.amount)
    val minutes = (now - event.timestampEpochMs).coerceAtLeast(0L) / 60_000L
    return "${amount}U·${if (minutes < 60L) "${minutes}m" else "${minutes / 60L}h"}"
}

internal data class WearTileGraphPoint(
    val sample: GlucoseSample,
    val xDp: Float,
)

internal fun wearTileGraphPoints(
    state: TherapyDisplayState?,
    now: Long,
    graphHours: Int,
    plotWidthDp: Float,
): List<WearTileGraphPoint> {
    val window = GraphTimeWindow.live(now, graphHours * 60L * 60_000L)
    val samples =
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
        }
    return samples
        .asSequence()
        .filter { it.quality == app.aapswear.model.CgmQuality.VALID }
        .filter { it.measuredAtEpochMs in window.startEpochMs..window.endEpochMs }
        .distinctBy { listOf(it.sensorId, it.sessionId, it.sequenceNumber, it.measuredAtEpochMs, it.source) }
        .sortedBy(GlucoseSample::measuredAtEpochMs)
        .map { WearTileGraphPoint(it, window.plotX(it.measuredAtEpochMs, 0f, plotWidthDp)) }
        .toList()
}

abstract class SugarliciousTileService : TileService() {
    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tileTypography = SugarliciousTileTypography(this)

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        tileTypography.clear()
    }

    protected abstract val tileKind: WearTileKind

    public override fun onTileRequest(requestParams: RequestBuilders.TileRequest): com.google.common.util.concurrent.ListenableFuture<Tile> {
        val future = SettableFuture.create<Tile>()
        tileScope.launch {
            runCatching { buildTile(requestParams) }
                .onSuccess(future::set)
                .onFailure(future::setException)
        }
        return future
    }

    private suspend fun buildTile(requestParams: RequestBuilders.TileRequest): Tile {
        val phoneState = TherapyStateStore(this@SugarliciousTileService).state.first()
        val state = G7LocalReadingResolver.resolve(this@SugarliciousTileService, phoneState)
        val colors = WearTileAppearanceStore.read(this, tileKind)
        val content = WearTileContentStore.read(this, tileKind)
        val therapySelection = TherapyTileSelectionStore.read(this)
        val therapyAppearance = TherapyTileGeometryStore.read(this)
        val preferences = WearDisplayPreferences.read(this)
        val now = System.currentTimeMillis()
        val resourcesVersion =
            if (tileKind == WearTileKind.THERAPY) {
                "$TILE_RESOURCES_VERSION-therapy-${therapySelection.canonical.joinToString { it.name }}-${therapyAppearance.hashCode()}"
            } else if (content == WearTileContent.GRAPH) {
                "$TILE_RESOURCES_VERSION-graph-${now / 60_000L}-${state?.glucoseHistory.hashCode()}-${state?.glucose.hashCode()}-${preferences.hashCode()}-${colors.hashCode()}"
            } else {
                TILE_RESOURCES_VERSION
            }
        return Tile
            .Builder()
            .setResourcesVersion("$resourcesVersion-${listOf(state.hashCode(), colors.hashCode(), preferences.hashCode(), resources.configuration.fontScale, resources.configuration.densityDpi, now / 60_000L).hashCode()}")
            .setFreshnessIntervalMillis(60_000L)
            .setTileTimeline(
                Timeline.fromLayoutElement(
                    if (tileKind == WearTileKind.THERAPY) {
                        therapyTileContent(
                            requestParams.scope,
                            state,
                            colors,
                            now,
                            therapySelection,
                            therapyAppearance,
                            minOf(requestParams.deviceConfiguration.screenWidthDp, requestParams.deviceConfiguration.screenHeightDp).toFloat(),
                            tileTypography,
                        )
                    } else {
                        when (content) {
                            WearTileContent.GLUCOSE -> glucoseTileContent(requestParams.scope, state, colors, now, preferences, tileTypography)
                            WearTileContent.GRAPH -> graphTileContent(requestParams.scope, state, colors, now, preferences, resources.configuration.fontScale, tileTypography)
                        }
                    },
                ),
            ).build()
    }

    override fun onDestroy() {
        tileScope.cancel()
        super.onDestroy()
    }
}

class GlucoseTileService : SugarliciousTileService() {
    override val tileKind: WearTileKind = WearTileKind.GLUCOSE
}

private fun glucoseTileContent(
    scope: ProtoLayoutScope,
    state: TherapyDisplayState?,
    colors: WatchUiColors,
    now: Long,
    preferences: WearDisplayPreferences,
    textRenderer: SugarliciousTileTypography,
): LayoutElementBuilders.LayoutElement {
    val presentation = wearGlucoseTilePresentation(state, colors, now, preferences.cgmThresholds)
    val trendSpace = presentation.trend?.let(TrendVisuals::spec)?.let { 7f + GlucoseTrendSizing.arrowHeightForGlucoseHeight(42f) * preferences.trendArrowStyle.renderSpec().scale * it.aspectRatio } ?: 0f
    val primary =
        Row
            .Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(
                textRenderer.text(
                    scope,
                    presentation.value,
                    42f * GlucoseTrendSizing.scaleFactor(preferences.glucoseScalePercent),
                    presentation.valueColor,
                    bold = true,
                    maxWidthDp = (textRenderer.contentWidthDp - 28f - trendSpace).coerceAtLeast(24f),
                ),
            ).apply {
                val spec = presentation.trend?.let(TrendVisuals::spec)
                if (spec != null) {
                    addContent(Spacer.Builder().setWidth(dp(7f)).build())
                    addContent(tileTrendImage(scope, spec, preferences.trendArrowStyle.renderSpec()))
                }
            }.build()
    val card = roundedTileCard(colors, 14f, primary)
    val column =
        Column
            .Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(textRenderer.text(scope, presentation.status, 10f, presentation.statusColor, bold = true))
            .addContent(Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(card)
            .addContent(Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(
                Row
                    .Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(textRenderer.text(scope, presentation.meta.replace("  ·  ", " "), 14f, colors.deltaUnit, bold = true, maxWidthDp = textRenderer.contentWidthDp * .65f))
                    .apply {
                        Regex("\\b\\d+m\\b").find(presentation.footer)?.value?.let {
                            addContent(textRenderer.text(scope, " · $it", 14f, colors.textSecondary, bold = true, maxWidthDp = textRenderer.contentWidthDp * .35f))
                        }
                    }.build(),
            ).addContent(Spacer.Builder().setHeight(dp(4f)).build())
            .build()
    return tileRoot(colors.background, column)
}

class TherapyTileService : SugarliciousTileService() {
    override val tileKind: WearTileKind = WearTileKind.THERAPY
}

private fun graphTileContent(
    scope: ProtoLayoutScope,
    state: TherapyDisplayState?,
    colors: WatchUiColors,
    now: Long,
    preferences: WearDisplayPreferences,
    fontScale: Float,
    textRenderer: SugarliciousTileTypography,
): LayoutElementBuilders.LayoutElement {
    val bitmap = renderWearTileGraph(state, preferences, now, fontScale, textRenderer.typeface(false), textRenderer.typeface(true))
    val graphPixels =
        try {
            bitmap.toRgb565Image()
        } finally {
            bitmap.recycle()
        }
    val graphResource =
        ImageResource
            .Builder()
            .setInlineResource(
                InlineImageResource
                    .Builder()
                    .setData(graphPixels.data)
                    .setWidthPx(graphPixels.widthPx)
                    .setHeightPx(graphPixels.heightPx)
                    .setFormat(IMAGE_FORMAT_RGB_565)
                    .build(),
            ).build()
    val column =
        Column
            .Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(textRenderer.text(scope, "CGM · ${preferences.graphHours}h", 12f, colors.accent, bold = true))
            .addContent(Spacer.Builder().setHeight(dp(5f)).build())
            .addContent(
                Image
                    .Builder(scope)
                    .setImageResource(graphResource, TILE_GRAPH_RESOURCE_ID)
                    .setWidth(dp(148f))
                    .setHeight(dp(60f))
                    .build(),
            ).build()
    return tileRoot(colors.background, roundedTileCard(colors, 18f, column))
}

internal fun renderWearTileGraph(
    state: TherapyDisplayState?,
    preferences: WearDisplayPreferences,
    now: Long,
    fontScale: Float = 1f,
    regularTypeface: android.graphics.Typeface? = null,
    boldTypeface: android.graphics.Typeface? = null,
): Bitmap {
    val bitmap = Bitmap.createBitmap(TILE_GRAPH_WIDTH_PX, TILE_GRAPH_HEIGHT_PX, Bitmap.Config.ARGB_8888)
    val graphColors = preferences.graphColors
    SharedWearCgmGraphRenderer.render(
        canvas = Canvas(bitmap),
        widthPx = bitmap.width,
        heightPx = bitmap.height,
        density = 2f,
        scaledDensity = 2f * fontScale,
        input =
            SharedWearCgmGraphInput(
                history = wearTileGraphPoints(state, now, preferences.graphHours, bitmap.width.toFloat()).map(WearTileGraphPoint::sample),
                timeWindow = GraphTimeWindow.live(now, preferences.graphHours * 60L * 60_000L),
                nowEpochMs = now,
                thresholds = preferences.cgmThresholds,
                palette =
                    SharedWearCgmGraphPalette(
                        background = graphColors.graphBackground,
                        targetArea = graphColors.rangeInRange,
                        highArea = graphColors.rangeHigh,
                        lowArea = graphColors.rangeLow,
                        highLine = graphColors.highLine,
                        lowLine = graphColors.lowLine,
                        dotHigh = graphColors.cgmHigh,
                        dotInRange = graphColors.cgmInRange,
                        dotLow = graphColors.cgmLow,
                        dotVeryHigh = graphColors.cgmVeryHigh,
                        dotVeryLow = graphColors.cgmVeryLow,
                        dotOutline = graphColors.outline,
                        axisText = graphColors.axisLabel,
                        axisTick = graphColors.axisTick,
                        nowLine = graphColors.nowLine,
                        border = graphColors.border,
                        predictionIob = graphColors.predictionIob,
                        predictionCob = graphColors.predictionCob,
                        predictionUam = graphColors.predictionUam,
                        predictionZeroTemp = graphColors.predictionZeroTemp,
                        targetText = graphColors.targetValue,
                        emptyText = graphColors.signalLoss,
                    ),
                style =
                    SharedWearCgmGraphStyle(
                        dotRadiusDp = preferences.graphStyle.cgmDotRadiusDp,
                        dotOutlineWidthDp = preferences.graphStyle.cgmDotOutlineWidthDp,
                        historicalDotOutlineWidthDp = preferences.graphStyle.cgmHistoricalDotOutlineWidthDp,
                        currentDotOutlineWidthDp = preferences.graphStyle.cgmCurrentDotOutlineWidthDp,
                        dotOutlineEnabled = preferences.graphStyle.cgmDotOutlineEnabled,
                        historicalDotOutlineEnabled = preferences.graphStyle.cgmHistoricalDotOutlineEnabled,
                        currentDotOutlineEnabled = true,
                        scaleLaneOpacityPercent = preferences.graphStyle.scaleLaneOpacityPercent,
                        regularTypeface = regularTypeface,
                        boldTypeface = boldTypeface,
                    ),
            ),
    )
    return bitmap
}

private fun roundedTileCard(
    colors: WatchUiColors,
    padding: Float,
    child: LayoutElementBuilders.LayoutElement,
): Box =
    Box
        .Builder()
        .setWidth(expand())
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .setModifiers(
            Modifiers
                .Builder()
                .setBackground(
                    Background
                        .Builder()
                        .setColor(argb(colors.tileBackground))
                        .setCorner(Corner.Builder().setRadius(dp(28f)).build())
                        .build(),
                ).setBorder(
                    Border
                        .Builder()
                        .setWidth(dp(1f))
                        .setColor(argb(colors.tileBorder))
                        .build(),
                ).setPadding(Padding.Builder().setAll(dp(padding)).build())
                .build(),
        ).addContent(child)
        .build()

private fun tileRoot(
    background: Int,
    child: LayoutElementBuilders.LayoutElement,
    paddingDp: Float = 18f,
): Box =
    Box
        .Builder()
        .setWidth(expand())
        .setHeight(expand())
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .setModifiers(
            Modifiers
                .Builder()
                .setBackground(Background.Builder().setColor(argb(background)).build())
                .setPadding(Padding.Builder().setAll(dp(paddingDp)).build())
                .build(),
        ).addContent(child)
        .build()

private fun tileTrendImage(
    scope: ProtoLayoutScope,
    spec: app.aapswear.model.TrendVisualSpec,
    style: app.aapswear.model.TrendArrowRenderSpec,
): Image =
    Image
        .Builder(scope)
        .setImageResource(
            ImageResource
                .Builder()
                .setAndroidResourceByResId(
                    AndroidImageResourceByResId
                        .Builder()
                        .setResourceId(TrendDrawableResources.forAsset(spec.asset))
                        .build(),
                ).build(),
            trendResourceId(spec.asset),
        ).setWidth(dp(GlucoseTrendSizing.arrowHeightForGlucoseHeight(42f) * style.scale * spec.aspectRatio))
        .setHeight(dp(GlucoseTrendSizing.arrowHeightForGlucoseHeight(42f) * style.scale))
        .setColorFilter(ColorFilter.Builder().setTint(argb(style.fillColor)).build())
        .build()

private fun trendResourceId(asset: TrendVisualAsset): String = "trend_${asset.name.lowercase()}"

internal fun requestSugarliciousTileUpdates(context: Context) {
    requestSugarliciousTileUpdates(context, setOf(GlucoseTileService::class.java, TherapyTileService::class.java))
}

private fun therapyTileContent(
    scope: ProtoLayoutScope,
    state: TherapyDisplayState?,
    colors: WatchUiColors,
    now: Long,
    selection: TherapyTileSelection,
    appearance: TherapyProgressAppearance,
    availableSideDp: Float,
    textRenderer: SugarliciousTileTypography,
): LayoutElementBuilders.LayoutElement {
    val presentation = wearTherapyTilePresentation(state, now)
    val effectiveAppearance = appearance.copy(enabledMetrics = selection.canonical.mapTo(linkedSetOf()) { it.toProgressMetric() })
    val side = (availableSideDp - 12f).coerceAtLeast(1f)
    val resolved = centeredTherapyTileLayout(side, effectiveAppearance)
    val group =
        Box
            .Builder()
            .setWidth(dp(side))
            .setHeight(dp(side))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_LEFT)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_TOP)
    resolved.forEach { item ->
        val card =
            therapyMetricCard(
                scope,
                item.metric.toTileMetric(),
                presentation,
                state,
                colors,
                now,
                TherapyRingLayoutSpec(item.slot.diameterDp, strokeWidthDp = item.strokeWidthDp),
                item.primaryTextSizeSp,
                item.secondaryTextSizeSp,
                item.iconSizeDp,
                textRenderer,
            )
        group.addContent(
            Box
                .Builder()
                .setWidth(dp(item.slot.rightDp))
                .setHeight(dp(item.slot.bottomDp))
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_RIGHT)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_BOTTOM)
                .addContent(card)
                .build(),
        )
    }
    return tileRoot(colors.background, group.build(), paddingDp = 6f)
}

private fun therapyMetricCard(
    scope: ProtoLayoutScope,
    metric: TherapyTileMetric,
    presentation: WearTherapyTilePresentation,
    state: TherapyDisplayState?,
    colors: WatchUiColors,
    nowEpochMs: Long,
    ring: TherapyRingLayoutSpec,
    primaryTextSizeSp: Float,
    secondaryTextSizeSp: Float,
    configuredIconSizeDp: Float,
    textRenderer: SugarliciousTileTypography,
): Box {
    val value = therapyMetricValue(metric, presentation)
    val progress = wearTherapyProgress(metric, state, nowEpochMs)
    val accent =
        when (metric) {
            TherapyTileMetric.IOB -> colors.iob
            TherapyTileMetric.COB -> colors.cob
            TherapyTileMetric.BASAL -> colors.basal
        }
    val bitmap = renderTherapyTileRing(ring, progress, accent, colors.background, metric == TherapyTileMetric.BASAL)
    val pixels =
        try {
            bitmap.toRgb565Image()
        } finally {
            bitmap.recycle()
        }
    val ringImage =
        Image
            .Builder(scope)
            .setImageResource(
                ImageResource
                    .Builder()
                    .setInlineResource(
                        InlineImageResource
                            .Builder()
                            .setData(pixels.data)
                            .setWidthPx(pixels.widthPx)
                            .setHeightPx(pixels.heightPx)
                            .setFormat(IMAGE_FORMAT_RGB_565)
                            .build(),
                    ).build(),
                "therapy_ring_${metric.name.lowercase()}",
            ).setWidth(dp(ring.diameterDp))
            .setHeight(dp(ring.diameterDp))
            .build()
    val fittedPrimarySp = textRenderer.fittedTextSize(value, TherapyIndicatorGeometry.fontSizeSp(primaryTextSizeSp, TherapyIndicatorGeometry.REFERENCE_DIAMETER_DP, textRenderer.fontScale), ring.diameterDp * TherapyIndicatorGeometry.VALUE_WIDTH_FRACTION, bold = true)
    val valueContent = textRenderer.text(scope, value, fittedPrimarySp, colors.textPrimary, bold = true, maxWidthDp = ring.diameterDp * TherapyIndicatorGeometry.VALUE_WIDTH_FRACTION, maxHeightDp = ring.diameterDp * .34f)
    val secondary =
        when (metric) {
            TherapyTileMetric.IOB -> presentation.iobSecondary
            TherapyTileMetric.COB -> null
            TherapyTileMetric.BASAL -> null
        }
    val textContent =
        Box
            .Builder()
            .setWidth(dp(ring.diameterDp))
            .setHeight(dp(ring.diameterDp))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(valueContent)
            .apply {
                secondary?.let {
                    addContent(
                        Box
                            .Builder()
                            .setWidth(dp(ring.diameterDp))
                            .setHeight(dp(ring.diameterDp))
                            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                            .setModifiers(Modifiers.Builder().setPadding(Padding.Builder().setTop(dp(ring.diameterDp * .34f)).build()).build())
                            .addContent(textRenderer.text(scope, it, secondaryTextSizeSp, colors.textSecondary, bold = false, maxWidthDp = ring.diameterDp * TherapyIndicatorGeometry.VALUE_WIDTH_FRACTION, maxHeightDp = ring.diameterDp * .12f))
                            .build(),
                    )
                }
            }.build()
    val iconState = wearTherapyIcon(metric, state, nowEpochMs)
    val iconSize = configuredIconSizeDp
    val icon =
        Image
            .Builder(scope)
            .setImageResource(
                ImageResource
                    .Builder()
                    .setAndroidResourceByResId(
                        AndroidImageResourceByResId.Builder().setResourceId(wearTherapyIconResource(iconState)).build(),
                    ).build(),
                "therapy_${iconState.name.lowercase()}",
            ).setWidth(dp(iconSize))
            .setHeight(dp(iconSize))
            .setColorFilter(ColorFilter.Builder().setTint(argb(accent)).build())
            .build()
    val iconOverlay =
        Box
            .Builder()
            .setWidth(dp(ring.diameterDp))
            .setHeight(dp(ring.diameterDp))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_BOTTOM)
            .setModifiers(Modifiers.Builder().setPadding(Padding.Builder().setBottom(dp((ring.diameterDp * (1f - TherapyIndicatorGeometry.ICON_CENTER_Y_FRACTION) - iconSize / 2f).coerceAtLeast(0f))).build()).build())
            .addContent(icon)
            .build()
    return Box
        .Builder()
        .setWidth(dp(ring.diameterDp))
        .setHeight(dp(ring.diameterDp))
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .addContent(ringImage)
        .addContent(textContent)
        .addContent(iconOverlay)
        .build()
}

internal fun renderTherapyTileRing(ring: TherapyRingLayoutSpec, progress: Float?, accent: Int, background: Int, showMarker: Boolean): Bitmap {
    val scale = 3f
    val side = (ring.diameterDp * scale).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(background)
    val stroke = ring.strokeWidthDp * scale
    val proportionalScale = TherapyIndicatorGeometry.scale(ring.diameterDp)
    val outline = TherapyIndicatorGeometry.MARKER_OUTLINE_DP * proportionalScale * scale
    val marker = showMarker && progress?.isFinite() == true
    val inset = stroke / 2f + if (marker) TherapyIndicatorGeometry.MARKER_INSET_DP * proportionalScale * scale else 0f
    val bounds = android.graphics.RectF(inset, inset, side - inset, side - inset)
    val sweep = ring.sweepDegrees
    val arcs = app.aapswear.model.progressArcSegments(progress, bounds.width() + stroke, stroke, sweep)
    val paint =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeWidth = stroke
            color = opaqueOverlay(accent, background, .30f)
        }
    if (marker) {
        paint.color = accent
        canvas.drawArc(bounds, ring.geometry.composeStartDegrees, sweep, false, paint)
    } else {
        if (arcs.trackSweep > 0f) canvas.drawArc(bounds, ring.geometry.composeStartDegrees + arcs.trackStartOffset, arcs.trackSweep, false, paint)
        paint.color = accent
        if (arcs.fillSweep > 0f) canvas.drawArc(bounds, ring.geometry.composeStartDegrees, arcs.fillSweep, false, paint)
    }
    if (marker) {
        val angle = Math.toRadians((ring.geometry.composeStartDegrees + arcs.fillSweep).toDouble())
        val x = bounds.centerX() + bounds.width() / 2f * kotlin.math.cos(angle).toFloat()
        val y = bounds.centerY() + bounds.height() / 2f * kotlin.math.sin(angle).toFloat()
        paint.style = android.graphics.Paint.Style.FILL
        canvas.drawCircle(x, y, stroke / 2f, paint)
        paint.apply {
            color = android.graphics.Color.BLACK
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = outline
        }
        canvas.drawCircle(x, y, stroke / 2f + outline / 2f, paint)
    }
    return bitmap
}

internal data class TherapyRingLayoutSpec(
    val diameterDp: Float,
    val geometry: TherapyRingGeometry = TherapyRingGeometry(),
    val strokeWidthDp: Float = geometry.strokeWidthDp,
) {
    val protoLayoutStartDegrees: Float get() = geometry.protoLayoutStartDegrees
    val sweepDegrees: Float get() = geometry.sweepDegrees
}

internal data class TherapyTileGroupGeometry(
    val ringDiameterDp: Float,
    val gapDp: Float,
    val widthDp: Float,
    val heightDp: Float,
    val originXDp: Float,
    val originYDp: Float,
)

internal fun therapyTileGroupGeometry(
    availableSideDp: Float,
    metricCount: Int,
): TherapyTileGroupGeometry {
    val count = metricCount.coerceIn(1, 3)
    val gap = 6f
    val ring =
        when (count) {
            1 -> minOf(112f, availableSideDp * 0.62f)
            else -> minOf(70f, ((availableSideDp * 0.80f) - gap) / 2f)
        }.coerceAtLeast(48f)
    val width = if (count == 1) ring else ring * 2f + gap
    val height = if (count == 3) ring * 2f + gap else ring
    return TherapyTileGroupGeometry(
        ringDiameterDp = ring,
        gapDp = gap,
        widthDp = width,
        heightDp = height,
        originXDp = (availableSideDp - width) / 2f,
        originYDp = (availableSideDp - height) / 2f,
    )
}

internal fun therapyMetricValue(
    metric: TherapyTileMetric,
    presentation: WearTherapyTilePresentation,
): String =
    when (metric) {
        TherapyTileMetric.IOB -> presentation.iob
        TherapyTileMetric.COB -> presentation.cob
        TherapyTileMetric.BASAL -> presentation.basal.removeSuffix("/h")
    }

internal fun wearTherapyProgress(
    metric: TherapyTileMetric,
    state: TherapyDisplayState?,
    nowEpochMs: Long,
): Float? =
    when (metric) {
        TherapyTileMetric.IOB -> TherapyProgressSemantics.scaled(state?.insulin?.totalIob, 10.0)
        TherapyTileMetric.COB -> TherapyProgressSemantics.scaled(state?.carbs?.cobGrams, 300.0)
        TherapyTileMetric.BASAL -> {
            effectiveBasalPresentation(state, nowEpochMs)?.percent?.let(TherapyProgressSemantics::basal)
        }
    }

internal fun wearTherapyIcon(
    metric: TherapyTileMetric,
    state: TherapyDisplayState?,
    nowEpochMs: Long,
): TherapyIndicatorIcon =
    when (metric) {
        TherapyTileMetric.IOB -> TherapyIndicatorIcon.IOB
        TherapyTileMetric.COB -> TherapyIndicatorIcon.COB
        TherapyTileMetric.BASAL -> basalIndicatorIcon(effectiveBasalPresentation(state, nowEpochMs)?.percent)
    }

private fun wearTherapyIconResource(icon: TherapyIndicatorIcon): Int =
    when (icon) {
        TherapyIndicatorIcon.IOB -> app.aapswear.uishared.R.drawable.ic_iob
        TherapyIndicatorIcon.COB -> app.aapswear.uishared.R.drawable.ic_cob
        TherapyIndicatorIcon.BASAL -> app.aapswear.uishared.R.drawable.ic_basal
        TherapyIndicatorIcon.BASAL_LESS -> app.aapswear.uishared.R.drawable.ic_basalless
        TherapyIndicatorIcon.BASAL_MORE -> app.aapswear.uishared.R.drawable.ic_basalmore
    }

internal fun opaqueOverlay(
    foreground: Int,
    background: Int,
    alpha: Float,
): Int {
    val amount = alpha.coerceIn(0f, 1f)

    fun channel(shift: Int): Int {
        val front = foreground ushr shift and 0xFF
        val back = background ushr shift and 0xFF
        return (back + (front - back) * amount).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

internal fun affectedSugarliciousTiles(
    old: TherapyDisplayState?,
    new: TherapyDisplayState,
): Set<Class<out TileService>> {
    if (old == null) return setOf(GlucoseTileService::class.java, TherapyTileService::class.java)
    val affected = linkedSetOf<Class<out TileService>>()
    if (
        old.glucose != new.glucose ||
        old.glucoseHistory != new.glucoseHistory ||
        old.glucosePredictions != new.glucosePredictions ||
        old.target != new.target ||
        old.source != new.source
    ) {
        affected += GlucoseTileService::class.java
    }
    if (
        old.insulin != new.insulin ||
        old.carbs != new.carbs ||
        old.basal != new.basal ||
        old.therapyHistory != new.therapyHistory
    ) {
        affected += TherapyTileService::class.java
    }
    return affected
}

internal fun requestSugarliciousTileUpdates(
    context: Context,
    services: Set<Class<out TileService>>,
) {
    val updater = TileService.getUpdater(context)
    services.forEach(updater::requestUpdate)
}
