package app.aapswear.g7watch

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.g7.G7Sensor
import app.aapswear.g7.G7SessionManager
import app.aapswear.g7.G7SetupPayload
import app.aapswear.model.DiagnosticSeverity
import app.aapswear.model.Trend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class G7WatchActivity : Activity() {
    private val diagnosticScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val appearanceStore by lazy { G7AppearanceStore(this) }
    private var batteryRequestPending = false
    private var showPairingEditor = false
    private var readingObserverRegistered = false
    private var activePalette: G7AppearancePalette? = null
    private var screenBuilt = false
    private lateinit var scrollView: ScrollView
    private lateinit var statusHost: LinearLayout
    private lateinit var glucoseHost: LinearLayout
    private lateinit var systemStatusCard: LinearLayout
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
        if (batteryRequestPending) {
            batteryRequestPending = false
            val unrestricted = G7BackgroundAccess.isBatteryUnrestricted(this)
            if (unrestricted) {
                Toast.makeText(this, "Dauerbetrieb ist uneingeschränkt", Toast.LENGTH_SHORT).show()
                recordBackgroundDiagnostic(
                    "G7-BG-200",
                    "Battery optimization exemption granted for G7 Watch Collector",
                    DiagnosticSeverity.INFO,
                )
            } else {
                Toast.makeText(
                    this,
                    "Dauerbetrieb nicht freigegeben – Akkuoptimierung ist weiterhin aktiv",
                    Toast.LENGTH_LONG,
                ).show()
                recordBackgroundDiagnostic(
                    "G7-BG-403",
                    "Battery optimization exemption was not granted for G7 Watch Collector",
                    DiagnosticSeverity.WARNING,
                )
            }
        }
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
        if (state.sensor == null) showPairingEditor = true

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
        systemStatusCard = card(palette)
        content.addView(systemStatusCard, cardParams(top = 7))

        content.addView(pill("Einstellungen", PillStyle.SECONDARY, palette) {
            startActivity(Intent(this, G7AppearanceActivity::class.java))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 14.dp
            gravity = Gravity.CENTER_HORIZONTAL
        })

        content.addView(label("G7 Direct to Watch", 15f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true).apply {
            setPadding(3.dp, 15.dp, 3.dp, 0)
        })
        content.addView(label("by Sugarlicious", 9f, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY), true).apply {
            letterSpacing = 0.08f
            setPadding(3.dp, 1.dp, 3.dp, 0)
        })

        content.addView(
            label(
                "Nur einen direkten G7-Collector gleichzeitig verwenden. Juggluco oder xDrip vorher beenden.",
                9f,
                palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY),
            ).apply {
                gravity = Gravity.CENTER
                setPadding(8.dp, 10.dp, 8.dp, 0)
            },
        )

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
        }, LinearLayout.LayoutParams(58.dp, 58.dp))
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
            addView(label(trendGlyph(reading?.trend ?: Trend.UNKNOWN), 31f, palette.argb(G7AppearanceRole.GLUCOSE_TREND), true).apply {
                setPadding(10.dp, 0, 0, 0)
            })
        })
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
            populateSystemStatus(systemStatusCard, state, userStatus, credentials, palette)
        }
    }

    private fun refreshSystemStatus() {
        if (!screenBuilt) return
        val palette = activePalette ?: return
        preserveScrollPosition(true) {
            val state = G7SensorStateStore(this).read()
            val credentials = G7CredentialStore(this).read()
            populateSystemStatus(systemStatusCard, state, deriveG7UserStatus(state, credentials != null), credentials, palette)
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

    private fun populateSystemStatus(
        target: LinearLayout,
        state: app.aapswear.g7.G7PersistedState,
        userStatus: G7UserStatus,
        credentials: G7CredentialStore.StoredCredentials?,
        palette: G7AppearancePalette,
    ) = target.apply {
        removeAllViews()
        addView(sectionLabel("SYSTEMSTATUS", palette))
        addView(sectionLabel("STATUSINFORMATIONEN", palette).apply { setPadding(3.dp, 9.dp, 3.dp, 3.dp) })
        addView(valueRow("Zustand", userStatus.title, palette))
        addView(valueRow("Phase", userStatus.phase, palette))
        addView(valueRow("Status", userStatus.status, palette))
        addView(valueRow("Verbindung", state.connectionState.name, palette))
        addView(label(userStatus.description, 10f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY)).apply {
            gravity = Gravity.START
            setPadding(3.dp, 6.dp, 3.dp, 3.dp)
        })
        if (userStatus.action.isNotBlank()) {
            addView(label("Was tun: ${userStatus.action}", 9f, if (userStatus.level == G7UserStatusLevel.ERROR) palette.argb(G7AppearanceRole.GLUCOSE_ERROR) else palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY), userStatus.level == G7UserStatusLevel.ERROR).apply { gravity = Gravity.START })
        }
        addView(divider(palette))
        addView(sectionLabel("TECHNISCHE DETAILS", palette))
        val nearbyAllowed =
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val notificationsAllowed =
            Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val batteryUnrestricted = isBatteryUnrestricted()
        val exactReconnectAllowed = canScheduleExactReconnects()

        addView(valueRow("Geräte in der Nähe", if (nearbyAllowed) "Erlaubt" else "Freigeben", palette))
        addView(valueRow("Benachrichtigungen", if (notificationsAllowed) "Erlaubt" else "Freigeben", palette))
        addView(valueRow("Akku-Optimierung", if (batteryUnrestricted) "Uneingeschränkt" else "Optimiert", palette))
        addView(valueRow("Präzise Sensor-Abfragen", if (exactReconnectAllowed) "Erlaubt" else "Freigeben", palette))
        addView(valueRow("Sensorcode", if (credentials != null) "Gespeichert" else "Fehlt", palette))
        addView(valueRow("Intern", "${state.protocolState.name} / ${state.sessionState.name}", palette))
        addSensorDocumentation(this, state.sensor, state.lastReading, credentials, palette)
        addView(divider(palette))
        addView(sectionLabel("AKTIONEN", palette))

        if (!nearbyAllowed || !notificationsAllowed) {
            addView(pill("Berechtigungen freigeben", PillStyle.SECONDARY, palette) { requestMissingPermissions() }, buttonParams())
        }
        if (!batteryUnrestricted) {
            addView(pill("Dauerbetrieb freigeben", PillStyle.SECONDARY, palette) { requestBatteryExemption() }, buttonParams())
        }
        if (!exactReconnectAllowed) {
            addView(pill("Präzise Sensor-Abfragen freigeben", PillStyle.SECONDARY, palette) { requestExactAlarmAccess() }, buttonParams())
        }

        addView(pill(if (state.sensor == null) "Sensor einrichten" else "Sensor neu koppeln", PillStyle.SECONDARY, palette) {
            showPairingEditor = !showPairingEditor
            refreshSystemStatus()
        }, buttonParams())

        if (showPairingEditor || state.sensor == null) {
            addView(pairingEditor(palette), buttonParams(top = 7))
        }

        addView(pill(
            if (state.collectorEnabled) "Collector stoppen" else "Collector starten",
            if (state.collectorEnabled) PillStyle.DANGER else PillStyle.PRIMARY,
            palette,
        ) {
            if (state.collectorEnabled) G7CollectorService.stop(this@G7WatchActivity) else G7CollectorService.start(this@G7WatchActivity)
            postDelayed({ refreshLiveContent() }, 350L)
        }, buttonParams())

        addView(label(
            "Der Collector scannt nur in begrenzten G7-Sensorfenstern. Verpasste einzelne Fenster werden automatisch zum nächsten 5-Minuten-Zyklus wiederholt.",
            8.5f,
            palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY),
        ).apply {
            gravity = Gravity.START
            setPadding(2.dp, 9.dp, 2.dp, 0)
        })
    }

    private fun pairingEditor(palette: G7AppearancePalette): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(9.dp, 9.dp, 9.dp, 9.dp)
        background = rounded(palette.argb(G7AppearanceRole.MENU_BACKGROUND), palette.argb(G7AppearanceRole.MENU_BORDER), 16f)
        addView(label("Vierstelliger Code vom G7-Applikator", 10f, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY), true).apply { gravity = Gravity.START })
        val codeInput = EditText(this@G7WatchActivity).apply {
            hint = "0000"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(4))
            setTextColor(palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY))
            setHintTextColor(palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY))
            textSize = 19f
            gravity = Gravity.CENTER
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)
            background = rounded(palette.argb(G7AppearanceRole.MENU_SURFACE), palette.argb(G7AppearanceRole.MENU_BORDER), 999f)
        }
        addView(codeInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 7.dp })
        addView(pill("Sensorcode speichern", PillStyle.PRIMARY, palette) {
            val code = codeInput.text?.toString().orEmpty()
            val payload = runCatching { G7SetupPayload(code) }.getOrNull()
            if (payload == null) {
                codeInput.error = "4 Ziffern erforderlich"
                return@pill
            }
            G7CredentialStore(this@G7WatchActivity).saveSetup(payload)
            val sensorId = "G7-${UUID.randomUUID().toString().take(8)}"
            val sensor = G7Sensor(sensorId, sensorId, "Dexcom G7")
            G7SensorStateStore(this@G7WatchActivity).save(
                G7SessionManager(G7SensorStateStore(this@G7WatchActivity).read()).prepareInitialSetup(sensor),
            )
            codeInput.text?.clear()
            showPairingEditor = false
            postDelayed({ refreshLiveContent() }, 350L)
        }, buttonParams(top = 7))
    }

    private fun addSensorDocumentation(
        target: LinearLayout,
        sensor: G7Sensor?,
        reading: CgmReading?,
        credentials: G7CredentialStore.StoredCredentials?,
        palette: G7AppearancePalette,
    ) = target.apply {
        if (sensor == null && reading == null && credentials == null) return@apply
        addView(sectionLabel("SENSOR-DOKUMENTATION", palette).apply { setPadding(3.dp, 8.dp, 3.dp, 3.dp) })
        addView(valueRow("Sensorcode", credentials?.pairingCode ?: "—", palette))
        addView(valueRow("GTIN", credentials?.gtin ?: "—", palette))
        addView(valueRow("Seriennummer", credentials?.sensorSerial ?: "—", palette))
        addView(valueRow("Sensor-ID", sensor?.sensorId ?: reading?.sensorId ?: "—", palette))
        addView(valueRow("Session-ID", sensor?.sessionId ?: reading?.sessionId ?: "—", palette))
        addView(valueRow("BLE-Name", sensor?.deviceName ?: "—", palette))
        addView(valueRow("BLE-Adresse", sensor?.deviceAddress ?: "—", palette))
        addView(valueRow("Sensorstatus", sensor?.state?.name ?: "—", palette))
        addView(valueRow("Abgeleiteter Start", formatTimestamp(sensor?.sensorStartEpochMs ?: reading?.sensorStartEpochMs), palette))
        addView(valueRow("Reguläres Ende", formatTimestamp(sensor?.sensorEndEpochMs ?: reading?.sensorEndEpochMs), palette))
        addView(valueRow("Kulanzende", formatTimestamp(sensor?.graceEndEpochMs ?: reading?.graceEndEpochMs), palette))
        addView(valueRow("Sequenz", reading?.sequenceNumber?.toString() ?: "—", palette))
        addView(valueRow("Trendrate", reading?.trendRateMgDlPerMinute?.let { String.format(Locale.US, "%.1f mg/dL/min", it) } ?: "—", palette))
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

    private fun card(palette: G7AppearancePalette) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(14.dp, 12.dp, 14.dp, 12.dp)
        background = rounded(
            palette.argb(G7AppearanceRole.MENU_SURFACE),
            palette.argb(G7AppearanceRole.MENU_BORDER),
            22f,
        )
    }

    private fun valueRow(title: String, value: String, palette: G7AppearancePalette) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(title, 10f, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY)).apply { gravity = Gravity.START }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(label(value, 10f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true).apply {
            gravity = Gravity.END
            maxLines = 3
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f))
    }

    private fun divider(palette: G7AppearancePalette) = View(this).apply {
        setBackgroundColor(palette.argb(G7AppearanceRole.MENU_BORDER))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dp).apply { setMargins(0, 7.dp, 0, 7.dp) }
    }

    private fun sectionLabel(value: String, palette: G7AppearancePalette) =
        label(value, 9.5f, palette.argb(G7AppearanceRole.MENU_PRIMARY), true).apply {
            gravity = Gravity.START
            letterSpacing = 0.10f
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

    private fun buttonParams(top: Int = 7) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
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

    private fun isBatteryUnrestricted(): Boolean = G7BackgroundAccess.isBatteryUnrestricted(this)

    private fun canScheduleExactReconnects(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun requestBatteryExemption() {
        if (G7BackgroundAccess.isBatteryUnrestricted(this)) {
            Toast.makeText(this, "Dauerbetrieb ist bereits uneingeschränkt", Toast.LENGTH_SHORT).show()
            refreshLiveContent()
            return
        }
        batteryRequestPending = true
        if (G7BackgroundAccess.openBatterySettings(this)) return
        batteryRequestPending = false
        Toast.makeText(this, "Akku-Einstellungen konnten auf dieser Watch nicht geöffnet werden", Toast.LENGTH_LONG).show()
        recordBackgroundDiagnostic(
            "G7-BG-404",
            "Battery optimization settings could not be opened for G7 Watch Collector",
            DiagnosticSeverity.ERROR,
        )
    }

    private fun recordBackgroundDiagnostic(code: String, message: String, severity: DiagnosticSeverity) {
        diagnosticScope.launch { applicationContext.recordG7Diagnostic(code, message, severity) }
    }

    private fun requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(Uri.parse("package:$packageName")))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) }
        }
    }

    override fun onDestroy() {
        unregisterReadingObserver()
        diagnosticScope.cancel()
        super.onDestroy()
    }

    private fun trendGlyph(trend: Trend): String = when (trend) {
        Trend.DOUBLE_UP -> "⇈"
        Trend.SINGLE_UP -> "↑"
        Trend.FORTY_FIVE_UP -> "↗"
        Trend.FLAT -> "→"
        Trend.FORTY_FIVE_DOWN -> "↘"
        Trend.SINGLE_DOWN -> "↓"
        Trend.DOUBLE_DOWN -> "⇊"
        Trend.UNKNOWN -> "·"
    }

    private fun signedDelta(value: Double): String = String.format(Locale.US, "%+.0f", value)

    private fun formatReadingAge(ageMs: Long): String {
        val seconds = ageMs / 1_000L
        return if (seconds < 90L) "$seconds s" else "${seconds / 60L} min"
    }

    private fun formatTimestamp(timestamp: Long?): String =
        timestamp?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "—"

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
