package app.aapswear.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Arc
import androidx.wear.protolayout.LayoutElementBuilders.ArcLine
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.ColorFilter
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.Image
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders.Background
import androidx.wear.protolayout.ModifiersBuilders.Border
import androidx.wear.protolayout.ModifiersBuilders.Corner
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ModifiersBuilders.Padding
import androidx.wear.protolayout.ProtoLayoutScope
import androidx.wear.protolayout.ResourceBuilders.AndroidImageResourceByResId
import androidx.wear.protolayout.ResourceBuilders.ImageResource
import androidx.wear.protolayout.ResourceBuilders.InlineImageResource
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import app.aapswear.complications.G7LocalReadingResolver
import app.aapswear.model.CgmRangeClass
import app.aapswear.model.CgmThresholds
import app.aapswear.model.Freshness
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseTrendSizing
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.GraphTimeWindow
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.TherapyIndicatorIcon
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
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Locale

// Bump when visual resources/typography change so Wear OS cannot reuse an older cached tile tree.
private const val TILE_RESOURCES_VERSION = "sugarlicious-9-therapy-ring-parity"
private const val TILE_GRAPH_RESOURCE_ID = "live_cgm_graph"
private const val TILE_GRAPH_WIDTH_PX = 296
private const val TILE_GRAPH_HEIGHT_PX = 120

/** ProtoLayout's 700 weight is optically heavier than the same system face in a TextView. */
internal fun sugarliciousTileWeight(emphasized: Boolean): Int = if (emphasized) 500 else 400

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
    val fresh = TherapyDisplayFormatter.isGlucoseDisplayable(state, now) && glucose != null
    val displayable = TherapyDisplayFormatter.isGlucoseKnown(state) && glucose != null
    val statusColor =
        when (freshness) {
            Freshness.CURRENT -> colors.accent
            Freshness.DELAYED -> colors.glucoseHigh
            Freshness.STALE, Freshness.SIGNAL_LOSS, Freshness.ERROR, Freshness.NO_DATA -> colors.glucoseLow
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
        when (thresholds.classify(glucose.valueMgDl)) {
            CgmRangeClass.VERY_LOW -> colors.glucoseVeryLow
            CgmRangeClass.LOW -> colors.glucoseLow
            CgmRangeClass.IN_RANGE -> colors.glucoseInRange
            CgmRangeClass.HIGH -> colors.glucoseHigh
            CgmRangeClass.VERY_HIGH -> colors.glucoseVeryHigh
            null -> colors.glucoseInRange
        }
    val delta = TherapyDisplayFormatter.signedDelta(glucose.deltaMgDl, glucose.displayUnit).ifBlank { "—" }
    val unit = if (glucose.displayUnit == GlucoseUnit.MMOL_L) "mmol/L" else "mg/dL"
    val age = TherapyDisplayFormatter.ageMinutesValue(glucose.measuredAtEpochMs, now)?.let { "vor $it min" }.orEmpty()
    return WearGlucoseTilePresentation(
        value = TherapyDisplayFormatter.glucose(glucose),
        meta = "$delta  ·  $unit",
        footer = listOf(TherapyDisplayFormatter.sourceName(state.source), age).filter(String::isNotBlank).joinToString("  ·  "),
        status = TherapyDisplayFormatter.freshnessLabel(freshness),
        valueColor = valueColor,
        statusColor = statusColor,
        trend = glucose.trend.takeIf { fresh && it != Trend.UNKNOWN },
    )
}

