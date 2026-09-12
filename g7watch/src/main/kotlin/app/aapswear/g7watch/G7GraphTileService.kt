package app.aapswear.g7watch

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.Image
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.ModifiersBuilders.Background
import androidx.wear.protolayout.ModifiersBuilders.Border
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ModifiersBuilders.Corner
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ModifiersBuilders.Padding
import androidx.wear.protolayout.ResourceBuilders.ImageResource
import androidx.wear.protolayout.ResourceBuilders.InlineImageResource
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import app.aapswear.g7.CgmReading
import app.aapswear.uishared.SharedWearCgmGraphRenderer
import com.google.common.util.concurrent.SettableFuture
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal data class G7GraphTileSnapshot(
    val readings: List<CgmReading>,
    val palette: G7AppearancePalette,
    val pillState: G7StatusPillState,
    val graphHours: Int,
    val nowEpochMs: Long,
) {
    val resourceVersion: String
        get() = "g7-graph-3-${readings.maxOfOrNull(CgmReading::timestampEpochMs) ?: 0L}-${nowEpochMs / G7_GRAPH_TILE_FRESHNESS_INTERVAL_MS}-${palette.hashCode()}-$graphHours-${pillState.name}"
}

internal fun g7GraphEmptyLabel(state: G7StatusPillState, hasHistory: Boolean): String = when {
    hasHistory -> ""
    state == G7StatusPillState.NO_ACTIVE_SENSOR -> "Kein aktiver Sensor"
    state == G7StatusPillState.SENSOR_ERROR -> "Sensorfehler"
    state == G7StatusPillState.SIGNAL_LOSS -> "Signalverlust"
    else -> "Wird geladen"
}

class G7GraphTileService : TileService() {
    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    public override fun onTileRequest(requestParams: RequestBuilders.TileRequest): SettableFuture<Tile> {
        val future = SettableFuture.create<Tile>()
        tileScope.launch {
            runCatching {
                val snapshot = snapshot()
                Tile.Builder()
                    .setResourcesVersion(snapshot.resourceVersion)
                    .setFreshnessIntervalMillis(G7_GRAPH_TILE_FRESHNESS_INTERVAL_MS)
                    .setTileTimeline(Timeline.fromLayoutElement(layout(requestParams)))
                    .build()
            }.onSuccess(future::set).onFailure(future::setException)
        }
        return future
    }

