package app.aapswear.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColorStore
import app.aapswear.model.Freshness
import app.aapswear.model.FreshnessPolicy
import app.aapswear.model.CanonicalCgmHistory
import app.aapswear.model.CgmGraphPolicy
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.RangeExcursion
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import app.aapswear.storage.TherapyStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PersistentBridgeService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var uiPreferences: SharedPreferences
    private lateinit var diagnostics: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var latestState: TherapyDisplayState? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        uiPreferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        diagnostics = getSharedPreferences(DIAGNOSTICS_NAME, MODE_PRIVATE)
        uiPreferences.registerOnSharedPreferenceChangeListener(this)
        diagnostics.registerOnSharedPreferenceChangeListener(this)
        createNotificationChannel()
        scope.launch {
            TherapyStateStore(this@PersistentBridgeService).state.collectLatest {
                latestState = it
                if (foregroundStarted) notifyUpdated()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISABLE_LIVE) {
            uiPreferences.edit { putBoolean(PREFERENCE_LIVE_NOTIFICATION, false) }
        }
        promoteToForeground(buildNotification())
        foregroundStarted = true
        return START_STICKY
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (foregroundStarted) notifyUpdated()
    }

    override fun onDestroy() {
        uiPreferences.unregisterOnSharedPreferenceChangeListener(this)
        diagnostics.unregisterOnSharedPreferenceChangeListener(this)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notifyUpdated() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun promoteToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val liveRequested = uiPreferences.getBoolean(PREFERENCE_LIVE_NOTIFICATION, false)
        val liveCapable = liveRequested && Build.VERSION.SDK_INT >= 36
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notificationGraphEnabled =
            uiPreferences.getBoolean(PREFERENCE_NOTIFICATION_GRAPH_ENABLED, true)
        val display = notificationDisplay(latestState, notificationGraphEnabled)
        val collapsedGraph =
            if (notificationGraphEnabled) {
                NotificationGraphRenderer.renderCollapsed(this, latestState, uiPreferences)
            } else null
        val expandedGraph =
            if (notificationGraphEnabled) {
                NotificationGraphRenderer.renderExpanded(this, latestState, uiPreferences)
            } else null
        val collapsedView =
            notificationRemoteView(R.layout.notification_sugarlicious_collapsed, display, collapsedGraph)
        val expandedView =
            notificationRemoteView(R.layout.notification_sugarlicious_expanded, display, expandedGraph)

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_outlined)
            .setColor(getColor(R.color.app_accent))
            .setContentTitle(display.title)
            .setContentText(display.subtitle)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(collapsedView)
            .setCustomBigContentView(expandedView)

        if (liveCapable) {
            val disableLive = PendingIntent.getService(
                this,
                1,
                Intent(this, PersistentBridgeService::class.java).setAction(ACTION_DISABLE_LIVE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_notification),
                        "Live beenden",
                        disableLive,
                    ).build(),
                )
                .addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        }

        return builder.build()
    }

    private fun notificationRemoteView(
        layoutId: Int,
        display: NotificationDisplay,
        graph: Bitmap?,
    ): RemoteViews {
        val palette = SugarliciousColorStore.load(uiPreferences)
        val textPrimary = palette.argb(SugarliciousColorRole.TEXT_PRIMARY)
        val textSecondary = palette.argb(SugarliciousColorRole.TEXT_SECONDARY)

        return RemoteViews(packageName, layoutId).apply {
            setTextViewText(R.id.notification_value, display.title)
            setTextViewText(R.id.notification_meta, display.subtitle)
            setTextColor(R.id.notification_value, textPrimary)
            setTextColor(R.id.notification_meta, textSecondary)
            val trendBitmap =
                display.trend?.let { NotificationTrendRenderer.render(this@PersistentBridgeService, it) }
            if (trendBitmap != null) {
                setViewVisibility(R.id.notification_trend, View.VISIBLE)
                setImageViewBitmap(R.id.notification_trend, trendBitmap)
            } else {
                setViewVisibility(R.id.notification_trend, View.GONE)
            }
            if (graph != null) {
                setViewVisibility(R.id.notification_graph, View.VISIBLE)
                setImageViewBitmap(R.id.notification_graph, graph)
            } else {
                setViewVisibility(R.id.notification_graph, View.GONE)
            }
        }
    }

    private fun notificationDisplay(
        state: TherapyDisplayState?,
        graphEnabled: Boolean,
    ): NotificationDisplay {
        val glucose = state?.glucose
        val now = System.currentTimeMillis()
        val freshness = FreshnessPolicy.classify(glucose?.measuredAtEpochMs, now)
        if (glucose == null || !TherapyDisplayFormatter.isGlucoseDisplayable(state, now)) {
            return NotificationDisplay("—", "Keine aktuellen Glukosedaten", null)
        }

        val selectedUnit = DashboardUiPreferences.read(uiPreferences).unitFor(state)
        val value =
            if (selectedUnit == GlucoseUnit.MMOL_L) {
                String.format(Locale.getDefault(), "%.1f", glucose.valueMgDl / 18.0)
            } else {
                glucose.valueMgDl.roundToInt().toString()
            }
        val delta =
            TherapyDisplayFormatter.signedDelta(glucose.deltaMgDl, selectedUnit)
                .ifBlank { "—" }
        val age = ((now - glucose.measuredAtEpochMs).coerceAtLeast(0L) / 60_000L)
        val prefix = if (freshness == Freshness.DELAYED) "Verzögert · " else ""
        // Delta intentionally replaces the former mg/dL/mmol/L line in both layouts.
        val subtitle = "$prefix$delta · $age min alt"
        return NotificationDisplay(value, subtitle, glucose.trend)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Glukose im Hintergrund",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Zeigt den aktuellen Glukosewert und hält die lokale Watch-Verbindung aktiv"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private data class NotificationDisplay(
        val title: String,
        val subtitle: String,
        val trend: app.aapswear.model.Trend?,
    )

    companion object {
        const val PREFERENCE_LIVE_NOTIFICATION = "liveNotification"
        const val PREFERENCE_NOTIFICATION_GRAPH_ENABLED = "notification.graphEnabled"
        const val PREFERENCE_NOTIFICATION_GRAPH_HOURS = "notification.graphHours"
        const val PREFERENCE_NOTIFICATION_DOT_RADIUS = "notification.cgmDotRadiusDp"
        const val PREFERENCE_NOTIFICATION_DOT_OUTLINE_ENABLED = "notification.cgmDotOutlineEnabled"
        const val PREFERENCE_NOTIFICATION_DOT_OUTLINE_WIDTH = "notification.cgmDotOutlineWidthDp"
        const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
        const val CHANNEL_ID = "sugarlicious_background"
        const val NOTIFICATION_ID = 4101
        private const val PREFERENCES_NAME = "dashboard_ui"
        private const val DIAGNOSTICS_NAME = "diagnostics"
        private const val ACTION_REFRESH = "app.aapswear.action.REFRESH_PERSISTENT_NOTIFICATION"
        private const val ACTION_DISABLE_LIVE = "app.aapswear.action.DISABLE_LIVE_NOTIFICATION"

        fun start(context: Context): Boolean = startWithAction(context, null)
        fun refresh(context: Context): Boolean = startWithAction(context, ACTION_REFRESH)

        private fun startWithAction(context: Context, action: String?): Boolean = try {
            context.startForegroundService(
                Intent(context, PersistentBridgeService::class.java).apply { this.action = action },
            )
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
    }
}

internal object NotificationGraphRenderer {
    const val COLLAPSED_WIDTH = 704
    const val COLLAPSED_HEIGHT = 184
    const val EXPANDED_WIDTH = 720
    const val EXPANDED_HEIGHT = 296
    const val WIDTH = EXPANDED_WIDTH
    const val HEIGHT = EXPANDED_HEIGHT

    private const val COLLAPSED_DISPLAY_HEIGHT_DP = 46f
    private const val EXPANDED_DISPLAY_HEIGHT_DP = 148f

    fun renderCollapsed(
        context: Context,
        state: TherapyDisplayState?,
        preferences: SharedPreferences,
    ): Bitmap = render(
        context = context,
        state = state,
        preferences = preferences,
        width = COLLAPSED_WIDTH,
        height = COLLAPSED_HEIGHT,
        displayHeightDp = COLLAPSED_DISPLAY_HEIGHT_DP,
        graphHoursOverride = notificationGraphHours(preferences),
    )

    fun renderExpanded(
        context: Context,
        state: TherapyDisplayState?,
        preferences: SharedPreferences,
    ): Bitmap = render(
        context = context,
        state = state,
        preferences = preferences,
        width = EXPANDED_WIDTH,
        height = EXPANDED_HEIGHT,
        displayHeightDp = EXPANDED_DISPLAY_HEIGHT_DP,
        graphHoursOverride = notificationGraphHours(preferences),
    )

    internal fun notificationGraphHours(preferences: SharedPreferences): Int =
        preferences
            .getInt(PersistentBridgeService.PREFERENCE_NOTIFICATION_GRAPH_HOURS, 3)
            .takeIf { it in 1..3 }
            ?: 3

    fun render(
        context: Context,
        state: TherapyDisplayState?,
        preferences: SharedPreferences,
        width: Int = WIDTH,
        height: Int = HEIGHT,
        displayHeightDp: Float = EXPANDED_DISPLAY_HEIGHT_DP,
        graphHoursOverride: Int? = null,
    ): Bitmap {
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val palette = SugarliciousColorStore.load(preferences)
        val notificationColorPrefix =
            if (palette.isLight) "notification.color.light." else "notification.color.dark."
        fun graphColor(role: SugarliciousColorRole): Int {
            val overrideKey = "notification.color.override." + role.preferenceKey
            val legacyModeKey = notificationColorPrefix + role.preferenceKey
            return when {
                preferences.contains(overrideKey) -> preferences.getInt(overrideKey, palette.argb(role))
                role == SugarliciousColorRole.RANGE_IN_RANGE -> palette.argb(role)
                preferences.contains(legacyModeKey) -> preferences.getInt(legacyModeKey, palette.argb(role))
                else -> palette.argb(role)
            }
        }

        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val dpToBitmap = height / displayHeightDp
        val cornerDp = if (displayHeightDp > 100f) 18f else 10f
        val radius = cornerDp * dpToBitmap
        val clip = Path().apply {
            addRoundRect(bounds, radius, radius, Path.Direction.CW)
        }
        canvas.clipPath(clip)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = graphColor(SugarliciousColorRole.GRAPH_BACKGROUND)
        canvas.drawRoundRect(bounds, radius, radius, paint)

        val now = System.currentTimeMillis()
        val graphHours = graphHoursOverride ?: preferences
            .getInt("graphHours", 3)
            .takeIf { it in listOf(3, 6, 12, 24) }
            ?: 3
        val windowMs = graphHours * 60L * 60L * 1000L
        val start = now - windowMs
        val validSamples = CanonicalCgmHistory.merge(
            samples = buildList {
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
            },
            nowEpochMs = now,
            preferredSource = state?.source,
            windowMs = windowMs,
        )
        val points = validSamples.associate { it.measuredAtEpochMs to it.valueMgDl }.entries.sortedBy { it.key }
        if (points.isEmpty()) return bitmap

        val targetLow = state?.target?.lowMgDl ?: 80.0
        val targetHigh = state?.target?.highMgDl ?: 160.0
        val excursion =
            CgmGraphPolicy.rangeExcursion(
                validSamples,
                targetLow,
                targetHigh,
            )
        val lowest = points.minOf { it.value }
        val highest = points.maxOf { it.value }
        val minValue = min(targetLow - 24.0, lowest - max(12.0, lowest * 0.08))
        val maxValue = max(targetHigh + 24.0, highest + max(12.0, highest * 0.08))

        val plotLeft = bounds.left
        val plotRight = bounds.right
        val plotTop = bounds.top
        val plotBottom = bounds.bottom

        fun y(value: Double): Float {
            val fraction = ((value - minValue) / (maxValue - minValue).coerceAtLeast(1.0))
                .coerceIn(0.0, 1.0)
            return (plotBottom - fraction * (plotBottom - plotTop)).toFloat()
        }

        fun x(timestamp: Long): Float {
            val fraction = ((timestamp - start).toDouble() / windowMs.toDouble())
                .coerceIn(0.0, 1.0)
            return (plotLeft + fraction * (plotRight - plotLeft)).toFloat()
        }

        paint.style = Paint.Style.FILL
        if (excursion == RangeExcursion.HIGH) {
            paint.color = graphColor(SugarliciousColorRole.RANGE_HIGH)
            canvas.drawRect(
                plotLeft,
                plotTop,
                plotRight,
                y(targetHigh),
                paint,
            )
        }
        paint.color = graphColor(SugarliciousColorRole.RANGE_IN_RANGE)
        canvas.drawRect(
            plotLeft,
            y(targetHigh),
            plotRight,
            y(targetLow),
            paint,
        )
        if (excursion == RangeExcursion.LOW) {
            paint.color = graphColor(SugarliciousColorRole.RANGE_LOW)
            canvas.drawRect(
                plotLeft,
                y(targetLow),
                plotRight,
                plotBottom,
                paint,
            )
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, height / displayHeightDp)
        paint.color = graphColor(SugarliciousColorRole.GRAPH_DIVIDER)
        canvas.drawLine(plotLeft, y(targetHigh), plotRight, y(targetHigh), paint)
        canvas.drawLine(plotLeft, y(targetLow), plotRight, y(targetLow), paint)

        val dotRadiusDp = preferences.getFloat(
            PersistentBridgeService.PREFERENCE_NOTIFICATION_DOT_RADIUS,
            preferences.getFloat("cgm.dotRadiusDp", 2.4f),
        ).coerceIn(1.5f, 6.0f)
        val outlineEnabled = preferences.getBoolean(
            PersistentBridgeService.PREFERENCE_NOTIFICATION_DOT_OUTLINE_ENABLED,
            preferences.getBoolean("cgm.dotOutlineEnabled", true),
        )
        val outlineWidthDp = preferences.getFloat(
            PersistentBridgeService.PREFERENCE_NOTIFICATION_DOT_OUTLINE_WIDTH,
            preferences.getFloat("cgm.dotOutlineWidthDp", 0.95f),
        ).coerceIn(0.25f, 3.0f)
        val outlineRadius = (dotRadiusDp + outlineWidthDp) * dpToBitmap
        val dotRadius = dotRadiusDp * dpToBitmap
        val currentExtra = 0.18f * dpToBitmap

        points.forEachIndexed { index, point ->
            val px = x(point.key)
            val py = y(point.value)
            val current = index == points.lastIndex
            if (outlineEnabled) {
                paint.style = Paint.Style.FILL
                paint.color = graphColor(SugarliciousColorRole.GRAPH_CURRENT_OUTLINE)
                canvas.drawCircle(
                    px,
                    py,
                    outlineRadius + if (current) currentExtra else 0f,
                    paint,
                )
            }

            paint.style = Paint.Style.FILL
            paint.color = when {
                point.value < targetLow -> graphColor(SugarliciousColorRole.CGM_DOT_LOW)
                point.value > targetHigh -> graphColor(SugarliciousColorRole.CGM_DOT_HIGH)
                else -> graphColor(SugarliciousColorRole.CGM_DOT_IN_RANGE)
            }
            canvas.drawCircle(
                px,
                py,
                dotRadius + if (current) currentExtra else 0f,
                paint,
            )
        }

        return bitmap
    }
}
