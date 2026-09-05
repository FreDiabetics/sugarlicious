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
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.g7.G7Sensor
import app.aapswear.g7.G7SessionManager
import app.aapswear.g7.G7SetupPayload
import app.aapswear.model.Trend
import app.aapswear.model.TrendVisuals
import app.aapswear.model.CgmQuality
import app.aapswear.model.CgmRangeClass
import app.aapswear.model.cgmBoundaryDisplay
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.WearGlucoseCardInput
import app.aapswear.model.WearGlucoseCardStyle
import app.aapswear.model.GlucoseTrendSizing
import app.aapswear.model.wearGlucoseCardPresentation
import app.aapswear.uishared.TrendDrawableResources
import java.util.Locale

internal fun hasUsableCollectorSession(reading: CgmReading?, sensorId: String?): Boolean =
    reading != null && reading.status == CgmReadingStatus.VALID && reading.sensorId == sensorId

class G7WatchActivity : Activity() {
    private val appearanceStore by lazy { G7AppearanceStore(this) }
    private var readingObserverRegistered = false
    private var activePalette: G7AppearancePalette? = null
    private var screenBuilt = false
    private var pairingGateVisible = false
    private val pairingRefresh = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed && pairingGateVisible) {
                refreshScreen()
                mainHandler.postDelayed(this, 1_000L)
            }
        }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var scrollView: ScrollView
    private lateinit var statusHost: LinearLayout
    private lateinit var glucoseHost: LinearLayout
    private lateinit var graphView: G7CollectorGraphView
    private lateinit var graphPeriodPill: TextView
    private val readingObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (!isFinishing && !isDestroyed) refreshScreen()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        G7RuntimeReconciler.reconcile(this, G7RuntimeEntryPoint.WATCH_APP)
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
        mainHandler.removeCallbacks(pairingRefresh)
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
        val state = G7SensorStateStore(this).read()
        if (!hasUsableCollectorSession(state.lastReading, state.sensor?.sensorId)) {
            buildPairingGate(palette, state.collectorEnabled, state.lastError?.safeMessage)
        } else if (!screenBuilt || pairingGateVisible || palette != activePalette) {
            pairingGateVisible = false
            mainHandler.removeCallbacks(pairingRefresh)
            buildScreen(palette)
        } else refreshLiveContent()
    }

    private fun buildPairingGate(palette: G7AppearancePalette, pairingStarted: Boolean, error: String?) {
        pairingGateVisible = true
        screenBuilt = false
        activePalette = palette
        val background = palette.argb(G7AppearanceRole.MENU_BACKGROUND)
        window.statusBarColor = background
        window.navigationBarColor = background
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(26.dp, 24.dp, 26.dp, 36.dp)
            setBackgroundColor(background)
        }
        content.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_g7_sensor)
            contentDescription = "Sensor koppeln"
        }, LinearLayout.LayoutParams(70.dp, 70.dp).apply { gravity = Gravity.CENTER_HORIZONTAL })
        content.addView(label("Sensor koppeln", 19f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true))
        content.addView(label(
            if (pairingStarted) "Sensor wird gesucht und sicher gekoppelt …" else "Vierstelligen Kopplungscode vom Sensor eingeben",
            12f, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY), false,
        ).apply { setPadding(4.dp, 10.dp, 4.dp, 12.dp) })
        if (!pairingStarted) {
            val code = EditText(this).apply {
                hint = "0000"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(android.text.InputFilter.LengthFilter(4))
                gravity = Gravity.CENTER
                textSize = 24f
                setTextColor(palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY))
                setHintTextColor(palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY))
                contentDescription = "Vierstelliger G7 Kopplungscode"
            }
            content.addView(code, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 58.dp))
            content.addView(pill("Koppeln", PillStyle.PRIMARY, palette) {
                val entered = code.text?.toString().orEmpty()
                if (entered.length != 4 || entered.any { !it.isDigit() }) {
                    code.error = "Bitte genau vier Ziffern eingeben"
                    return@pill
                }
                val sensorId = "G7-${java.util.UUID.randomUUID().toString().take(8)}"
                G7CredentialStore(this).saveSetup(G7SetupPayload(entered, null, null))
                val prepared = G7SessionManager(G7SensorStateStore(this).read()).prepareInitialSetup(
                    G7Sensor(sensorId = sensorId, sessionId = sensorId, deviceName = "Dexcom G7"),
                )
                G7SensorStateStore(this).save(prepared)
                G7CollectorService.start(this)
                refreshScreen()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 10.dp; gravity = Gravity.CENTER_HORIZONTAL
            })
        } else {
            content.addView(label(error ?: "Die Android-Kopplungsabfrage bitte auf der Uhr bestätigen.", 11f,
                if (error == null) palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY) else palette.argb(G7AppearanceRole.GLUCOSE_ERROR)))
            content.addView(pill("Erneut versuchen", PillStyle.SECONDARY, palette) {
                G7CollectorService.restart(this)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12.dp; gravity = Gravity.CENTER_HORIZONTAL
            })
        }
        setContentView(G7EdgeFadeScrollView(this).apply { isFillViewport = true; addView(content) }.applyG7EdgeFade())
        mainHandler.removeCallbacks(pairingRefresh)
        mainHandler.postDelayed(pairingRefresh, 1_000L)
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

        content.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            setColorFilter(palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY), PorterDuff.Mode.SRC_IN)
            setOnClickListener { startActivity(Intent(this@G7WatchActivity, G7SettingsActivity::class.java)) }
            contentDescription = "Einstellungen"
        }, LinearLayout.LayoutParams(48.dp, 48.dp).apply {
            topMargin = 3.dp
            gravity = Gravity.CENTER_HORIZONTAL
        })

        content.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_g7_sensor)
            contentDescription = "Direct to Watch"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(54.dp, 54.dp).apply {
            topMargin = 9.dp
            gravity = Gravity.CENTER_HORIZONTAL
        })
        content.addView(label("Direct to Watch", 15f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true).apply {
            setPadding(3.dp, 2.dp, 3.dp, 0)
        })
        content.addView(label("by Sugarlicious", 9f, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY), true).apply {
            letterSpacing = 0.08f
            setPadding(3.dp, 1.dp, 3.dp, 0)
        })

        scrollView = G7EdgeFadeScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(background)
            addView(content)
        }.applyG7EdgeFade()
        setContentView(scrollView)
        screenBuilt = true
        refreshLiveContent(preserveScroll = false)
        if (previousScrollY > 0) scrollView.post { scrollView.scrollTo(0, previousScrollY) }
    }

    private fun header(palette: G7AppearancePalette, status: G7UserStatus) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        statusHost = LinearLayout(this@G7WatchActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(statusPill(status, palette))
        }
        addView(statusHost)
    }

    private fun glucoseTile(
        reading: CgmReading?,
        palette: G7AppearancePalette,
    ): LinearLayout {
        val now = System.currentTimeMillis()
        val basePresentation = wearGlucoseCardPresentation(
            WearGlucoseCardInput(
                valueMgDl = reading?.glucoseMgDl,
                displayUnit = GlucoseUnit.MG_DL,
                deltaMgDl = reading?.deltaMgDl,
                trend = reading?.trend ?: Trend.UNKNOWN,
                measuredAtEpochMs = reading?.timestampEpochMs,
                quality = when (reading?.status) {
                    CgmReadingStatus.VALID -> CgmQuality.VALID
                    CgmReadingStatus.SENSOR_ERROR -> CgmQuality.SENSOR_ERROR
                    CgmReadingStatus.INVALID, null -> CgmQuality.INVALID
                },
                sourceLabel = "",
            ),
            G7GraphColorStore(this).readThresholds(),
            now,
        )
        val boundary = reading?.takeIf { it.status == CgmReadingStatus.VALID }?.glucoseMgDl.let(::cgmBoundaryDisplay)
        val presentation = if (boundary == null) basePresentation else basePresentation.copy(
            value = boundary.label,
            trend = null,
            primaryMeta = "",
        )
        val valueColor = when {
            !presentation.displayable -> palette.argb(G7AppearanceRole.GLUCOSE_NO_SOURCE)
            presentation.rangeClass == CgmRangeClass.VERY_LOW -> palette.argb(G7AppearanceRole.GLUCOSE_VERY_LOW)
            presentation.rangeClass == CgmRangeClass.LOW -> palette.argb(G7AppearanceRole.GLUCOSE_LOW)
            presentation.rangeClass == CgmRangeClass.VERY_HIGH -> palette.argb(G7AppearanceRole.GLUCOSE_VERY_HIGH)
            presentation.rangeClass == CgmRangeClass.HIGH -> palette.argb(G7AppearanceRole.GLUCOSE_HIGH)
            else -> palette.argb(G7AppearanceRole.GLUCOSE_IN_RANGE)
        }
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = WearGlucoseCardStyle.CARD_HEIGHT_DP.dp
            setPadding(
                WearGlucoseCardStyle.HORIZONTAL_PADDING_DP.dp,
                WearGlucoseCardStyle.VERTICAL_PADDING_DP.dp,
                WearGlucoseCardStyle.HORIZONTAL_PADDING_DP.dp,
                WearGlucoseCardStyle.VERTICAL_PADDING_DP.dp,
            )
            background = rounded(
                palette.argb(G7AppearanceRole.MENU_SURFACE),
                palette.argb(G7AppearanceRole.MENU_BORDER),
                WearGlucoseCardStyle.CARD_RADIUS_DP,
            )
        }
        tile.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(label(presentation.value, WearGlucoseCardStyle.VALUE_TEXT_SP * GlucoseTrendSizing.scaleFactor(appearanceStore.glucoseScalePercent()), valueColor, true))
            presentation.trend?.let { addView(trendIndicator(it, valueColor)) }
        })
        tile.addView(label(presentation.primaryMeta, WearGlucoseCardStyle.META_TEXT_SP, palette.argb(G7AppearanceRole.GLUCOSE_DELTA), true))
        if (presentation.secondaryMeta.isNotBlank()) {
            tile.addView(label(presentation.secondaryMeta, WearGlucoseCardStyle.META_TEXT_SP, palette.argb(G7AppearanceRole.GLUCOSE_DELTA), true))
        }
        return tile
    }

    private fun refreshLiveContent(preserveScroll: Boolean = true) {
        if (pairingGateVisible) {
            refreshScreen()
            return
        }
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
            glucoseHost.addView(glucoseTile(state.lastReading, palette))
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
        setPadding(WearGlucoseCardStyle.TREND_GAP_DP.dp, 0, 0, 0)
        TrendVisuals.spec(trend)?.let { spec ->
            val style = appearanceStore.trendArrowStyle().renderSpec()
            val height = (WearGlucoseCardStyle.TREND_SIZE_DP * style.scale).toInt().dp
            val width = (WearGlucoseCardStyle.TREND_SIZE_DP * style.scale * spec.aspectRatio).toInt().dp
            addView(android.widget.FrameLayout(this@G7WatchActivity).apply {
                fun arrow(tint: Int, x: Float = 0f, y: Float = 0f) = ImageView(this@G7WatchActivity).apply {
                    setImageResource(TrendDrawableResources.forAsset(spec.asset))
                    setColorFilter(tint, PorterDuff.Mode.SRC_IN)
                    translationX = x
                    translationY = y
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                if (style.outlineThicknessDp > 0f) {
                    val offset = style.outlineThicknessDp * resources.displayMetrics.density
                    listOf(-offset to 0f, offset to 0f, 0f to -offset, 0f to offset).forEach { (x, y) ->
                        addView(arrow(style.outlineColor, x, y), android.widget.FrameLayout.LayoutParams(width, height))
                    }
                }
                addView(arrow(style.fillColor).apply { contentDescription = "Trend ${trend.name}" }, android.widget.FrameLayout.LayoutParams(width, height))
                contentDescription = "Trend ${trend.name}"
            }, LinearLayout.LayoutParams(width, height))
        }
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