    public override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): SettableFuture<Resources> {
        val future = SettableFuture.create<Resources>()
        tileScope.launch {
            runCatching {
                val snapshot = snapshot()
                val device = requestParams.deviceConfiguration
                val square = g7SquareTileSpec(device.screenWidthDp, device.screenHeightDp)
                val density = device.screenDensity.takeIf { it > 0f } ?: resources.displayMetrics.density
                val graphWidthDp = square.sideDp - square.innerPaddingDp * 2f
                val graphHeightDp = square.sideDp - square.innerPaddingDp * 2f - TITLE_LANE_DP
                Resources.Builder()
                    .setVersion(snapshot.resourceVersion)
                    .addIdToImageMapping(
                        GRAPH_RESOURCE_ID,
                        ImageResource.Builder()
                            .setInlineResource(
                                InlineImageResource.Builder()
                                    .setData(renderGraph(snapshot, graphWidthDp, graphHeightDp, density))
                                    .build(),
                            )
                            .build(),
                    )
                    .build()
            }.onSuccess(future::set).onFailure(future::setException)
        }
        return future
    }

    override fun onDestroy() {
        tileScope.cancel()
        super.onDestroy()
    }

    private fun layout(requestParams: RequestBuilders.TileRequest): LayoutElementBuilders.LayoutElement {
        val device = requestParams.deviceConfiguration
        val square = g7SquareTileSpec(device.screenWidthDp, device.screenHeightDp)
        val palette = G7AppearanceStore(this).load()
        val state = G7SensorStateStore(this).read()
        val pillState = deriveG7StatusPillState(state, G7CredentialStore(this).read() != null)
        val titleColor = when (pillState) {
            G7StatusPillState.CONNECTED -> palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY)
            G7StatusPillState.SIGNAL_LOSS -> palette.argb(G7AppearanceRole.GLUCOSE_STALE)
            G7StatusPillState.SENSOR_ERROR -> palette.argb(G7AppearanceRole.GLUCOSE_ERROR)
            G7StatusPillState.NO_ACTIVE_SENSOR -> palette.argb(G7AppearanceRole.GLUCOSE_NO_SOURCE)
        }
        val graphWidth = square.sideDp - square.innerPaddingDp * 2f
        val graphHeight = square.sideDp - square.innerPaddingDp * 2f - TITLE_LANE_DP
        val card = Column.Builder()
            .setWidth(dp(square.sideDp))
            .setHeight(dp(square.sideDp))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_LEFT)
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(palette.argb(G7AppearanceRole.MENU_SURFACE)))
                            .setCorner(Corner.Builder().setRadius(dp(square.cornerRadiusDp)).build())
                            .build(),
                    )
                    .setBorder(Border.Builder().setColor(argb(palette.argb(G7AppearanceRole.MENU_BORDER))).setWidth(dp(1f)).build())
                    .setPadding(Padding.Builder().setAll(dp(square.innerPaddingDp)).build())
                    .build(),
            )
            .addContent(label("Gewebeglukose-Verlauf", 10f, titleColor))
            .addContent(Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(
                Image.Builder()
                    .setResourceId(GRAPH_RESOURCE_ID)
                    .setWidth(dp(graphWidth))
                    .setHeight(dp(graphHeight))
                    .build(),
            )
            .build()

        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(Background.Builder().setColor(argb(palette.argb(G7AppearanceRole.MENU_BACKGROUND))).build())
                    .setClickable(
                        Clickable.Builder()
                            .setId(OPEN_GRAPH_CLICK_ID)
                            .setOnClick(ActionBuilders.launchAction(ComponentName(this, G7WatchActivity::class.java)))
                            .build(),
                    )
                    .build(),
            )
            .addContent(card)
            .build()
    }

    private suspend fun snapshot(): G7GraphTileSnapshot {
        val now = System.currentTimeMillis()
        val settings = G7DirectToWatchSettingsStore(this)
        val hours = settings.graphHours()
        val readings = G7ReadingDatabase(this).let { database ->
            try {
                database.getRange(now - hours * 60L * 60_000L, now + 5L * 60_000L)
            } finally {
                database.close()
            }
        }
        val state = G7SensorStateStore(this).read()
        return G7GraphTileSnapshot(
            readings = readings,
            palette = G7AppearanceStore(this).load(),
            pillState = deriveG7StatusPillState(state, G7CredentialStore(this).read() != null, now),
            graphHours = hours,
            nowEpochMs = now,
        )
    }

    private fun renderGraph(snapshot: G7GraphTileSnapshot, widthDp: Float, heightDp: Float, density: Float): ByteArray {
        val widthPx = (widthDp * density).toInt().coerceAtLeast(1)
        val heightPx = (heightDp * density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val settings = G7DirectToWatchSettingsStore(this)
        SharedWearCgmGraphRenderer.render(
            Canvas(bitmap),
            widthPx,
            heightPx,
            density,
            density * resources.configuration.fontScale,
            g7SharedGraphInput(
                readings = snapshot.readings,
                palette = snapshot.palette,
                settings = settings,
                graphHours = snapshot.graphHours,
                nowEpochMs = snapshot.nowEpochMs,
                emptyLabel = g7GraphEmptyLabel(snapshot.pillState, normalizeG7LocalHistory(snapshot.readings).isNotEmpty()),
            ),
        )
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun label(value: String, sizeSp: Float, color: Int): Text = Text.Builder()
        .setText(value)
        .setMaxLines(1)
        .setFontStyle(
            FontStyle.Builder()
                .setSize(androidx.wear.protolayout.DimensionBuilders.sp(sizeSp))
                .setColor(argb(color))
                .setPreferredFontFamilies("sans-serif")
                .setWeight(sugarWearTileWeight(emphasized = true))
                .build(),
        )
        .build()

    companion object {
        private const val GRAPH_RESOURCE_ID = "sugarwear_graph"
        private const val OPEN_GRAPH_CLICK_ID = "open_sugarwear_graph"
        private const val TITLE_LANE_DP = 19f
    }
}

internal const val G7_GRAPH_TILE_FRESHNESS_INTERVAL_MS = 5L * 60_000L
