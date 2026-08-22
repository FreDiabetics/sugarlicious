package app.aapswear.wear

import android.content.Context
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.ColorFilter
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.Image
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders.Background
import androidx.wear.protolayout.ModifiersBuilders.Corner
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ModifiersBuilders.Padding
import androidx.wear.protolayout.ModifiersBuilders.Transformation
import androidx.wear.protolayout.ResourceBuilders.AndroidImageResourceByResId
import androidx.wear.protolayout.ResourceBuilders.ImageResource
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import app.aapswear.complications.G7LocalReadingResolver
import app.aapswear.model.Freshness
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
import app.aapswear.model.TrendVisuals
import app.aapswear.protocol.WatchUiColors
import app.aapswear.storage.TherapyStateStore
import com.google.common.util.concurrent.Futures
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private const val TILE_RESOURCES_VERSION = "sugarlicious-4"
private const val TREND_RESOURCE_ID = "ic_trend"

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
): WearGlucoseTilePresentation {
    val freshness = TherapyDisplayFormatter.freshness(state, now)
    val glucose = state?.glucose
    val displayable = TherapyDisplayFormatter.isGlucoseDisplayable(state, now) && glucose != null
    val statusColor = when (freshness) {
        Freshness.CURRENT -> colors.accent
        Freshness.DELAYED -> colors.glucoseHigh
        Freshness.STALE, Freshness.ERROR, Freshness.NO_DATA -> colors.glucoseLow
    }
    if (!displayable) {
        val message = when (freshness) {
            Freshness.STALE -> "KEINE AKTUELLEN CGM-DATEN"
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
    val low = state.target?.lowMgDl ?: 80.0
    val high = state.target?.highMgDl ?: 160.0
    val valueColor = when {
        glucose.valueMgDl < low -> colors.glucoseLow
        glucose.valueMgDl > high -> colors.glucoseHigh
        else -> colors.glucoseInRange
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
        trend = glucose.trend.takeUnless { it == Trend.UNKNOWN },
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

internal fun wearTherapyTilePresentation(state: TherapyDisplayState?, now: Long): WearTherapyTilePresentation {
    val freshness = TherapyDisplayFormatter.freshness(state, now)
    val displayable = TherapyDisplayFormatter.isGlucoseDisplayable(state, now)
    return WearTherapyTilePresentation(
        iob = state?.insulin?.totalIob?.takeIf { displayable }?.let { String.format(Locale.US, "%.1f U", it) } ?: "—",
        cob = state?.carbs?.cobGrams?.takeIf { displayable }?.let { String.format(Locale.US, "%.0f g", it) } ?: "—",
        basal = state?.basal?.currentUnitsPerHour?.takeIf { displayable }?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
        status = TherapyDisplayFormatter.freshnessLabel(freshness),
        footer = if (displayable) {
            listOf(TherapyDisplayFormatter.sourceName(state?.source), state?.loop?.status.orEmpty()).filter(String::isNotBlank).joinToString("  ·  ")
        } else {
            when (freshness) {
                Freshness.STALE -> "THERAPIEDATEN AUSGEBLENDET"
                Freshness.ERROR -> "SENSORFEHLER"
                Freshness.NO_DATA -> "KEINE THERAPIEDATEN"
                else -> TherapyDisplayFormatter.freshnessLabel(freshness)
            }
        },
        displayable = displayable,
    )
}

abstract class SugarliciousTileService : TileService() {
    protected abstract fun tileContent(state: TherapyDisplayState?, colors: WatchUiColors, now: Long): LayoutElementBuilders.LayoutElement

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): com.google.common.util.concurrent.ListenableFuture<Tile> {
        val state = runBlocking(Dispatchers.IO) {
            val phoneState = TherapyStateStore(this@SugarliciousTileService).state.first()
            G7LocalReadingResolver.resolve(this@SugarliciousTileService, phoneState)
        }
        val colors = WearDisplayPreferences.read(this).uiColors
        return Futures.immediateFuture(
            Tile.Builder()
                .setResourcesVersion(TILE_RESOURCES_VERSION)
                .setFreshnessIntervalMillis(60_000L)
                .setTileTimeline(Timeline.fromLayoutElement(tileContent(state, colors, System.currentTimeMillis())))
                .build(),
        )
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest) =
        Futures.immediateFuture(
            Resources.Builder()
                .setVersion(TILE_RESOURCES_VERSION)
                .addIdToImageMapping(
                    TREND_RESOURCE_ID,
                    ImageResource.Builder()
                        .setAndroidResourceByResId(
                            AndroidImageResourceByResId.Builder().setResourceId(R.drawable.ic_trend_arrow).build(),
                        )
                        .build(),
                )
                .build(),
        )
}

class GlucoseTileService : SugarliciousTileService() {
    override fun tileContent(state: TherapyDisplayState?, colors: WatchUiColors, now: Long): LayoutElementBuilders.LayoutElement {
        val presentation = wearGlucoseTilePresentation(state, colors, now)
        val primary = Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(tileText(presentation.value, 42f, presentation.valueColor, bold = true))
            .apply {
                val spec = presentation.trend?.let(TrendVisuals::spec)
                if (spec != null) {
                    addContent(Spacer.Builder().setWidth(dp(7f)).build())
                    repeat(spec.arrowCount) { index ->
                        if (index > 0) addContent(Spacer.Builder().setWidth(dp(2f)).build())
                        addContent(tileTrendImage(spec.rotationDegrees, presentation.valueColor))
                    }
                }
            }
            .build()
        val card = roundedTileCard(colors.tileBackground, 14f, primary)
        val column = Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(tileText("SUGARLICIOUS  ·  ${presentation.status}", 11f, presentation.statusColor, bold = true))
            .addContent(Spacer.Builder().setHeight(dp(7f)).build())
            .addContent(card)
            .addContent(Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(tileText(presentation.meta, 16f, colors.textPrimary, bold = true))
            .addContent(Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(tileText(presentation.footer, 11f, colors.textSecondary, bold = false))
            .build()
        return tileRoot(colors.background, column)
    }
}

class TherapyTileService : SugarliciousTileService() {
    override fun tileContent(state: TherapyDisplayState?, colors: WatchUiColors, now: Long): LayoutElementBuilders.LayoutElement {
        val presentation = wearTherapyTilePresentation(state, now)
        val metrics = Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(metricCard("IOB", presentation.iob, colors.iob, colors))
            .addContent(Spacer.Builder().setWidth(dp(4f)).build())
            .addContent(metricCard("COB", presentation.cob, colors.cob, colors))
            .addContent(Spacer.Builder().setWidth(dp(4f)).build())
            .addContent(metricCard("BASAL", presentation.basal, colors.basal, colors))
            .build()
        val statusColor = if (presentation.displayable) colors.accent else colors.glucoseLow
        val column = Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(tileText("SUGARLICIOUS  ·  THERAPIE", 11f, colors.accent, bold = true))
            .addContent(Spacer.Builder().setHeight(dp(9f)).build())
            .addContent(metrics)
            .addContent(Spacer.Builder().setHeight(dp(9f)).build())
            .addContent(tileText(presentation.status, 12f, statusColor, bold = true))
            .addContent(Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(tileText(presentation.footer, 10f, colors.textSecondary, bold = false))
            .build()
        return tileRoot(colors.background, column)
    }
}

private fun metricCard(label: String, value: String, accent: Int, colors: WatchUiColors): Box {
    val content = Column.Builder()
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .addContent(tileText(label, 9f, accent, bold = true))
        .addContent(tileText(value, 14f, colors.textPrimary, bold = true))
        .build()
    return Box.Builder()
        .setWidth(dp(54f))
        .setHeight(dp(55f))
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .setModifiers(
            Modifiers.Builder()
                .setBackground(
                    Background.Builder()
                        .setColor(argb(colors.tileBackground))
                        .setCorner(Corner.Builder().setRadius(dp(18f)).build())
                        .build(),
                )
                .build(),
        )
        .addContent(content)
        .build()
}

private fun roundedTileCard(background: Int, padding: Float, child: LayoutElementBuilders.LayoutElement): Box =
    Box.Builder()
        .setWidth(expand())
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .setModifiers(
            Modifiers.Builder()
                .setBackground(
                    Background.Builder()
                        .setColor(argb(background))
                        .setCorner(Corner.Builder().setRadius(dp(28f)).build())
                        .build(),
                )
                .setPadding(Padding.Builder().setAll(dp(padding)).build())
                .build(),
        )
        .addContent(child)
        .build()

private fun tileRoot(background: Int, child: LayoutElementBuilders.LayoutElement): Box =
    Box.Builder()
        .setWidth(expand())
        .setHeight(expand())
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .setModifiers(
            Modifiers.Builder()
                .setBackground(Background.Builder().setColor(argb(background)).build())
                .setPadding(Padding.Builder().setAll(dp(18f)).build())
                .build(),
        )
        .addContent(child)
        .build()

private fun tileTrendImage(rotationDegrees: Float, color: Int): Image =
    Image.Builder()
        .setResourceId(TREND_RESOURCE_ID)
        .setWidth(dp(28f))
        .setHeight(dp(26f))
        .setColorFilter(ColorFilter.Builder().setTint(argb(color)).build())
        .setModifiers(
            Modifiers.Builder()
                .setTransformation(Transformation.Builder().setRotation(degrees(rotationDegrees)).build())
                .build(),
        )
        .build()

private fun tileText(value: String, size: Float, color: Int, bold: Boolean): Text =
    Text.Builder()
        .setText(value)
        .setMaxLines(1)
        .setFontStyle(
            FontStyle.Builder()
                .setSize(sp(size))
                .setColor(argb(color))
                .apply { if (bold) setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD) }
                .build(),
        )
        .build()

internal fun requestSugarliciousTileUpdates(context: Context) {
    val updater = TileService.getUpdater(context)
    updater.requestUpdate(GlucoseTileService::class.java)
    updater.requestUpdate(TherapyTileService::class.java)
}
