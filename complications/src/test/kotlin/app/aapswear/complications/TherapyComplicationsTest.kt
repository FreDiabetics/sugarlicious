package app.aapswear.complications

import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TherapyComplicationsTest {

    @Test
    fun `all documented providers remain active`() {
        assertEquals(41, AllProviders.classes.distinct().size)
        assertEquals(GlucoseComplication::class.java, AllProviders.classes.first())
        assertTrue(DateComplication::class.java in AllProviders.classes)
        assertTrue(PumpBatteryComplication::class.java in AllProviders.classes)
        assertTrue(PhoneBatteryComplication::class.java in AllProviders.classes)
        assertTrue(AapsStatusComplication::class.java in AllProviders.classes)
    }

    @Test
    fun `glucose plus delta exposes both values`() {
        val service = Robolectric.buildService(GlucosePlusDeltaComplication::class.java).create().get()
        val data = service.getPreviewData(ComplicationType.SHORT_TEXT) as ShortTextComplicationData
        assertEquals("123", data.text.getTextAt(service.resources, Instant.now()).toString())
        assertEquals("+5", data.title!!.getTextAt(service.resources, Instant.now()).toString())
    }

    @Test
    fun `basal IOB COB shows values without redundant labels`() {
        val service = Robolectric.buildService(IobCobBasalComplication::class.java).create().get()
        val data = service.getPreviewData(ComplicationType.SHORT_TEXT) as ShortTextComplicationData
        assertEquals("1.2 U · 15 g", data.text.getTextAt(service.resources, Instant.now()).toString())
        assertEquals("0.80 U/h", data.title!!.getTextAt(service.resources, Instant.now()).toString())
    }

    @Test
    fun `IOB COB short and long providers share title text and description semantics`() {
        val shortService = Robolectric.buildService(IobCobComplication::class.java).create().get()
        val short = shortService.getPreviewData(ComplicationType.LONG_TEXT) as ShortTextComplicationData
        assertEquals("1.2 U", short.title!!.getTextAt(shortService.resources, Instant.now()).toString())
        assertEquals("15 g", short.text.getTextAt(shortService.resources, Instant.now()).toString())
        assertEquals(
            "IOB 1.2 U, COB 15 g",
            short.contentDescription!!.getTextAt(shortService.resources, Instant.now()).toString(),
        )

        val longService = Robolectric.buildService(IobCobLongTextComplication::class.java).create().get()
        val long = longService.getPreviewData(ComplicationType.SHORT_TEXT) as LongTextComplicationData
        assertEquals("1.2 U", long.title!!.getTextAt(longService.resources, Instant.now()).toString())
        assertEquals("15 g", long.text.getTextAt(longService.resources, Instant.now()).toString())
        assertEquals(
            short.contentDescription!!.getTextAt(shortService.resources, Instant.now()).toString(),
            long.contentDescription!!.getTextAt(longService.resources, Instant.now()).toString(),
        )
    }

    @Test
    fun `loop short complication exposes app state text and icon instead of circle glyph`() {
        val service = Robolectric.buildService(LoopComplication::class.java).create().get()
        val data = service.getPreviewData(ComplicationType.SHORT_TEXT) as ShortTextComplicationData
        assertEquals("Closed", data.text.getTextAt(service.resources, Instant.now()).toString())
        assertEquals("Closed Loop", data.contentDescription!!.getTextAt(service.resources, Instant.now()).toString())
        assertNotNull(data.monochromaticImage)
    }

    @Test
    fun `glucose trend short text uses the shared vector arrow`() {
        val service = Robolectric.buildService(GlucoseTrendComplication::class.java).create().get()
        val data = service.getPreviewData(ComplicationType.SHORT_TEXT) as ShortTextComplicationData
        assertEquals("123", data.text.getTextAt(service.resources, Instant.now()).toString())
        assertNotNull(data.monochromaticImage)
    }

    @Test
    fun `all short glucose trend combinations use the shared vector arrow`() {
        listOf(
            GlucoseTrendComplication::class.java,
            GlucoseTrendAgeComplication::class.java,
            GlucoseTrendDeltaComplication::class.java,
            GlucoseTrendDeltaAgeComplication::class.java,
        ).forEach { provider ->
            val service = Robolectric.buildService(provider).create().get()
            val data = service.getPreviewData(ComplicationType.SHORT_TEXT) as ShortTextComplicationData
            assertEquals("123", data.text.getTextAt(service.resources, Instant.now()).toString())
            assertNotNull(data.monochromaticImage)
        }
    }

    @Test
    fun `G6 header uses normalized trend icon and every G6 preview is tappable`() {
        val headerService = Robolectric.buildService(G6StyleHeaderComplication::class.java).create().get()
        val header = headerService.getPreviewData(ComplicationType.SHORT_TEXT) as ShortTextComplicationData
        assertEquals("152", header.text.getTextAt(headerService.resources, Instant.now()).toString())
        assertNotNull(header.monochromaticImage)
        assertNotNull(header.tapAction)

        val statusService = Robolectric.buildService(G6StyleStatusComplication::class.java).create().get()
        val status = statusService.getPreviewData(ComplicationType.SHORT_TEXT) as ShortTextComplicationData
        assertNotNull(status.tapAction)

        val graphService = Robolectric.buildService(G6StyleGraphComplication::class.java).create().get()
        val graph = graphService.getPreviewData(ComplicationType.SMALL_IMAGE) as SmallImageComplicationData
        assertNotNull(graph.tapAction)
    }

    @Test
    fun `watchface compatibility providers expose current previews`() {
        val pumpService = Robolectric.buildService(PumpBatteryComplication::class.java).create().get()
        val pump = pumpService.getPreviewData(ComplicationType.SHORT_TEXT) as ShortTextComplicationData
        assertEquals("80%", pump.text.getTextAt(pumpService.resources, Instant.now()).toString())

        val phoneService = Robolectric.buildService(PhoneBatteryComplication::class.java).create().get()
        val phone = phoneService.getPreviewData(ComplicationType.SHORT_TEXT) as ShortTextComplicationData
        assertEquals("85%", phone.text.getTextAt(phoneService.resources, Instant.now()).toString())

        val statusService = Robolectric.buildService(AapsStatusComplication::class.java).create().get()
        val status = statusService.getPreviewData(ComplicationType.SHORT_TEXT) as ShortTextComplicationData
        assertEquals("123↗", status.text.getTextAt(statusService.resources, Instant.now()).toString())
    }

    @Test
    fun `trend only exposes the shared vector arrow without duplicate text`() {
        val service = Robolectric.buildService(TrendOnlyComplication::class.java).create().get()
        val data = service.getPreviewData(ComplicationType.SHORT_TEXT) as ShortTextComplicationData
        assertEquals("", data.text.getTextAt(service.resources, Instant.now()).toString())
        assertNull(data.title)
        assertNotNull(data.monochromaticImage)
    }

    @Test
    fun `glucose trend ranged complication separates value and trend for renderers`() {
        val service =
            Robolectric
                .buildService(GlucoseTrendRangedValueComplication::class.java)
                .create()
                .get()

        val data =
            service.getPreviewData(
                ComplicationType.RANGED_VALUE,
            ) as RangedValueComplicationData

        assertEquals(40f, data.min)
        assertEquals(260f, data.max)
        assertEquals(123f, data.value)
        assertEquals(
            "123",
            data.text!!
                .getTextAt(
                    service.resources,
                    Instant.now(),
                )
                .toString(),
        )
        assertNull(data.title)
        assertNotNull(data.monochromaticImage)
        assertNull(data.smallImage)
    }

    @Test
    fun `delta complication contains delta and age without title`() {
        val service =
            Robolectric
                .buildService(GlucoseDeltaComplication::class.java)
                .create()
                .get()

        val data =
            service.getPreviewData(
                ComplicationType.SHORT_TEXT,
            ) as ShortTextComplicationData

        assertEquals(
            "+5",
            data.text.getTextAt(service.resources, Instant.now()).toString(),
        )
        assertEquals(
            "2m",
            data.title!!.getTextAt(service.resources, Instant.now()).toString(),
        )
    }

    @Test
    fun `iob and cob show values without titles`() {
        val iobService =
            Robolectric
                .buildService(IobComplication::class.java)
                .create()
                .get()
        val iob =
            iobService.getPreviewData(
                ComplicationType.SHORT_TEXT,
            ) as ShortTextComplicationData
        assertEquals(
            "1.20U",
            iob.text
                .getTextAt(
                    iobService.resources,
                    Instant.now(),
                )
                .toString(),
        )
        assertNull(iob.title)
        assertNotNull(iob.monochromaticImage)

        val cobService =
            Robolectric
                .buildService(CobComplication::class.java)
                .create()
                .get()
        val cob =
            cobService.getPreviewData(
                ComplicationType.SHORT_TEXT,
            ) as ShortTextComplicationData
        assertEquals(
            "15g",
            cob.text
                .getTextAt(
                    cobService.resources,
                    Instant.now(),
                )
                .toString(),
        )
        assertNull(cob.title)
        assertNotNull(cob.monochromaticImage)
    }

    @Test
    fun `date preview uses uppercase weekday and plain day of month`() {
        val service = Robolectric.buildService(DateComplication::class.java).create().get()
        val data = service.getPreviewData(ComplicationType.SHORT_TEXT) as ShortTextComplicationData
        assertEquals(3, data.title!!.getTextAt(service.resources, Instant.now()).length)
        assertEquals(
            data.title!!.getTextAt(service.resources, Instant.now()).toString().uppercase(),
            data.title!!.getTextAt(service.resources, Instant.now()).toString(),
        )
        data.text.getTextAt(service.resources, Instant.now()).toString().toInt()
    }

    @Test
    fun `graph providers each return only their declared image type`() {
        val small = Robolectric.buildService(GlucoseGraphComplication::class.java).create().get()
        val large = Robolectric.buildService(GlucoseGraphLargeComplication::class.java).create().get()

        assertEquals(
            ComplicationType.SMALL_IMAGE,
            (small.getPreviewData(ComplicationType.PHOTO_IMAGE) as SmallImageComplicationData).type,
        )
        assertEquals(
            ComplicationType.PHOTO_IMAGE,
            (large.getPreviewData(ComplicationType.SMALL_IMAGE) as PhotoImageComplicationData).type,
        )
        assertEquals(400 to 140, complicationImageSize(ProviderKind.GRAPH))
        assertEquals(400 to 240, complicationImageSize(ProviderKind.GRAPH_LARGE))
    }

    @Test
    fun `glucose providers keep short long and ranged data in separate services`() {
        val short = Robolectric.buildService(GlucoseComplication::class.java).create().get()
        val long = Robolectric.buildService(GlucoseLongTextComplication::class.java).create().get()
        val ranged = Robolectric.buildService(GlucoseRangedValueComplication::class.java).create().get()

        assertEquals(ComplicationType.SHORT_TEXT, short.getPreviewData(ComplicationType.LONG_TEXT).type)
        assertEquals(
            ComplicationType.LONG_TEXT,
            (long.getPreviewData(ComplicationType.SHORT_TEXT) as LongTextComplicationData).type,
        )
        assertEquals(
            ComplicationType.RANGED_VALUE,
            (ranged.getPreviewData(ComplicationType.SHORT_TEXT) as RangedValueComplicationData).type,
        )
    }
}
