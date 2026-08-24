package app.aapswear.g7watch

import android.content.ComponentName
import android.content.Context
import androidx.wear.protolayout.ActionBuilders
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
import androidx.wear.protolayout.ModifiersBuilders.Border
import androidx.wear.protolayout.ModifiersBuilders.Clickable
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
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.model.ArgbContrast
import app.aapswear.model.Freshness
import app.aapswear.model.FreshnessPolicy
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.Trend
import app.aapswear.model.TrendVisuals
import com.google.common.util.concurrent.Futures
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

internal data class G7TilePresentation(
    val glucoseValue: String,
    val meta: String,
    val age: String,
    val cardBackground: Int,
    val cardForeground: Int,
    val trend: Trend? = null,
) {
    val value: String
        get() {
            val arrow = trend?.let(TherapyDisplayFormatter::trendArrow).orEmpty()
            return listOf(glucoseValue, arrow).filter(String::isNotBlank).joinToString(" ")
        }

    val tileValue: String
        get() = glucoseValue

    val tileMeta: String
        get() = listOf(meta, age).filter(String::isNotBlank).joinToString("  ·  ")

    // Compatibility properties keep the in-app glucose card on the same validated presentation
    // without duplicating source, freshness or range logic in G7WatchActivity.
    val background: Int
        get() = cardBackground

    val foreground: Int
        get() = cardForeground
}

internal data class G7TileStatusPresentation(
    val label: String,
    val color: Int,
)

internal fun g7TilePresentation(
    reading: CgmReading?,
    colors: app.aapswear.protocol.WatchGraphColors,
    nowEpochMs: Long,
): G7TilePresentation {
    if (reading == null) {
        return G7TilePresentation(
            glucoseValue = "—",
            meta = "Noch kein lokaler Wert",
            age = "",
            cardBackground = G7_TILE_CARD_BACKGROUND,
            cardForeground = G7_TILE_TEXT_PRIMARY,
        )
    }
    val ageMs = (nowEpochMs - reading.timestampEpochMs).coerceAtLeast(0L)
    val ageMinutes = ageMs / 60_000L
    val age = if (ageMinutes == 0L) "gerade" else "vor $ageMinutes min"
    if (reading.status == CgmReadingStatus.SENSOR_ERROR) {
        return G7TilePresentation(
            glucoseValue = "—",
            meta = "Sensorfehler",
            age = age,
            cardBackground = G7_TILE_CARD_BACKGROUND,
            cardForeground = G7_TILE_TEXT_PRIMARY,
        )
    }
    if (
        reading.status != CgmReadingStatus.VALID ||
        !reading.glucoseMgDl.isFinite() ||
        reading.glucoseMgDl !in 20.0..1_000.0
    ) {
        return G7TilePresentation(
            glucoseValue = "—",
            meta = "Ungültiger Sensorwert",
            age = "",
            cardBackground = G7_TILE_CARD_BACKGROUND,
            cardForeground = G7_TILE_TEXT_PRIMARY,
        )
    }
    val freshness = FreshnessPolicy.classify(reading.timestampEpochMs, nowEpochMs)
    if (freshness !in setOf(Freshness.CURRENT, Freshness.DELAYED)) {
        val label = if (freshness == Freshness.STALE) "Veraltet" else "Keine Daten"
        return G7TilePresentation(
            glucoseValue = "—",
            meta = label,
            age = age,
            cardBackground = G7_TILE_CARD_BACKGROUND,
            cardForeground = G7_TILE_TEXT_PRIMARY,
        )
    }

    val value = reading.glucoseMgDl
    val cardBackground = when {
        value < G7_TILE_TARGET_LOW_MG_DL -> colors.cgmLow
        value > G7_TILE_TARGET_HIGH_MG_DL -> colors.cgmHigh
        else -> G7_TILE_CARD_BACKGROUND
    }
    val delta = reading.deltaMgDl?.let { String.format(Locale.US, "%+.0f", it) } ?: "—"
    return G7TilePresentation(
        glucoseValue = value.toInt().toString(),
        meta = "Δ $delta",
        age = age,
        cardBackground = cardBackground,
        cardForeground = tileForegroundFor(cardBackground),
        trend = reading.trend.takeUnless { it == Trend.UNKNOWN },
    )
}

