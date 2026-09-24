package app.aapswear.wear

import androidx.test.core.app.ApplicationProvider
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.ResourceBuilders.IMAGE_FORMAT_RGB_565
import androidx.wear.tiles.RequestBuilders
import app.aapswear.model.BasalState
import app.aapswear.model.CarbState
import app.aapswear.model.DataSourceId
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.InsulinState
import app.aapswear.model.TargetState
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.TherapyIndicatorIcon
import app.aapswear.model.Trend
import app.aapswear.protocol.WatchUiColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SugarliciousTilesTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val now = 50_000_000L
    private val colors =
        WatchUiColors(
            glucoseLow = 0xFFAA0000.toInt(),
            glucoseInRange = 0xFF00AA00.toInt(),
            glucoseHigh = 0xFFAAAA00.toInt(),
        )

    @Test
    fun `tile emphasis is calibrated to the Wear app system font`() {
        assertEquals(500, sugarliciousTileWeight(true))
        assertEquals(400, sugarliciousTileWeight(false))
    }

    @Test
    fun `glucose tile keeps value trend and source separate and explicit`() {
        val presentation = wearGlucoseTilePresentation(state(123.0, now - 2 * 60_000L), colors, now)

        assertEquals("123", presentation.value)
        assertEquals(Trend.FORTY_FIVE_UP, presentation.trend)
        assertTrue(presentation.meta.contains("mg/dL"))
        assertTrue(presentation.footer.contains("AndroidAPS"))
        assertEquals(colors.glucoseInRange, presentation.valueColor)
        assertEquals("AKTUELL", presentation.status)
    }

    @Test
    fun `signal loss tile preserves last validated values and labels signal loss`() {
        val stale = wearGlucoseTilePresentation(state(123.0, now - 20 * 60_000L), colors, now)
        val therapy = wearTherapyTilePresentation(state(123.0, now - 20 * 60_000L), now)

        assertEquals("123", stale.value)
        assertNull(stale.trend)
        assertEquals("SIGNALVERLUST", stale.status)
        assertTrue(therapy.displayable)
        assertEquals("1.2 U", therapy.iob)
        assertEquals("18 g", therapy.cob)
        assertEquals("0.70", therapy.basal)
        assertTrue(therapy.footer.contains("letzter Stand"))
    }

    @Test
    fun `therapy tile exposes three independent modern metric cards`() {
        val presentation = wearTherapyTilePresentation(state(123.0, now - 60_000L), now)

        assertTrue(presentation.displayable)
        assertEquals("1.2 U", presentation.iob)
        assertEquals("18 g", presentation.cob)
        assertEquals("0.70", presentation.basal)
    }

    @Test
    fun `therapy rings expose values without duplicate metric headings`() {
        val presentation = wearTherapyTilePresentation(state(123.0, now - 60_000L), now)

        assertEquals("1.2 U", therapyMetricValue(TherapyTileMetric.IOB, presentation))
        assertEquals("18 g", therapyMetricValue(TherapyTileMetric.COB, presentation))
        assertEquals("0.70", therapyMetricValue(TherapyTileMetric.BASAL, presentation))
    }

    @Test
    fun `three ring group is derived and centered as one square composition`() {
        val geometry = therapyTileGroupGeometry(192f, 3)

        assertEquals(geometry.ringDiameterDp * 2f + geometry.gapDp, geometry.widthDp, 0.001f)
        assertEquals(geometry.widthDp, geometry.heightDp, 0.001f)
        assertEquals((192f - geometry.widthDp) / 2f, geometry.originXDp, 0.001f)
        assertEquals((192f - geometry.heightDp) / 2f, geometry.originYDp, 0.001f)
    }

    @Test
    fun `therapy tile renders every valid one two and three metric selection`() {
        val selections =
            listOf(
                setOf(TherapyTileMetric.IOB),
                setOf(TherapyTileMetric.COB),
                setOf(TherapyTileMetric.BASAL),
                setOf(TherapyTileMetric.IOB, TherapyTileMetric.COB),
                setOf(TherapyTileMetric.IOB, TherapyTileMetric.BASAL),
                setOf(TherapyTileMetric.COB, TherapyTileMetric.BASAL),
                TherapyTileMetric.entries.toSet(),
            )
        val device =
            DeviceParameters
                .Builder()
                .setScreenWidthDp(192)
                .setScreenHeightDp(192)
                .setScreenDensity(2f)
                .build()
        selections.forEach { selected ->
            TherapyTileSelectionStore.write(context, selected)
            val service = Robolectric.buildService(TherapyTileService::class.java).create().get()
            val request =
                RequestBuilders.TileRequest
                    .Builder()
                    .setDeviceConfiguration(device)
                    .build()
            val tile =
                service
                    .onTileRequest(request)
                    .get()
            assertTrue(tile.resourcesVersion.contains("therapy"))
            assertEquals(selected.size, TherapyTileSelectionStore.read(context).metrics.size)
            val resourceIds =
                request.scope
                    .collectResources()
                    .idToImageMapping.keys
            selected.forEach { metric ->
                val expectedPrefix = if (metric == TherapyTileMetric.BASAL) "therapy_basal" else "therapy_${metric.name.lowercase()}"
                assertTrue(resourceIds.any { it.startsWith(expectedPrefix) })
            }
            service.onDestroy()
        }
    }

    @Test
    fun `therapy rings keep equal bounds and the mobile stroke geometry`() {
        val single = TherapyRingLayoutSpec(therapyTileGroupGeometry(192f, 1).ringDiameterDp)
        val pair = TherapyRingLayoutSpec(therapyTileGroupGeometry(192f, 2).ringDiameterDp)
        val triple = TherapyRingLayoutSpec(therapyTileGroupGeometry(192f, 3).ringDiameterDp)

        assertEquals(7f, single.strokeWidthDp, 0f)
        assertEquals(220f, single.protoLayoutStartDegrees, 0f)
        assertEquals(280f, single.sweepDegrees, 0f)
        assertEquals(pair.diameterDp, triple.diameterDp, 0f)
        assertEquals(pair.strokeWidthDp, triple.strokeWidthDp, 0f)
    }

    @Test
    fun `therapy rings use the shared mobile icon states`() {
        assertEquals(TherapyIndicatorIcon.IOB, wearTherapyIcon(TherapyTileMetric.IOB, null, now))
        assertEquals(TherapyIndicatorIcon.COB, wearTherapyIcon(TherapyTileMetric.COB, null, now))
        assertEquals(
            TherapyIndicatorIcon.BASAL_LESS,
            wearTherapyIcon(TherapyTileMetric.BASAL, state(123.0, now).copy(basal = BasalState(currentUnitsPerHour = 0.7, tempPercent = 80)), now),
        )
        assertEquals(
            TherapyIndicatorIcon.BASAL_MORE,
            wearTherapyIcon(TherapyTileMetric.BASAL, state(123.0, now).copy(basal = BasalState(currentUnitsPerHour = 0.7, tempPercent = 120)), now),
        )
    }

    @Test
    fun `therapy ring background precomposites the mobile thirty percent accent`() {
        assertEquals(0xFF000000.toInt(), opaqueOverlay(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0f))
        assertEquals(0xFFFFFFFF.toInt(), opaqueOverlay(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 1f))
        assertEquals(0xFF4C4C4C.toInt(), opaqueOverlay(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0.30f))
    }

    @Test
    fun `graph tile positions every dot by measured time and advances with the minute clock`() {
        val measuredAt = now - 5 * 60_000L
        val oldReceivedNow = GlucoseSample(110.0, measuredAt, receivedAtEpochMs = now)
        val source = state(123.0, now).copy(glucoseHistory = listOf(oldReceivedNow))

        val initial = wearTileGraphPoints(source, now, 3, 180f)
        val oneMinuteLater = wearTileGraphPoints(source, now + 60_000L, 3, 180f)
        val oldInitial = initial.single { it.sample.measuredAtEpochMs == measuredAt }
        val oldLater = oneMinuteLater.single { it.sample.measuredAtEpochMs == measuredAt }
        val currentInitial = initial.single { it.sample.measuredAtEpochMs == now }

        assertEquals(180f, currentInitial.xDp, 0.001f)
        assertTrue(oldLater.xDp < oldInitial.xDp)
        assertTrue(oldInitial.xDp < currentInitial.xDp)
    }

    @Test
    fun `graph tile registers its inline image on the tile request scope`() {
        WearTileContentStore.write(context, WearTileKind.GLUCOSE, WearTileContent.GRAPH)
        val device =
            DeviceParameters
                .Builder()
                .setScreenWidthDp(192)
                .setScreenHeightDp(192)
                .setScreenDensity(2f)
                .build()
        val service = Robolectric.buildService(GlucoseTileService::class.java).create().get()
        val request =
            RequestBuilders.TileRequest
                .Builder()
                .setDeviceConfiguration(device)
                .build()

        service.onTileRequest(request).get()
        val resources = request.scope.collectResources()

        assertTrue(request.scope.hasResources())
        val inline = resources.idToImageMapping.getValue("live_cgm_graph").inlineResource!!
        assertEquals(IMAGE_FORMAT_RGB_565, inline.format)
        assertTrue(inline.widthPx > 0)
        assertTrue(inline.heightPx > 0)
        assertEquals(inline.widthPx * inline.heightPx * 2, inline.data.size)
        service.onDestroy()
    }

    private fun state(
        value: Double,
        measuredAt: Long,
    ) = TherapyDisplayState(
        source = DataSourceId.ANDROID_APS,
        receivedAtEpochMs = now,
        glucose =
            GlucoseState(
                valueMgDl = value,
                displayUnit = GlucoseUnit.MG_DL,
                trend = Trend.FORTY_FIVE_UP,
                measuredAtEpochMs = measuredAt,
                deltaMgDl = 5.0,
            ),
        insulin = InsulinState(totalIob = 1.2),
        carbs = CarbState(cobGrams = 18.0),
        basal = BasalState(currentUnitsPerHour = 0.70),
        target = TargetState(lowMgDl = 80.0, highMgDl = 160.0),
    )
}
