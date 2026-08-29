package app.aapswear.mobile

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColorStore
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.TargetSample
import app.aapswear.model.TargetState
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
import app.aapswear.storage.TherapyStateStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import app.aapswear.model.GlucoseTrendSizing

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PersistentBridgeServiceTest {

    @Test
    fun `notification value block keeps metadata below the value and flat arrow`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val layouts =
            listOf(
                Triple(R.layout.notification_sugarlicious_collapsed, 29f, 11f),
                Triple(R.layout.notification_sugarlicious_expanded, 36f, 13f),
            )

        layouts.forEach { (layoutId, valueSp, metaSp) ->
            val root = LayoutInflater.from(context).inflate(layoutId, null)
            val info = root.findViewById<ViewGroup>(R.id.notification_info_block)
            val primary = root.findViewById<ViewGroup>(R.id.notification_primary_row)
            val value = root.findViewById<TextView>(R.id.notification_value)
            val trend = root.findViewById<ImageView>(R.id.notification_trend)
            val meta = root.findViewById<TextView>(R.id.notification_meta)
            val density = context.resources.displayMetrics.density
            val valuePx =
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    valueSp,
                    context.resources.displayMetrics,
                )
            val metaPx =
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    metaSp,
                    context.resources.displayMetrics,
                )
            val digitBounds = Rect()
            value.text = "123"
            meta.text = "+5 · 2 min alt"
            trend.visibility = View.VISIBLE
            root.measure(
                View.MeasureSpec.makeMeasureSpec(
                    (400f * density).toInt(),
                    View.MeasureSpec.EXACTLY,
                ),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            value.paint.getTextBounds("123", 0, 3, digitBounds)

            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, info.layoutParams.width)
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, primary.layoutParams.width)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, meta.layoutParams.width)
            assertEquals(valuePx, value.textSize, 0.5f)
            assertEquals(metaPx, meta.textSize, 0.5f)
            assertEquals(primary.measuredWidth, info.measuredWidth)
            assertEquals(info.measuredWidth, meta.measuredWidth)
            assertTrue(
                abs(value.textSize * GlucoseTrendSizing.REFERENCE_ARROW_TO_GLUCOSE_HEIGHT - trend.layoutParams.height) <= 3f * density,
            )
        }
    }

    @Test
    @Config(sdk = [35])
    fun `normal notification is ongoing private and sticky by default`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("dashboard_ui", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val controller = Robolectric.buildService(PersistentBridgeService::class.java).create().startCommand(0, 1)
        val service = controller.get()
        val manager = service.getSystemService(NotificationManager::class.java)
        val notification = shadowOf(manager).getNotification(PersistentBridgeService.NOTIFICATION_ID)

        assertNotNull(notification)
        assertEquals("—", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(notification.extras.getBoolean(PersistentBridgeService.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(PersistentBridgeService.CHANNEL_ID).importance)
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 2))
        controller.destroy()
    }

    @Test
    @Config(sdk = [36])
    fun `live preference requests promoted status with current glucose delta and graph`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("dashboard_ui", android.content.Context.MODE_PRIVATE).edit()
            .clear()
            .putString("themeMode", "DARK")
            .putBoolean(PersistentBridgeService.PREFERENCE_LIVE_NOTIFICATION, true)
            .commit()
        context.getSharedPreferences("diagnostics", android.content.Context.MODE_PRIVATE).edit()
            .putString("sourceVersion", "4.0.0-dev")
            .putInt("reachableWatches", 1)
            .commit()
        val now = System.currentTimeMillis()
        val therapyState = TherapyDisplayState(
            receivedAtEpochMs = now,
            glucose = GlucoseState(
                valueMgDl = 123.0,
                displayUnit = GlucoseUnit.MG_DL,
                trend = Trend.FLAT,
                measuredAtEpochMs = now,
                deltaMgDl = 5.0,
            ),
            glucoseHistory = listOf(
                GlucoseSample(115.0, now - 10 * 60_000L),
                GlucoseSample(120.0, now - 5 * 60_000L),
            ),
        )
        runBlocking { TherapyStateStore(context).save(therapyState) }

        val controller = Robolectric.buildService(PersistentBridgeService::class.java).create().startCommand(0, 1)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val manager = controller.get().getSystemService(NotificationManager::class.java)
        val notification = shadowOf(manager).getNotification(PersistentBridgeService.NOTIFICATION_ID)
        val content = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

        assertEquals("123", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(notification.extras.getBoolean(PersistentBridgeService.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertTrue(content.contains("+5"))
        assertFalse(content.contains("mg/dL"))
        assertNull(notification.getLargeIcon())
        assertNotNull(notification.contentView)
        assertNotNull(notification.bigContentView)
        assertNull(notification.extras.getParcelable(Notification.EXTRA_PICTURE))

        val graphPreferences =
            context.getSharedPreferences("dashboard_ui", android.content.Context.MODE_PRIVATE)
        val highEdgeColor = Color.rgb(198, 36, 91)
        val appInRangeColor = Color.rgb(0, 184, 126)
        val lowEdgeColor = Color.rgb(24, 156, 214)
        SugarliciousColorStore.save(
            graphPreferences,
            SugarliciousColorRole.RANGE_IN_RANGE,
            appInRangeColor,
        )
        val graphColors = graphPreferences.edit()
        listOf("dark", "light").forEach { mode ->
            graphColors.putInt(
                "notification.color.$mode.${SugarliciousColorRole.RANGE_HIGH.preferenceKey}",
                highEdgeColor,
            )
            // Legacy/notification-only values must never turn the shared in-range band white.
            graphColors.putInt(
                "notification.color.$mode.${SugarliciousColorRole.RANGE_IN_RANGE.preferenceKey}",
                Color.WHITE,
            )
            graphColors.putInt(
                "notification.color.$mode.${SugarliciousColorRole.TARGET_BAND.preferenceKey}",
                Color.WHITE,
            )
            graphColors.putInt(
                "notification.color.$mode.${SugarliciousColorRole.RANGE_LOW.preferenceKey}",
                lowEdgeColor,
            )
        }
        graphColors.commit()

        val collapsedGraph = NotificationGraphRenderer.renderCollapsed(
            context,
            therapyState,
            graphPreferences,
        )
        val expandedGraph = NotificationGraphRenderer.renderExpanded(
            context,
            therapyState,
            graphPreferences,
        )

        assertEquals(NotificationGraphRenderer.COLLAPSED_WIDTH, collapsedGraph.width)
        assertEquals(NotificationGraphRenderer.COLLAPSED_HEIGHT, collapsedGraph.height)
        assertEquals(NotificationGraphRenderer.EXPANDED_WIDTH, expandedGraph.width)
        assertEquals(NotificationGraphRenderer.EXPANDED_HEIGHT, expandedGraph.height)
        listOf(collapsedGraph, expandedGraph).forEach { graph ->
            assertEquals(0, Color.alpha(graph.getPixel(0, 0)))
            assertEquals(0, Color.alpha(graph.getPixel(graph.width - 1, 0)))
            assertEquals(0, Color.alpha(graph.getPixel(0, graph.height - 1)))
            assertEquals(0, Color.alpha(graph.getPixel(graph.width - 1, graph.height - 1)))
            // In-range data must leave both excursion regions on the graph background.
            assertEquals(0xFF202020.toInt(), graph.getPixel(graph.width / 2, 0))
            assertEquals(0xFF202020.toInt(), graph.getPixel(graph.width / 2, graph.height - 1))
        }
        var inRangePixels = 0
        for (y in 0 until expandedGraph.height) {
            for (x in 0 until expandedGraph.width) {
                if (expandedGraph.getPixel(x, y) == appInRangeColor) inRangePixels++
            }
        }
        assertTrue("inRangePixels=$inRangePixels", inRangePixels > 1_000)
        assertEquals(null, notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        assertEquals(1, notification.actions.size)
        controller.destroy()
    }

    @Test
    @Config(sdk = [35])
    fun `notification graph ignores target value and target history`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences("dashboard_ui", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().putString("themeMode", "DARK").commit()
        val now = System.currentTimeMillis()
        val base =
            TherapyDisplayState(
                receivedAtEpochMs = now,
                glucose = GlucoseState(
                    valueMgDl = 123.0,
                    displayUnit = GlucoseUnit.MG_DL,
                    measuredAtEpochMs = now - 60_000L,
                ),
                glucoseHistory = listOf(
                    GlucoseSample(116.0, now - 10 * 60_000L),
                    GlucoseSample(120.0, now - 5 * 60_000L),
                ),
                target = TargetState(lowMgDl = 80.0, highMgDl = 160.0, valueMgDl = 100.0),
                targetHistory = listOf(
                    TargetSample(
                        valueMgDl = 100.0,
                        startedAtEpochMs = now - 60L * 60_000L,
                        endsAtEpochMs = now,
                    ),
                ),
            )
        val changedTargetHistory =
            base.copy(
                target = base.target?.copy(valueMgDl = 130.0, temporary = true),
                targetHistory = listOf(
                    TargetSample(
                        valueMgDl = 130.0,
                        startedAtEpochMs = now - 30L * 60_000L,
                        endsAtEpochMs = now + 30L * 60_000L,
                        temporary = true,
                    ),
                ),
            )

        val collapsedBase = NotificationGraphRenderer.renderCollapsed(context, base, preferences)
        val collapsedChanged = NotificationGraphRenderer.renderCollapsed(context, changedTargetHistory, preferences)
        val expandedBase = NotificationGraphRenderer.renderExpanded(context, base, preferences)
        val expandedChanged = NotificationGraphRenderer.renderExpanded(context, changedTargetHistory, preferences)

        fun assertTargetIndependentColumnsEqual(
            baseGraph: android.graphics.Bitmap,
            changedGraph: android.graphics.Bitmap,
        ) {
            assertEquals(baseGraph.width, changedGraph.width)
            assertEquals(baseGraph.height, changedGraph.height)
            // All CGM points in this fixture are clustered in the final ~10 minutes of a 3 h
            // window. Sampling three earlier columns therefore tests target-band/target-history
            // rendering without making the assertion sensitive to millisecond-level x drift of
            // anti-aliased glucose dots between two independent render calls.
            listOf(baseGraph.width / 4, baseGraph.width / 2, baseGraph.width * 3 / 4).forEach { x ->
                for (y in 0 until baseGraph.height) {
                    assertEquals(
                        "target-independent graph differs at x=$x y=$y",
                        baseGraph.getPixel(x, y),
                        changedGraph.getPixel(x, y),
                    )
                }
            }
        }

        assertTargetIndependentColumnsEqual(collapsedBase, collapsedChanged)
        assertTargetIndependentColumnsEqual(expandedBase, expandedChanged)
    }

    @Test
    @Config(sdk = [35])
    fun `notification graph accepts only one two or three hours`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences("dashboard_ui", android.content.Context.MODE_PRIVATE)

        preferences.edit().clear().putInt(PersistentBridgeService.PREFERENCE_NOTIFICATION_GRAPH_HOURS, 1).commit()
        assertEquals(1, NotificationGraphRenderer.notificationGraphHours(preferences))

        preferences.edit().putInt(PersistentBridgeService.PREFERENCE_NOTIFICATION_GRAPH_HOURS, 2).commit()
        assertEquals(2, NotificationGraphRenderer.notificationGraphHours(preferences))

        preferences.edit().putInt(PersistentBridgeService.PREFERENCE_NOTIFICATION_GRAPH_HOURS, 3).commit()
        assertEquals(3, NotificationGraphRenderer.notificationGraphHours(preferences))

        preferences.edit().putInt(PersistentBridgeService.PREFERENCE_NOTIFICATION_GRAPH_HOURS, 6).commit()
        assertEquals(3, NotificationGraphRenderer.notificationGraphHours(preferences))
    }

    @Test
    @Config(sdk = [35])
    fun `boot receiver requests persistent service restart`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val shadowContext = shadowOf(context)
        shadowContext.clearStartedServices()

        PersistentBridgeBootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val started = shadowContext.nextStartedService
        assertEquals(PersistentBridgeService::class.java.name, started.component?.className)
    }
}