internal fun g7TileStatusPresentation(status: G7UserStatus): G7TileStatusPresentation {
    val color = when (status.level) {
        G7UserStatusLevel.OK, G7UserStatusLevel.WORKING -> G7_TILE_ACCENT
        G7UserStatusLevel.ATTENTION -> G7_TILE_WARNING
        G7UserStatusLevel.ERROR -> G7_TILE_ERROR
        G7UserStatusLevel.OFF -> G7_TILE_TEXT_SECONDARY
    }
    return G7TileStatusPresentation(status.title.uppercase(Locale.GERMANY), color)
}

internal fun tileForegroundFor(backgroundArgb: Int): Int =
    if (ArgbContrast.isLight(backgroundArgb, threshold = 0.50)) G7_TILE_TEXT_DARK else G7_TILE_TEXT_PRIMARY

class G7CollectorTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest) =
        Futures.immediateFuture(
            Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(60_000L)
                .setTileTimeline(Timeline.fromLayoutElement(layout()))
                .build(),
        )

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest) =
        Futures.immediateFuture(
            Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .addIdToImageMapping(
                    TREND_RESOURCE_ID,
                    ImageResource.Builder()
                        .setAndroidResourceByResId(
                            AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_trend_arrow)
                                .build(),
                        )
                        .build(),
                )
                .addIdToImageMapping(
                    HEADER_RESOURCE_ID,
                    ImageResource.Builder()
                        .setAndroidResourceByResId(
                            AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_g7_sensor)
                                .build(),
                        )
                        .build(),
                )
                .build(),
        )

    private fun layout(): LayoutElementBuilders.LayoutElement {
        val reading =
            runBlocking(Dispatchers.IO) {
                G7ReadingDatabase(this@G7CollectorTileService).let { database ->
                    try {
                        database.getLatest()
                    } finally {
                        database.close()
                    }
                }
            }
        val persistedState = G7SensorStateStore(this).read()
        val credentialsPresent = G7CredentialStore(this).read() != null
        val userStatus = deriveG7UserStatus(persistedState, credentialsPresent)
        val presentation = g7TilePresentation(reading, G7GraphColorStore(this).read(), System.currentTimeMillis())
        val statusPresentation = g7TileStatusPresentation(userStatus)

        val primaryRow =
            Row.Builder()
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(text(presentation.tileValue, 38f, presentation.cardForeground, bold = true))
                .apply {
                    val spec = presentation.trend?.let(TrendVisuals::spec)
                    if (spec != null) {
                        addContent(Spacer.Builder().setWidth(dp(8f)).build())
                        repeat(spec.arrowCount) { index ->
                            if (index > 0) addContent(Spacer.Builder().setWidth(dp(2f)).build())
                            addContent(trendImage(spec.rotationDegrees, presentation.cardForeground))
                        }
                    }
                }
                .build()

        val valueCard =
            Box.Builder()
                .setWidth(expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setModifiers(
                    Modifiers.Builder()
                        .setBackground(
                            Background.Builder()
                                .setColor(argb(presentation.cardBackground))
                                .setCorner(Corner.Builder().setRadius(dp(24f)).build())
                                .build(),
                        )
                        .setBorder(
                            Border.Builder()
                                .setColor(argb(G7_TILE_CARD_BORDER))
                                .setWidth(dp(1f))
                                .build(),
                        )
                        .setPadding(Padding.Builder().setAll(dp(12f)).build())
                        .build(),
                )
                .addContent(primaryRow)
                .build()

        val header =
            Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(
                    Image.Builder()
                        .setResourceId(HEADER_RESOURCE_ID)
                        .setWidth(dp(34f))
                        .setHeight(dp(34f))
                        .build(),
                )
                .addContent(text("Direct To Watch", 11f, G7_TILE_TEXT_SECONDARY, bold = true))
                .build()

        val content =
            Column.Builder()
                .setWidth(expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(header)
                .addContent(Spacer.Builder().setHeight(dp(5f)).build())
                .addContent(valueCard)
                .addContent(Spacer.Builder().setHeight(dp(6f)).build())
                .addContent(text(presentation.tileMeta, 14f, G7_TILE_TEXT_PRIMARY, bold = true))
                .addContent(Spacer.Builder().setHeight(dp(5f)).build())
                .addContent(statusPill(statusPresentation))
                .build()

        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_TOP)
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(Background.Builder().setColor(argb(G7_TILE_BACKGROUND)).build())
                    .setPadding(Padding.Builder().setAll(dp(12f)).build())
                    .setClickable(
                        Clickable.Builder()
                            .setId(OPEN_COLLECTOR_CLICK_ID)
                            .setOnClick(
                                ActionBuilders.launchAction(
                                    ComponentName(this, G7WatchActivity::class.java),
                                ),
                            )
                            .build(),
                    )
                    .build(),
            )
            .addContent(content)
            .build()
    }

    private fun statusPill(presentation: G7TileStatusPresentation): Box =
        Box.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(withTileAlpha(presentation.color, 36)))
                            .setCorner(Corner.Builder().setRadius(dp(18f)).build())
                            .build(),
                    )
                    .setBorder(
                        Border.Builder()
                            .setColor(argb(presentation.color))
                            .setWidth(dp(1f))
                            .build(),
                    )
                    .setPadding(
                        Padding.Builder()
                            .setStart(dp(12f))
                            .setEnd(dp(12f))
                            .setTop(dp(4f))
                            .setBottom(dp(4f))
                            .build(),
                    )
                    .build(),
            )
            .addContent(text("●  ${presentation.label}", 10f, presentation.color, bold = true))
            .build()

    private fun trendImage(rotationDegrees: Float, color: Int): Image =
        Image.Builder()
            .setResourceId(TREND_RESOURCE_ID)
            .setWidth(dp(27f))
            .setHeight(dp(25f))
            .setColorFilter(ColorFilter.Builder().setTint(argb(color)).build())
            .setModifiers(
                Modifiers.Builder()
                    .setTransformation(
                        Transformation.Builder()
                            .setRotation(degrees(rotationDegrees))
                            .build(),
                    )
                    .build(),
            )
            .build()

    private fun text(value: String, size: Float, color: Int, bold: Boolean): Text =
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

    companion object {
        private const val RESOURCES_VERSION = "g7-collector-4"
        private const val TREND_RESOURCE_ID = "ic_trend"
        private const val HEADER_RESOURCE_ID = "ic_g7_sensor"
        private const val OPEN_COLLECTOR_CLICK_ID = "open_g7_watch_collector"

        fun requestUpdate(context: Context) {
            TileService.getUpdater(context).requestUpdate(G7CollectorTileService::class.java)
        }
    }
}

internal fun withTileAlpha(color: Int, alpha: Int): Int =
    (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

internal const val G7_TILE_BACKGROUND = 0xFF181818.toInt()
internal const val G7_TILE_CARD_BACKGROUND = 0xFF242424.toInt()
internal const val G7_TILE_CARD_BORDER = 0xFF404040.toInt()
internal const val G7_TILE_TEXT_PRIMARY = 0xFFF5F5F5.toInt()
internal const val G7_TILE_TEXT_SECONDARY = 0xFFB5B5B5.toInt()
internal const val G7_TILE_TEXT_DARK = 0xFF181818.toInt()
internal const val G7_TILE_ACCENT = 0xFF6DE892.toInt()
internal const val G7_TILE_WARNING = 0xFFFFC107.toInt()
internal const val G7_TILE_ERROR = 0xFFFF5C69.toInt()
internal const val G7_TILE_TARGET_LOW_MG_DL = 80.0
internal const val G7_TILE_TARGET_HIGH_MG_DL = 160.0
