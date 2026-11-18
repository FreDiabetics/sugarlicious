package app.sugarlicious.wear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import app.sugarlicious.complications.ActiveProviderUpdates
import app.sugarlicious.complications.NativeApexGraphComplication
import app.sugarlicious.complications.NativeOrbitGraphComplication
import app.sugarlicious.complications.WatchfaceGraphProfile
import app.sugarlicious.model.GlucoseSample
import app.sugarlicious.model.TherapyDisplayState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchfaceGraphPopupTest {
    @Test fun `closing either popup requests one refresh from its own graph provider`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val shadow = org.robolectric.Shadows.shadowOf(app)
        val providers =
            mapOf(
                "apex" to NativeApexGraphComplication::class.java,
                "orbit" to NativeOrbitGraphComplication::class.java,
            )
        providers.forEach { (face, provider) ->
            ActiveProviderUpdates.activate(app, provider, 47)
            val intent = android.content.Intent(app, WatchfaceGraphActivity::class.java).setData(Uri.parse("app://sugarlicious.watchface-graph/$face?color=FFFFCCB1"))
            val controller =
                org.robolectric.Robolectric
                    .buildActivity(WatchfaceGraphActivity::class.java, intent)
                    .setup()
                    .visible()
            val before = shadow.broadcastIntents.size
            controller.get().onBackPressedDispatcher.onBackPressed()
            controller.pause().stop().destroy()
            val requests = shadow.broadcastIntents.drop(before).filter { it.action == ComplicationDataSourceUpdateRequester.ACTION_REQUEST_UPDATE }
            assertEquals(1, requests.size)
            assertEquals(
                android.content.ComponentName(app, provider),
                requests.single().getParcelableExtra(ComplicationDataSourceUpdateRequester.EXTRA_PROVIDER_COMPONENT, android.content.ComponentName::class.java),
            )
        }
    }

    @Test fun `apex outline uses the passed middle dial color`() {
        val target = GraphPopupTarget.parse(Uri.parse("app://sugarlicious.watchface-graph/apex?color=FFFFCCB1&inRange=FFEFF0E4&outline=FFFF935C"))!!
        assertEquals(0xFFFF935C.toInt(), target.outlineArgb)
    }

    @Test fun `tapping popup card dismisses it and a later launch opens again`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = android.content.Intent(context, WatchfaceGraphActivity::class.java).setData(Uri.parse("app://sugarlicious.watchface-graph/apex?color=FFFFCCB1"))
        repeat(2) {
            val controller =
                org.robolectric.Robolectric
                    .buildActivity(WatchfaceGraphActivity::class.java, intent)
                    .setup()
                    .visible()
            try {
                val activity = controller.get()
                org.robolectric.Shadows
                    .shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(java.time.Duration.ofMillis(50))
                assertFalse(activity.isFinishing)
                val card = findGraphCard(activity.window.decorView)
                assertNotNull(card)
                assertTrue(card!!.performClick())
                assertTrue(activity.isFinishing)
            } finally {
                controller.pause().stop().destroy()
            }
        }
    }

    private fun findGraphCard(view: View): WatchfaceGraphPopupView? {
        if (view is WatchfaceGraphPopupView) return view
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) findGraphCard(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    @Test fun `popup keeps the watchface visible with blur and dim behind its window`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = android.content.Intent(context, WatchfaceGraphActivity::class.java).setData(Uri.parse("app://sugarlicious.watchface-graph/orbit?color=FFFFCCB1"))
        val controller =
            org.robolectric.Robolectric
                .buildActivity(WatchfaceGraphActivity::class.java, intent)
                .setup()
                .visible()
        try {
            val activity = controller.get()
            val flags = activity.window.attributes.flags
            assertTrue(flags and android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0)
            assertTrue(flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
            assertTrue(activity.window.attributes.blurBehindRadius > 0)
            val value = android.util.TypedValue()
            activity.theme.resolveAttribute(android.R.attr.windowIsTranslucent, value, true)
            assertTrue(value.data != 0)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun `invalid popup launches close safely and valid popup back closes only its activity`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        for ((uri, valid) in listOf("app://sugarlicious.watchface-graph/orbit?color=FFFFCCB1" to true, "app://sugarlicious.watchface-graph/orbit?color=bad" to false)) {
            val intent = android.content.Intent(context, WatchfaceGraphActivity::class.java).setData(Uri.parse(uri))
            val controller =
                org.robolectric.Robolectric
                    .buildActivity(WatchfaceGraphActivity::class.java, intent)
                    .setup()
                    .visible()
            try {
                val activity = controller.get()
                org.robolectric.Shadows
                    .shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(java.time.Duration.ofMillis(50))
                assertEquals(!valid, activity.isFinishing)
                if (valid) {
                    activity.onBackPressedDispatcher.onBackPressed()
                    assertTrue(activity.isFinishing)
                }
            } finally {
                controller.pause().stop().destroy()
            }
        }
    }

    @Test fun `popup accepts only an explicit editor color and known watchface`() {
        val target = GraphPopupTarget.parse(Uri.parse("app://sugarlicious.watchface-graph/orbit?color=FFFFCCB1"))!!
        assertEquals(WatchfaceGraphProfile.ORBIT, target.profile)
        assertEquals(0xFFFFCCB1.toInt(), target.argb)
        for (uri in listOf("app://sugarlicious.watchface-graph/orbit", "app://sugarlicious.watchface-graph/other?color=FFFFFFFF", "https://watchface-graph/orbit?color=FFFFFFFF", "app://sugarlicious.watchface-graph/apex?color=bad", "app://sugarlicious.watchface-graph/apex?color=FFFFFFFF&color=FF000000")) {
            assertNull(uri, GraphPopupTarget.parse(Uri.parse(uri)))
        }
    }

    @Test fun `popup geometry matches the SugarWear graph tile on either orientation`() {
        val geometry = graphPopupGeometry(200, 240)
        assertEquals(152f, geometry.widthDp, 0.001f)
        assertEquals(131f, geometry.heightDp, 0.001f)
        assertEquals(19.76f, geometry.cornerRadiusDp, 0.001f)
        assertEquals(geometry, graphPopupGeometry(240, 200))
    }

    @Test fun `large graph uses the passed editor ink instead of application graph colors`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context
            .getSharedPreferences("watch_display", 0)
            .edit()
            .putInt("graph_color_cgm_in", 0xFF00FF00.toInt())
            .commit()
        val now = 1_000_000_000L
        val history = (0..18).map { GlucoseSample(40.0 + it * 10, now - (18 - it) * 300_000L) }
        val state = WearOverviewUiState(WearOverviewSnapshot(therapy = TherapyDisplayState(receivedAtEpochMs = now, glucoseHistory = history)), nowEpochMs = now)
        for ((profile, ink) in listOf(WatchfaceGraphProfile.ORBIT to 0xFFFFCCB1.toInt(), WatchfaceGraphProfile.APEX to 0xFFD4DAB6.toInt())) {
            val view = WatchfaceGraphPopupView(context, GraphPopupTarget(profile, ink, 0xFF22EB60.toInt()))
            view.update(state)
            view.measure(View.MeasureSpec.makeMeasureSpec(364, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(330, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 364, 330)
            val bitmap = Bitmap.createBitmap(364, 330, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            var inkPixels = 0
            var wrongGreen = 0
            var rangePixels = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    if (bitmap.getPixel(x, y) == ink) inkPixels++
                    if (bitmap.getPixel(x, y) == 0xFF22EB60.toInt()) rangePixels++
                    if (bitmap.getPixel(x, y) == 0xFF00FF00.toInt()) wrongGreen++
                }
            }
            assertTrue(inkPixels > 30)
            assertEquals(0, wrongGreen)
            assertTrue(rangePixels > 30)
            val directory = java.io.File("build/reports/watchface-graph-popup").apply { mkdirs() }
            java.io
                .File(directory, "${profile.name.lowercase()}.png")
                .outputStream()
                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
