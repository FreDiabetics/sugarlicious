package app.aapswear.g7watch

import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.model.DataSourceId
import app.aapswear.model.Trend
import app.aapswear.protocol.WatchGraphColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class G7CollectorTilePresentationTest {
    private val now = 2_000_000L
    private val colors = WatchGraphColors(
        graphBackground = 0xFF111111.toInt(),
        rangeLow = 0xFFAA0000.toInt(),
        rangeHigh = 0xFFCCAA00.toInt(),
        cgmLow = 0xFFFF0000.toInt(),
        cgmHigh = 0xFFFFCC00.toInt(),
    )

    @Test
    fun `no data stale invalid and sensor errors keep neutral glucose card`() {
        val noData = g7TilePresentation(null, colors, now)
        assertEquals(G7_TILE_CARD_BACKGROUND, noData.cardBackground)
        assertNull(noData.trend)

        val stale = g7TilePresentation(reading(120.0, now - G7_SIGNAL_LOSS_AFTER_MS), colors, now)
        assertEquals("—", stale.value)
        assertEquals(G7_TILE_CARD_BACKGROUND, stale.cardBackground)
        assertNull(stale.trend)

        val invalid = g7TilePresentation(reading(120.0, status = CgmReadingStatus.INVALID), colors, now)
        assertEquals("—", invalid.value)
        assertEquals(G7_TILE_CARD_BACKGROUND, invalid.cardBackground)

        val sensorError = g7TilePresentation(reading(0.0, status = CgmReadingStatus.SENSOR_ERROR), colors, now)
        assertEquals("—", sensorError.value)
        assertEquals(G7_TILE_CARD_BACKGROUND, sensorError.cardBackground)
    }

    @Test
    fun `only glucose card changes color outside target range`() {
        val low = g7TilePresentation(reading(79.0), colors, now)
        val normal = g7TilePresentation(reading(123.0), colors, now)
        val high = g7TilePresentation(reading(161.0), colors, now)

        assertEquals(colors.cgmLow, low.cardBackground)
        assertEquals(G7_TILE_CARD_BACKGROUND, normal.cardBackground)
        assertEquals(colors.cgmHigh, high.cardBackground)
        assertEquals(G7_TILE_BACKGROUND, 0xFF181818.toInt())
        assertEquals(G7_TILE_CARD_BORDER, 0xFF404040.toInt())
    }

    @Test
    fun `tile shows vector trend delta and age without glucose unit text`() {
        val presentation = g7TilePresentation(
            reading(123.0, now - 2 * 60_000L, delta = 5.0, trend = Trend.FORTY_FIVE_UP),
            colors,
            now,
        )

        assertEquals("123", presentation.tileValue)
        assertEquals(Trend.FORTY_FIVE_UP, presentation.trend)
        assertTrue(presentation.tileMeta.contains("+5"))
        assertTrue(presentation.tileMeta.contains("vor 2 min"))
        assertFalse(presentation.tileMeta.contains("mg/dL"))
    }

    @Test
    fun `collector app value shows the same validated trend beside glucose`() {
        val up = g7TilePresentation(
            reading(123.0, delta = 5.0, trend = Trend.FORTY_FIVE_UP),
            colors,
            now,
        )
        val doubleDown = g7TilePresentation(
            reading(98.0, delta = -9.0, trend = Trend.DOUBLE_DOWN),
            colors,
            now,
        )

        assertEquals("123 ↗", up.value)
        assertEquals("98 ⇊", doubleDown.value)
        assertEquals("Δ +5", up.meta)
        assertEquals("gerade", up.age)
        assertEquals(up.cardBackground, up.background)
        assertEquals(up.cardForeground, up.foreground)
    }

    @Test
    fun `unknown trend never invents a tile or in-app arrow`() {
        val presentation = g7TilePresentation(reading(123.0, trend = Trend.UNKNOWN), colors, now)
        assertNull(presentation.trend)
        assertEquals("123", presentation.value)
        assertEquals("123", presentation.tileValue)
    }

    @Test
    fun `collector ok and working status use Sugarlicious green pill`() {
        val ok = g7TileStatusPresentation(
            G7UserStatus(
                level = G7UserStatusLevel.OK,
                title = "Verbunden",
                phase = "Bereit",
                status = "Aktiv",
                description = "ok",
                action = "none",
            ),
        )
        val working = g7TileStatusPresentation(
            G7UserStatus(
                level = G7UserStatusLevel.WORKING,
                title = "Verbindung wird aufgebaut",
                phase = "Verbinden",
                status = "Aktiv",
                description = "working",
                action = "none",
            ),
        )

        assertEquals("VERBUNDEN", ok.label)
        assertEquals(G7_TILE_ACCENT, ok.color)
        assertEquals(G7_TILE_ACCENT, working.color)
        assertEquals(0x246DE892, withTileAlpha(G7_TILE_ACCENT, 36))
    }

    @Test
    fun `card foreground follows light and dark alarm backgrounds`() {
        assertEquals(G7_TILE_TEXT_DARK, tileForegroundFor(0xFFFFD040.toInt()))
        assertEquals(G7_TILE_TEXT_PRIMARY, tileForegroundFor(0xFF242424.toInt()))
    }

    private fun reading(
        value: Double,
        timestamp: Long = now,
        delta: Double? = null,
        trend: Trend = Trend.FLAT,
        status: CgmReadingStatus = CgmReadingStatus.VALID,
    ) = CgmReading(
        id = "reading-$value-$timestamp-$status",
        source = DataSourceId.DEXCOM_G7_WATCH,
        sensorId = "sensor",
        sessionId = "session",
        glucoseMgDl = value,
        timestampEpochMs = timestamp,
        receivedAtEpochMs = timestamp,
        deltaMgDl = delta,
        trend = trend,
        status = status,
    )
}
