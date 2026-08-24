package app.aapswear.g7watch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.model.Trend
import app.aapswear.model.TrendVisuals
import java.util.Locale

class G7WatchActivity : Activity() {
    private val appearanceStore by lazy { G7AppearanceStore(this) }
    private var readingObserverRegistered = false
    private var activePalette: G7AppearancePalette? = null
    private var screenBuilt = false
    private lateinit var scrollView: ScrollView
    private lateinit var statusHost: LinearLayout
    private lateinit var glucoseHost: LinearLayout
    private lateinit var graphView: G7CollectorGraphView
    private lateinit var graphPeriodPill: TextView
    private val readingObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (!isFinishing && !isDestroyed) refreshLiveContent()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMissingPermissions()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshLiveContent()
    }

    override fun onResume() {
        super.onResume()
        registerReadingObserver()
        refreshScreen()
    }

    override fun onPause() {
        unregisterReadingObserver()
        super.onPause()
    }

    private fun registerReadingObserver() {
        if (readingObserverRegistered) return
        contentResolver.registerContentObserver(
            G7ReadingProvider.CONTENT_URI,
            true,
            readingObserver,
        )
        readingObserverRegistered = true
    }

    private fun unregisterReadingObserver() {
        if (!readingObserverRegistered) return
        contentResolver.unregisterContentObserver(readingObserver)
        readingObserverRegistered = false
    }

    private fun refreshScreen() {
        val palette = appearanceStore.load()
        if (!screenBuilt || palette != activePalette) buildScreen(palette) else refreshLiveContent()
    }

    private fun buildScreen(palette: G7AppearancePalette) {
        val previousScrollY = if (screenBuilt) scrollView.scrollY else 0
        activePalette = palette
        val background = palette.argb(G7AppearanceRole.MENU_BACKGROUND)
        window.statusBarColor = background
        window.navigationBarColor = background

        val state = G7SensorStateStore(this).read()
        val credentials = G7CredentialStore(this).read()
        val userStatus = deriveG7UserStatus(state, credentials != null)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(18.dp, 5.dp, 18.dp, 30.dp)
            setBackgroundColor(background)
        }

        content.addView(header(palette, userStatus))
        glucoseHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(glucoseHost, cardParams(top = 4))
        content.addView(graphTile(G7ReadingDatabase(this).query(limit = 300), palette), cardParams(top = 7))
        content.addView(pill("Systemstatus", PillStyle.SECONDARY, palette) {
            startActivity(Intent(this, G7SystemStatusActivity::class.java))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 10.dp
            gravity = Gravity.CENTER_HORIZONTAL
        })

        content.addView(TextView(this).apply {
            text = "⚙"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY))
            setOnClickListener { startActivity(Intent(this@G7WatchActivity, G7AppearanceActivity::class.java)) }
            contentDescription = "Einstellungen"
        }, LinearLayout.LayoutParams(48.dp, 48.dp).apply {
            topMargin = 3.dp
            gravity = Gravity.CENTER_HORIZONTAL
        })

        content.addView(label("G7 Direct to Watch", 15f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true).apply {
            setPadding(3.dp, 15.dp, 3.dp, 0)
        })
        content.addView(label("by Sugarlicious", 9f, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY), true).apply {
            letterSpacing = 0.08f
            setPadding(3.dp, 1.dp, 3.dp, 0)
        })

        scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(background)
            addView(content)
        }
        setContentView(scrollView)
        screenBuilt = true
        refreshLiveContent(preserveScroll = false)
        if (previousScrollY > 0) scrollView.post { scrollView.scrollTo(0, previousScrollY) }
    }

    private fun header(palette: G7AppearancePalette, status: G7UserStatus) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(ImageView(this@G7WatchActivity).apply {
            setImageResource(R.drawable.ic_g7_sensor)
            contentDescription = "G7 Sensor"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(54.dp, 54.dp))
        statusHost = LinearLayout(this@G7WatchActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(statusPill(status, palette))
        }
        addView(statusHost)
    }

    private fun glucoseTile(
        reading: CgmReading?,
        userStatus: G7UserStatus,
        palette: G7AppearancePalette,
    ): LinearLayout {
        val now = System.currentTimeMillis()
        val ageMs = reading?.timestampEpochMs?.let { (now - it).coerceAtLeast(0L) }
        val statusColor = glucoseStatusColor(reading, userStatus, ageMs, palette)
        val valueColor = when {
            reading == null -> palette.argb(G7AppearanceRole.GLUCOSE_NO_SOURCE)
            reading.status != CgmReadingStatus.VALID -> palette.argb(G7AppearanceRole.GLUCOSE_ERROR)
            ageMs != null && ageMs >= G7GraphPolicy.STALE_AFTER_MS -> palette.argb(G7AppearanceRole.GLUCOSE_STALE)
            ageMs != null && ageMs >= 6L * 60_000L -> palette.argb(G7AppearanceRole.GLUCOSE_DELAYED)
            reading.glucoseMgDl < 80.0 -> palette.argb(G7AppearanceRole.GLUCOSE_LOW)
            reading.glucoseMgDl > 160.0 -> palette.argb(G7AppearanceRole.GLUCOSE_HIGH)
            else -> palette.argb(G7AppearanceRole.GLUCOSE_IN_RANGE)
        }
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 13.dp, 16.dp, 12.dp)
            background = rounded(withAlpha(statusColor, 32), statusColor, 24f)
        }
        tile.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(label(reading?.glucoseMgDl?.toInt()?.toString() ?: "—", 35f, valueColor, true))
            addView(trendIndicator(reading?.trend ?: Trend.UNKNOWN, palette.argb(G7AppearanceRole.GLUCOSE_TREND)))
        })
        tile.addView(label("mg/dL", 9f, palette.argb(G7AppearanceRole.GLUCOSE_DELTA), true))
        val delta = reading?.deltaMgDl?.let { signedDelta(it) } ?: "—"
        val age = ageMs?.let(::formatReadingAge) ?: "kein Wert"
        tile.addView(label("Δ $delta · $age", 11f, palette.argb(G7AppearanceRole.GLUCOSE_DELTA), true))
        return tile
    }

    private fun refreshLiveContent(preserveScroll: Boolean = true) {
        if (!screenBuilt) {
            refreshScreen()
            return
        }
        val palette = appearanceStore.load()
        if (palette != activePalette) {
            buildScreen(palette)
            return
        }
        preserveScrollPosition(preserveScroll) {
            val state = G7SensorStateStore(this).read()
            val credentials = G7CredentialStore(this).read()
            val userStatus = deriveG7UserStatus(state, credentials != null)

            statusHost.removeAllViews()
            statusHost.addView(statusPill(userStatus, palette))
            glucoseHost.removeAllViews()
            glucoseHost.addView(glucoseTile(state.lastReading, userStatus, palette))
            updateGraphOnly(preserveScroll = false)
        }
    }

    private fun updateGraphOnly(preserveScroll: Boolean = true) {
        if (!screenBuilt) return
        val palette = activePalette ?: return
        preserveScrollPosition(preserveScroll) {
            val hours = appearanceStore.graphHours()
            graphPeriodPill.text = "${hours}h"
            graphView.bind(
                readings = G7ReadingDatabase(this).query(limit = 300),
                palette = palette,
                graphHours = hours,
            )
        }
    }

    private inline fun preserveScrollPosition(enabled: Boolean, update: () -> Unit) {
        val previousScrollY = if (enabled && screenBuilt) scrollView.scrollY else 0
        update()
        if (enabled && screenBuilt) scrollView.post { scrollView.scrollTo(0, previousScrollY) }
    }

    private fun graphTile(readings: List<CgmReading>, palette: G7AppearancePalette): FrameLayout {
        val hours = appearanceStore.graphHours()
        return FrameLayout(this).apply {
            graphView = G7CollectorGraphView(this@G7WatchActivity).apply {
                bind(
                    readings = readings,
                    palette = palette,
                    graphHours = hours,
                )
            }
            addView(graphView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150.dp))

            graphPeriodPill = pill(
                textValue = "${hours}h",
                style = PillStyle.SECONDARY,
                palette = palette,
            ) {
                appearanceStore.nextGraphHours()
                updateGraphOnly()
            }
            addView(
                graphPeriodPill,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 34.dp, Gravity.TOP or Gravity.START).apply {
                    topMargin = 8.dp
                    marginStart = 8.dp
                },
            )
        }
    }

    private fun statusPill(status: G7UserStatus, palette: G7AppearancePalette): TextView {
        val color = statusColor(status, palette)
        val marker = if (status.level == G7UserStatusLevel.OFF) "○" else "●"
        return label("$marker  ${status.title.uppercase(Locale.GERMANY)}", 10f, color, true).apply {
            background = rounded(withAlpha(color, 36), color, 999f)
            setPadding(13.dp, 6.dp, 13.dp, 6.dp)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 7.dp
                bottomMargin = 3.dp
            }
        }
    }

    private fun statusColor(status: G7UserStatus, palette: G7AppearancePalette): Int = when (status.level) {
        G7UserStatusLevel.OK, G7UserStatusLevel.WORKING -> palette.argb(G7AppearanceRole.MENU_PRIMARY)
        G7UserStatusLevel.ATTENTION -> palette.argb(G7AppearanceRole.GLUCOSE_STALE)
        G7UserStatusLevel.ERROR -> palette.argb(G7AppearanceRole.GLUCOSE_ERROR)
        G7UserStatusLevel.OFF -> palette.argb(G7AppearanceRole.GLUCOSE_NO_SOURCE)
    }

    private fun glucoseStatusColor(
        reading: CgmReading?,
        status: G7UserStatus,
        ageMs: Long?,
        palette: G7AppearancePalette,
    ): Int = when {
        status.level == G7UserStatusLevel.ERROR -> palette.argb(G7AppearanceRole.GLUCOSE_ERROR)
        reading == null -> palette.argb(G7AppearanceRole.GLUCOSE_NO_SOURCE)
        reading.status != CgmReadingStatus.VALID -> palette.argb(G7AppearanceRole.GLUCOSE_ERROR)
        ageMs != null && ageMs >= G7GraphPolicy.STALE_AFTER_MS -> palette.argb(G7AppearanceRole.GLUCOSE_STALE)
        ageMs != null && ageMs >= 6L * 60_000L -> palette.argb(G7AppearanceRole.GLUCOSE_DELAYED)
        else -> palette.argb(G7AppearanceRole.MENU_PRIMARY)
    }

    private enum class PillStyle { PRIMARY, SECONDARY, DANGER }

    private fun pill(
        textValue: String,
        style: PillStyle,
        palette: G7AppearancePalette,
        action: () -> Unit,
    ) = TextView(this).apply {
        text = textValue
        textSize = 11f
        gravity = Gravity.CENTER
        minHeight = 40.dp
        setPadding(13.dp, 8.dp, 13.dp, 8.dp)
        setTypeface(typeface, Typeface.BOLD)
        val (fill, textColor, stroke) = when (style) {
            PillStyle.PRIMARY -> Triple(palette.argb(G7AppearanceRole.MENU_PRIMARY), palette.argb(G7AppearanceRole.MENU_BACKGROUND), palette.argb(G7AppearanceRole.MENU_PRIMARY))
            PillStyle.SECONDARY -> Triple(palette.argb(G7AppearanceRole.MENU_SURFACE), palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), palette.argb(G7AppearanceRole.MENU_BORDER))
            PillStyle.DANGER -> Triple(withAlpha(palette.argb(G7AppearanceRole.GLUCOSE_ERROR), 36), palette.argb(G7AppearanceRole.GLUCOSE_ERROR), palette.argb(G7AppearanceRole.GLUCOSE_ERROR))
        }
        setTextColor(textColor)
        background = rounded(fill, stroke, 999f)
        setOnClickListener { action() }
    }

    private fun label(textValue: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = textValue
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(3.dp, 3.dp, 3.dp, 3.dp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(1.dp, stroke)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    private fun cardParams(top: Int = 8) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        setMargins(0, top.dp, 0, 0)
    }

    private fun requestMissingPermissions() {
        val missing = buildList {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.BLUETOOTH_SCAN)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) refreshLiveContent()
    }

    override fun onDestroy() {
        unregisterReadingObserver()
        super.onDestroy()
    }

    private fun trendIndicator(trend: Trend, color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(10.dp, 0, 0, 0)
        TrendVisuals.spec(trend)?.let { spec ->
            repeat(spec.arrowCount) { index ->
                addView(ImageView(this@G7WatchActivity).apply {
                    setImageResource(R.drawable.ic_trend_arrow)
                    setColorFilter(color, PorterDuff.Mode.SRC_IN)
                    rotation = spec.rotationDegrees
                    contentDescription = "Trend ${trend.name}"
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, LinearLayout.LayoutParams(27.dp, 27.dp).apply {
                    if (index > 0) marginStart = 1.dp
                })
            }
        }
    }

    private fun signedDelta(value: Double): String = String.format(Locale.US, "%+.0f", value)

    private fun formatReadingAge(ageMs: Long): String {
        val seconds = ageMs / 1_000L
        return if (seconds < 90L) "$seconds s" else "${seconds / 60L} min"
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        const val PERMISSION_REQUEST = 7
    }
}
