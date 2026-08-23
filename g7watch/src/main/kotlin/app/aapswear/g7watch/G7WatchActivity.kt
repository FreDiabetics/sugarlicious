package app.aapswear.g7watch

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
    private var batteryRequestPending = false
    private var showPairingEditor = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMissingPermissions()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    override fun onResume() {
        super.onResume()
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
        render()
    }

    private fun render() {
        val appearanceStore = G7AppearanceStore(this)
        val palette = appearanceStore.load()
        val background = palette.argb(G7AppearanceRole.MENU_BACKGROUND)
        window.statusBarColor = background
        window.navigationBarColor = background

        val state = G7SensorStateStore(this).read()
        val credentials = G7CredentialStore(this).read()
        val userStatus = deriveG7UserStatus(state, credentials != null)
        val reading = state.lastReading
        val readings = G7ReadingDatabase(this).query(limit = 300)
        if (state.sensor == null) showPairingEditor = true

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(18.dp, 14.dp, 18.dp, 30.dp)
            setBackgroundColor(background)
        }

        content.addView(topBar(palette))
        content.addView(header(palette, userStatus))
        content.addView(glucoseTile(reading, userStatus, palette), cardParams())
        content.addView(graphTile(readings, appearanceStore, palette), cardParams(top = 7))
        content.addView(liveCollectorStatusTile(state, userStatus, palette), cardParams(top = 7))
        content.addView(systemStatusTile(state, credentials != null, palette), cardParams(top = 7))

        if (state.sensor != null || credentials != null || reading != null) {
            content.addView(sensorDocumentationTile(state.sensor, reading, credentials, palette), cardParams(top = 7))
        }

        content.addView(
            label(
                "Nur einen direkten G7-Collector gleichzeitig verwenden. Juggluco oder xDrip vorher beenden.",
                9f,
                palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY),
            ).apply {
                gravity = Gravity.CENTER
                setPadding(8.dp, 12.dp, 8.dp, 0)
            },
        )

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(background)
            addView(content)
        })
    }

    private fun topBar(palette: G7AppearancePalette) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@G7WatchActivity).apply {
            text = "←"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY))
            setOnClickListener { finish() }
            contentDescription = "Zurück"
        }, LinearLayout.LayoutParams(48.dp, 48.dp))
        addView(View(this@G7WatchActivity), LinearLayout.LayoutParams(0, 1, 1f))
        addView(TextView(this@G7WatchActivity).apply {
            text = "⚙"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY))
            setOnClickListener { startActivity(Intent(this@G7WatchActivity, G7AppearanceActivity::class.java)) }
            contentDescription = "Darstellung"
        }, LinearLayout.LayoutParams(48.dp, 48.dp))
    }

    private fun header(palette: G7AppearancePalette, status: G7UserStatus) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(ImageView(this@G7WatchActivity).apply {
            setImageResource(R.drawable.ic_g7_sensor)
            contentDescription = "G7 Sensor"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(70.dp, 70.dp))
        addView(label("G7 Direct to Watch", 20f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true))
        addView(label("by Sugarlicious", 10f, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY), true).apply { letterSpacing = 0.08f })
        addView(statusPill(status, palette))
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

    private fun graphTile(
        readings: List<CgmReading>,
        appearanceStore: G7AppearanceStore,
        palette: G7AppearancePalette,
    ): FrameLayout {
        val hours = appearanceStore.graphHours()
        return FrameLayout(this).apply {
            addView(G7CollectorGraphView(this@G7WatchActivity).apply {
                bind(
                    readings = readings,
                    palette = palette,
                    graphHours = hours,
                )
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150.dp))

            addView(
                pill(
                    textValue = "${hours}h",
                    style = PillStyle.SECONDARY,
                    palette = palette,
                ) {
                    appearanceStore.nextGraphHours()
                    render()
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 34.dp, Gravity.TOP or Gravity.END).apply {
                    topMargin = 8.dp
                    marginEnd = 8.dp
                },
            )
        }
    }

    private fun liveCollectorStatusTile(
        state: app.aapswear.g7.G7PersistedState,
        userStatus: G7UserStatus,
        palette: G7AppearancePalette,
    ) = card(palette).apply {
        addView(sectionLabel("LIVE COLLECTORSTATUS", palette))
        addView(valueRow("Zustand", userStatus.title, palette))
        addView(valueRow("Phase", userStatus.phase, palette))
        addView(valueRow("Status", userStatus.status, palette))
        addView(valueRow("Verbindung", state.connectionState.name, palette))
        addView(divider(palette))
        addView(label(userStatus.description, 10f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY)).apply { gravity = Gravity.START })
        if (userStatus.action.isNotBlank()) {
            addView(label("Was tun: ${userStatus.action}", 9f, if (userStatus.level == G7UserStatusLevel.ERROR) palette.argb(G7AppearanceRole.GLUCOSE_ERROR) else palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY), userStatus.level == G7UserStatusLevel.ERROR).apply { gravity = Gravity.START })
        }
        addView(label("Intern: ${state.protocolState.name} / ${state.sessionState.name}", 8f, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY)).apply { gravity = Gravity.START })
    }

    private fun systemStatusTile(
        state: app.aapswear.g7.G7PersistedState,
        credentialsPresent: Boolean,
        palette: G7AppearancePalette,
    ) = card(palette).apply {
        addView(sectionLabel("SYSTEMSTATUS", palette))
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
        addView(valueRow("Sensorcode", if (credentialsPresent) "Gespeichert" else "Fehlt", palette))
        addView(divider(palette))

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
            render()
        }, buttonParams())

        if (showPairingEditor || state.sensor == null) {
            addView(pairingEditor(palette), buttonParams(top = 7))
        }

        addView(pill("Farben & Darstellung", PillStyle.SECONDARY, palette) {
            startActivity(Intent(this@G7WatchActivity, G7AppearanceActivity::class.java))
        }, buttonParams())

        addView(pill(
            if (state.collectorEnabled) "Collector stoppen" else "Collector starten",
            if (state.collectorEnabled) PillStyle.DANGER else PillStyle.PRIMARY,
            palette,
        ) {
            if (state.collectorEnabled) G7CollectorService.stop(this@G7WatchActivity) else G7CollectorService.start(this@G7WatchActivity)
            postDelayed({ render() }, 350L)
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
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
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
            postDelayed({ render() }, 350L)
        }, buttonParams(top = 7))
    }

    private fun sensorDocumentationTile(
        sensor: G7Sensor?,
        reading: CgmReading?,
        credentials: G7CredentialStore.StoredCredentials?,
        palette: G7AppearancePalette,
    ) = card(palette).apply {
        addView(sectionLabel("SENSOR-DOKUMENTATION", palette))
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
        if (requestCode == PERMISSION_REQUEST) render()
    }

    private fun isBatteryUnrestricted(): Boolean = G7BackgroundAccess.isBatteryUnrestricted(this)

    private fun canScheduleExactReconnects(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun requestBatteryExemption() {
        if (G7BackgroundAccess.isBatteryUnrestricted(this)) {
            Toast.makeText(this, "Dauerbetrieb ist bereits uneingeschränkt", Toast.LENGTH_SHORT).show()
            render()
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
