package app.aapswear.g7watch

import android.content.Intent
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.tiles.RequestBuilders
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingOrigin
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.model.DataSourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class G7GraphTileTest {
    private val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    private val now = 20_000_000L

    @Test fun `rounded square sizing is responsive and safe on round watches`() {
        listOf(192 to 192, 227 to 227, 240 to 240, 220 to 180).forEach { (width, height) ->
            val spec = g7SquareTileSpec(width, height)
            assertEquals(minOf(width, height) * 0.76f, spec.sideDp, 0.01f)
            assertTrue(spec.sideDp < minOf(width, height))
            assertTrue(spec.cornerRadiusDp > 0f)
            assertTrue(spec.cornerRadiusDp < spec.sideDp / 2f)
        }
    }

    @Test fun `graph tile uses measured time and keeps only the newest sensor session`() {
        val oldSession = reading("sensor-old", "session-old", 1, now - 40 * 60_000L, CgmReadingOrigin.LIVE)
        val backfill = reading("sensor-new", "session-new", 2, now - 20 * 60_000L, CgmReadingOrigin.BACKFILL)
        val live = reading("sensor-new", "session-new", 3, now - 2 * 60_000L, CgmReadingOrigin.LIVE)
        val input = g7SharedGraphInput(
            readings = listOf(oldSession, live, backfill),
            palette = G7AppearanceStore(context).load(),
            settings = G7DirectToWatchSettingsStore(context),
            graphHours = 3,
            nowEpochMs = now,
        )

        assertEquals(listOf(backfill.timestampEpochMs, live.timestampEpochMs), input.history.map { it.measuredAtEpochMs })
        assertTrue(input.timeWindow.plotX(backfill.timestampEpochMs, 0f, 100f) < input.timeWindow.plotX(live.timestampEpochMs, 0f, 100f))
        assertEquals(g7CollectorGraphWindow(now, 3), input.timeWindow)
    }

    @Test fun `live copy wins over duplicate backfill without inventing a point`() {
        val backfill = reading("sensor", "session", 7, now - 5 * 60_000L, CgmReadingOrigin.BACKFILL)
        val live = backfill.copy(id = "live", origin = CgmReadingOrigin.LIVE, receivedAtEpochMs = now)
        val normalized = normalizeG7LocalHistory(listOf(backfill, live))

        assertEquals(1, normalized.size)
        assertEquals(CgmReadingOrigin.LIVE, normalized.single().origin)
    }

    @Test fun `graph empty states are explicit and history remains visible while stale`() {
        assertEquals("Wird geladen", g7GraphEmptyLabel(G7StatusPillState.CONNECTED, false))
        assertEquals("Signalverlust", g7GraphEmptyLabel(G7StatusPillState.SIGNAL_LOSS, false))
        assertEquals("Sensorfehler", g7GraphEmptyLabel(G7StatusPillState.SENSOR_ERROR, false))
        assertEquals("Kein aktiver Sensor", g7GraphEmptyLabel(G7StatusPillState.NO_ACTIVE_SENSOR, false))
        assertEquals("", g7GraphEmptyLabel(G7StatusPillState.SIGNAL_LOSS, true))
    }

    @Test fun `both SugarWear tile providers are registered`() {
        val services = context.packageManager.queryIntentServices(
            Intent("androidx.wear.tiles.action.BIND_TILE_PROVIDER").setPackage(context.packageName),
            0,
        ).map { it.serviceInfo.name }.toSet()

        assertTrue(services.any { it.endsWith("G7CollectorTileService") })
        assertTrue(services.any { it.endsWith("G7GraphTileService") })
    }

    @Test fun `graph tile returns a square layout and an inline graph resource`() {
        val device = DeviceParameters.Builder()
            .setScreenWidthDp(192)
            .setScreenHeightDp(192)
            .setScreenDensity(2f)
            .build()
        val service = Robolectric.buildService(G7GraphTileService::class.java).create().get()
        val request = RequestBuilders.TileRequest.Builder().setDeviceConfiguration(device).build()
        val tile = service.onTileRequest(request).get()
        val resources = request.scope.collectResources()

        assertTrue(tile.resourcesVersion.startsWith("g7-graph-3-"))
        assertTrue(request.scope.hasResources())
        assertTrue(resources.idToImageMapping.getValue("sugarwear_graph").inlineResource!!.data.isNotEmpty())
        service.onDestroy()
    }

    private fun reading(
        sensor: String,
        session: String,
        sequence: Long,
        measuredAt: Long,
        origin: CgmReadingOrigin,
    ) = CgmReading(
        id = "$sensor-$sequence-$origin",
        source = DataSourceId.DEXCOM_G7_WATCH,
        sensorId = sensor,
        sessionId = session,
        glucoseMgDl = 120.0 + sequence,
        timestampEpochMs = measuredAt,
        receivedAtEpochMs = now,
        sequenceNumber = sequence,
        status = CgmReadingStatus.VALID,
        origin = origin,
    )
}