internal data class WearTherapyTilePresentation(
    val iob: String,
    val cob: String,
    val basal: String,
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
        iob = state?.insulin?.totalIob?.let { String.format(Locale.US, "%.1f U", it) } ?: "—",
        cob = state?.carbs?.cobGrams?.let { String.format(Locale.US, "%.0f g", it) } ?: "—",
        basal = effectiveBasalPresentation(state, now)?.unitsPerHour?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
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
        val preferences = WearDisplayPreferences.read(this)
        val now = System.currentTimeMillis()
        val resourcesVersion =
            if (tileKind == WearTileKind.THERAPY) {
                "$TILE_RESOURCES_VERSION-therapy-${therapySelection.canonical.joinToString { it.name }}"
            } else if (content == WearTileContent.GRAPH) {
                "$TILE_RESOURCES_VERSION-graph-${now / 60_000L}-${state?.glucoseHistory.hashCode()}-${state?.glucose.hashCode()}-${preferences.hashCode()}-${colors.hashCode()}"
            } else {
                TILE_RESOURCES_VERSION
            }
        return Tile
            .Builder()
            .setResourcesVersion(resourcesVersion)
            .setFreshnessIntervalMillis(60_000L)
            .setTileTimeline(
                Timeline.fromLayoutElement(
                    if (tileKind == WearTileKind.THERAPY) {
                        therapyTileContent(requestParams.scope, state, colors, now, therapySelection)
                    } else {
                        when (content) {
                            WearTileContent.GLUCOSE -> glucoseTileContent(requestParams.scope, state, colors, now, preferences)
                            WearTileContent.GRAPH -> graphTileContent(requestParams.scope, state, colors, now, preferences)
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
): LayoutElementBuilders.LayoutElement {
    val presentation = wearGlucoseTilePresentation(state, colors, now, preferences.cgmThresholds)
    val primary =
        Row
            .Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(
                tileText(
                    presentation.value,
                    42f * GlucoseTrendSizing.scaleFactor(preferences.glucoseScalePercent),
                    presentation.valueColor,
                    bold = true,
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
            .addContent(tileText(presentation.status, 10f, presentation.statusColor, bold = true))
            .addContent(Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(card)
            .addContent(Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(
                Row
                    .Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(tileText(presentation.meta.replace("  ·  ", " "), 14f, colors.deltaUnit, bold = true))
                    .apply {
                        presentation.footer.substringAfterLast("vor ", "").takeIf(String::isNotBlank)?.let {
                            addContent(tileText(" · ${it.replace(" min", "m")}", 14f, colors.textSecondary, bold = true))
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
): LayoutElementBuilders.LayoutElement {
    val graphResource =
        ImageResource
            .Builder()
            .setInlineResource(
                InlineImageResource
                    .Builder()
                    .setData(
                        ByteArrayOutputStream().use { output ->
                            renderWearTileGraph(state, preferences, now).compress(Bitmap.CompressFormat.PNG, 100, output)
                            output.toByteArray()
                        },
                    ).build(),
            ).build()
    val column =
        Column
            .Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(tileText("CGM · ${preferences.graphHours}h", 12f, colors.accent, bold = true))
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
): Bitmap {
    val bitmap = Bitmap.createBitmap(TILE_GRAPH_WIDTH_PX, TILE_GRAPH_HEIGHT_PX, Bitmap.Config.ARGB_8888)
    val graphColors = preferences.graphColors
    SharedWearCgmGraphRenderer.render(
        canvas = Canvas(bitmap),
        widthPx = bitmap.width,
        heightPx = bitmap.height,
        density = 2f,
        scaledDensity = 2f,
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
                        border = graphColors.divider,
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
                        dotOutlineEnabled = preferences.graphStyle.cgmDotOutlineEnabled,
                        historicalDotOutlineEnabled = preferences.graphStyle.cgmHistoricalDotOutlineEnabled,
                        currentDotOutlineEnabled = preferences.graphStyle.cgmCurrentDotOutlineEnabled,
                        scaleLaneOpacityPercent = preferences.graphStyle.scaleLaneOpacityPercent,
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
                .setPadding(Padding.Builder().setAll(dp(18f)).build())
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

private fun tileText(
    value: String,
    size: Float,
    color: Int,
    bold: Boolean,
): Text =
    Text
        .Builder()
        .setText(value)
        .setMaxLines(1)
        .setFontStyle(
            FontStyle
                .Builder()
                .setSize(sp(size))
                .setColor(argb(color))
                .setPreferredFontFamilies("sans-serif")
                .setWeight(sugarliciousTileWeight(bold))
                .build(),
        ).build()

internal fun requestSugarliciousTileUpdates(context: Context) {
    requestSugarliciousTileUpdates(context, setOf(GlucoseTileService::class.java, TherapyTileService::class.java))
}

private fun therapyTileContent(
    scope: ProtoLayoutScope,
    state: TherapyDisplayState?,
    colors: WatchUiColors,
    now: Long,
    selection: TherapyTileSelection,
): LayoutElementBuilders.LayoutElement {
    val presentation = wearTherapyTilePresentation(state, now)
    val placements = therapyTilePlacements(selection)
    val compact = placements.size > 1

    fun card(metric: TherapyTileMetric): Box = therapyMetricCard(scope, metric, presentation, state, colors, compact, now)
    val group =
        when (placements.size) {
            1 -> card(placements.single().metric)
            2 ->
                Row
                    .Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(card(placements[0].metric))
                    .addContent(Spacer.Builder().setWidth(dp(6f)).build())
                    .addContent(card(placements[1].metric))
                    .build()
            else ->
                Column
                    .Builder()
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(
                        Row
                            .Builder()
                            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                            .addContent(card(TherapyTileMetric.IOB))
                            .addContent(Spacer.Builder().setWidth(dp(6f)).build())
                            .addContent(card(TherapyTileMetric.COB))
                            .build(),
                    ).addContent(Spacer.Builder().setHeight(dp(6f)).build())
                    .addContent(card(TherapyTileMetric.BASAL))
                    .build()
        }
    return tileRoot(colors.background, group)
}

private fun therapyMetricCard(
    scope: ProtoLayoutScope,
    metric: TherapyTileMetric,
    presentation: WearTherapyTilePresentation,
    state: TherapyDisplayState?,
    colors: WatchUiColors,
    compact: Boolean,
    nowEpochMs: Long,
): Box {
    val value =
        when (metric) {
            TherapyTileMetric.IOB -> presentation.iob
            TherapyTileMetric.COB -> presentation.cob
            TherapyTileMetric.BASAL -> presentation.basal
        }
    val progress = wearTherapyProgress(metric, state, nowEpochMs) ?: 0f
    val accent =
        when (metric) {
            TherapyTileMetric.IOB -> colors.iob
            TherapyTileMetric.COB -> colors.cob
            TherapyTileMetric.BASAL -> colors.basal
        }
    val ring = therapyRingLayoutSpec(if (compact) 2 else 1)
    val backgroundArc =
        ArcLine
            .Builder()
            .setLength(degrees(ring.sweepDegrees))
            .setThickness(dp(ring.strokeWidthDp))
            // ProtoLayout ArcLine requires an opaque color. Pre-composite the Mobile
            // accent at 30% over the Tile background to preserve the same appearance.
            .setColor(argb(opaqueOverlay(accent, colors.background, 0.30f)))
            .build()
    val progressArc =
        ArcLine
            .Builder()
            .setLength(degrees(ring.sweepDegrees * progress))
            .setThickness(dp(ring.strokeWidthDp))
            .setColor(argb(accent))
            .build()
    val backgroundRing =
        Arc
            .Builder()
            .setAnchorAngle(degrees(ring.protoLayoutStartDegrees))
            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
            .addContent(backgroundArc)
            .build()
    val progressRing =
        Arc
            .Builder()
            .setAnchorAngle(degrees(ring.protoLayoutStartDegrees))
            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
            .addContent(progressArc)
            .build()
    val column =
        Column
            .Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(tileText(metric.label.uppercase(Locale.GERMAN), if (compact) 10f else 13f, accent, bold = true))
            .addContent(Spacer.Builder().setHeight(dp(if (compact) 1f else 3f)).build())
            .addContent(tileText(value, if (compact) 18f else 30f, colors.textPrimary, bold = true))
            .build()
    val iconState = wearTherapyIcon(metric, state, nowEpochMs)
    val iconSize = if (metric == TherapyTileMetric.COB) 17f else 19f
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
            .addContent(icon)
            .build()
    return Box
        .Builder()
        .setWidth(dp(ring.diameterDp))
        .setHeight(dp(ring.diameterDp))
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .addContent(backgroundRing)
        .addContent(progressRing)
        .addContent(column)
        .addContent(iconOverlay)
        .build()
}

internal data class TherapyRingLayoutSpec(
    val diameterDp: Float,
    val geometry: TherapyRingGeometry = TherapyRingGeometry(),
) {
    val strokeWidthDp: Float get() = geometry.strokeWidthDp
    val protoLayoutStartDegrees: Float get() = geometry.protoLayoutStartDegrees
    val sweepDegrees: Float get() = geometry.sweepDegrees
}

internal fun therapyRingLayoutSpec(metricCount: Int): TherapyRingLayoutSpec =
    TherapyRingLayoutSpec(diameterDp = if (metricCount == 1) 112f else 70f)

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
        TherapyIndicatorIcon.COB -> app.aapswear.uishared.R.drawable.ic_carbs
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
