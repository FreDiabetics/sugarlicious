package app.aapswear.mobile

import android.graphics.drawable.GradientDrawable
import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.core.content.edit
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColorStore
import app.aapswear.mobile.ui.theme.SugarliciousColors
import app.aapswear.storage.TherapyStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val diagnostics by lazy { getSharedPreferences("diagnostics", MODE_PRIVATE) }
    private val uiPreferences by lazy { getSharedPreferences("dashboard_ui", MODE_PRIVATE) }
    private lateinit var content: LinearLayout
    private lateinit var scroll: DashboardScrollView
    private lateinit var factory: DashboardViewFactory
    private var state: app.aapswear.model.TherapyDisplayState? = null
    private var screen = DashboardScreen.OVERVIEW
    private var clockJob: Job? = null
    private var settingsSwipeStartX = 0f
    private var settingsSwipeStartY = 0f
    private var settingsSwipeTracking = false
    private val healthPermissionsLauncher = registerForActivityResult(HealthConnectIntegration.permissionContract) { granted ->
        scope.launch {
            if (granted.any { it in HealthConnectIntegration.recordPermissions }) {
                HealthConnectIntegration.schedule(applicationContext)
                val result = runCatching { HealthConnectIntegration.sync(applicationContext) }.getOrNull()
                SugarliciousWidgets.update(applicationContext)
                val message = when {
                    HealthConnectIntegration.glucoseWritePermission !in granted -> "Verbunden · BZ-Schreibrecht fehlt"
                    result?.glucoseExport?.state == HealthConnectExportState.SUCCESS -> "Verbunden · ${result.glucoseExport.acceptedCount} BZ-Werte übertragen"
                    result?.glucoseExport?.state == HealthConnectExportState.FAILED -> "Verbunden · BZ-Export ${result.glucoseExport.errorCode}"
                    else -> "Health Connect verbunden"
                }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@MainActivity, "Keine Health-Connect-Berechtigung erteilt", Toast.LENGTH_LONG).show()
            }
            refresh(forceSettingsRender = true)
        }
    }
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            PersistentBridgeService.refresh(this)
            if (::factory.isInitialized) refresh(forceSettingsRender = screen == DashboardScreen.SETTINGS)
        }
    private val settingsExportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        SettingsBackup.write(applicationContext, output)
                    } ?: error("Die Datei konnte nicht geöffnet werden")
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        result.fold(
                            onSuccess = { "Einstellungen wurden gesichert" },
                            onFailure = { "Sicherung fehlgeschlagen: ${it.message ?: "Dateifehler"}" },
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    private val settingsImportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        SettingsBackup.restore(applicationContext, input)
                    } ?: error("Die Datei konnte nicht geöffnet werden")
                }
                result.onSuccess {
                    runCatching { publishWatchConfig(applicationContext) }
                    runCatching { syncComplicationPreset(applicationContext, loadComplicationPreset(applicationContext)) }
                }
                withContext(Dispatchers.Main) {
                    result.onSuccess { restored ->
                        SugarliciousColors.apply(SugarliciousColorStore.load(uiPreferences))
                        PersistentBridgeService.refresh(this@MainActivity)
                        SugarliciousWidgets.update(applicationContext)
                        refresh(forceSettingsRender = true)
                        Toast.makeText(
                            this@MainActivity,
                            "${restored.valueCount} Einstellungen wurden wiederhergestellt",
                            Toast.LENGTH_LONG,
                        ).show()
                    }.onFailure { error ->
                        Toast.makeText(
                            this@MainActivity,
                            "Import fehlgeschlagen: ${error.message ?: "ungültige Datei"}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }

    private val diagnosticsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread(::refresh) }
    private val uiListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            runOnUiThread {
                SugarliciousColors.apply(SugarliciousColorStore.load(uiPreferences))
                if (screen == DashboardScreen.SETTINGS && isInteractiveAppearancePreference(key)) {
                    // Keep the active color picker/slider and expanded section alive. Rebuilding the
                    // complete Settings hierarchy here used to close the configurator on every drag.
                    applyRuntimeColors()
                } else if (uiPreferenceRequiresDashboardRefresh(key)) {
                    refresh(forceSettingsRender = true)
                }
            }
            scope.launch(Dispatchers.IO) {
                runCatching { publishWatchConfig(applicationContext) }
                if (uiPreferenceRequiresWidgetUpdate(key)) {
                    runCatching { SugarliciousWidgets.update(applicationContext) }
                }
            }
        }

    internal fun uiPreferenceRequiresDashboardRefresh(key: String?): Boolean =
        key != "watchFaceIndex"

    internal fun isInteractiveAppearancePreference(key: String?): Boolean =
        key != null && (
            key.startsWith("color.") ||
                key.startsWith("notification.color.") ||
                key.startsWith("widget.color.") ||
                key.startsWith("cgm.dot") ||
                key.startsWith("notification.cgm.dot")
            )

    internal fun uiPreferenceRequiresWidgetUpdate(key: String?): Boolean =
        key == "themeMode" ||
            key?.startsWith("color.") == true ||
            key?.startsWith("widget.color.") == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SugarliciousColors.apply(SugarliciousColorStore.load(uiPreferences))
        setContentView(R.layout.activity_main)
        if (!uiPreferences.getBoolean("graphHoursDefault3Migrated", false)) {
            uiPreferences.edit { putInt("graphHours", 3); putBoolean("graphHoursDefault3Migrated", true) }
        }
        if (!uiPreferences.getBoolean("cgmDotsOnlyDefaultMigratedV1", false)) {
            uiPreferences.edit {
                putBoolean("showCgmGraph", true)
                putBoolean("showMetabolicGraph", false)
                putBoolean("showPredictions", false)
                putBoolean("cgm.targetRange", false)
                putBoolean("cgm.basal", false)
                putBoolean("cgm.activity", false)
                putBoolean("cgm.prediction.iob", false)
                putBoolean("cgm.prediction.cob", false)
                putBoolean("cgm.prediction.uam", false)
                putBoolean("cgm.prediction.zeroTemp", false)
                putBoolean("cgmDotsOnlyDefaultMigratedV1", true)
            }
        }
        if (!uiPreferences.getBoolean("overviewDefaultsMigratedV2", false)) {
            uiPreferences.edit {
                putBoolean("showCgmGraph", true)
                putBoolean("showDetails", true)
                putBoolean("showMetabolicGraph", false)
                putBoolean("cgm.targetRange", true)
                putBoolean("cgm.targetValue", false)
                putBoolean("cgm.basal", false)
                putBoolean("cgm.activity", false)
                putBoolean("cgm.prediction.iob", false)
                putBoolean("cgm.prediction.cob", false)
                putBoolean("cgm.prediction.uam", false)
                putBoolean("cgm.prediction.zeroTemp", false)
                putBoolean("overviewDefaultsMigratedV2", true)
            }
        }
        if (!uiPreferences.getBoolean("targetStepLineDefaultMigratedV3", false)) {
            // Earlier releases persisted the target-value layer as off, which made a correctly
            // rendered Step-Line invisible after an in-place update. Enable the new AAPS-style
            // layer once; subsequent user changes are preserved.
            uiPreferences.edit {
                putBoolean("cgm.targetValue", true)
                putBoolean("targetStepLineDefaultMigratedV3", true)
            }
        }
        content = findViewById(R.id.dashboard_content)
        scroll = findViewById(R.id.dashboard_scroll)
        screen = savedInstanceState?.getString("screen")?.let { runCatching { DashboardScreen.valueOf(it) }.getOrNull() } ?: DashboardScreen.OVERVIEW
        styleTitle()
        factory = DashboardViewFactory(this, DashboardCallbacks(
            navigate = ::navigate,
            setUnit = { uiPreferences.edit { putString("unit", it.name) } },
            setDataSource = { uiPreferences.edit { putString("dataSource", it.name) } },
            openG7Setup = { startActivity(Intent(this, G7SetupActivity::class.java)) },
            openDiagnostics = { startActivity(Intent(this, DiagnosticActivity::class.java)) },
            setThemeMode = { uiPreferences.edit { putString("themeMode", it.name) } },
            setShowDetails = { uiPreferences.edit { putBoolean("showDetails", it) } },
            setShowCgmGraph = { uiPreferences.edit { putBoolean("showCgmGraph", it) } },
            setCgmStream = { key, enabled ->
                uiPreferences.edit {
                    putBoolean(
                        key,
                        enabled,
                    )
                }
            },
            setShowMetabolicGraph = { uiPreferences.edit { putBoolean("showMetabolicGraph", it) } },
            setCompact = { uiPreferences.edit { putBoolean("compact", it) } },
            setLiveNotification = ::setLiveNotification,
            setNotificationGraphEnabled = { enabled ->
                uiPreferences.edit {
                    putBoolean(
                        PersistentBridgeService.PREFERENCE_NOTIFICATION_GRAPH_ENABLED,
                        enabled,
                    )
                }
            },
            setNotificationGraphHours = { hours ->
                uiPreferences.edit {
                    putInt(
                        PersistentBridgeService.PREFERENCE_NOTIFICATION_GRAPH_HOURS,
                        hours.coerceIn(1, 3),
                    )
                }
            },
            setWatchFaceIndex = {
                uiPreferences.edit {
                    putInt(
                        "watchFaceIndex",
                        it.coerceIn(sugarliciousWatchFaceCards.indices),
                    )
                }
            },
            syncNow = ::syncNow,
            connectHealthConnect = ::connectHealthConnect,
            syncHealthConnect = ::syncHealthConnect,
            manageHealthConnect = ::manageHealthConnect,
            requestNotificationAccess = ::requestNotificationAccess,
            requestUnrestrictedBattery = ::requestUnrestrictedBattery,
            exportSettings = ::exportSettings,
            importSettings = ::importSettings,
            openProjectGitHub = ::openProjectGitHub,
            openContactEmail = ::openContactEmail,
        ))
        bindTopNavigation()
        PersistentBridgeService.start(this)
        scope.launch(Dispatchers.IO) {
            applicationContext.recordMobileDiagnostic("APP", "APP-START-100", "Mobile overview started")
        }
        requestNotificationPermissionIfNeeded()
        scope.launch {
            TherapyStateStore(this@MainActivity).state.collectLatest {
                state = it
                refresh()
            }
        }
        refresh(forceSettingsRender = true)
    }

    private fun connectHealthConnect() {
        when (HealthConnectIntegration.availability(this)) {
            HealthConnectClient.SDK_AVAILABLE -> healthPermissionsLauncher.launch(HealthConnectIntegration.permissions)
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> openExternal(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${HealthConnectIntegration.PROVIDER_PACKAGE}")))
            else -> Toast.makeText(this, "Health Connect ist auf diesem Gerät nicht verfügbar", Toast.LENGTH_SHORT).show()
        }
    }

    private fun syncHealthConnect() {
        scope.launch {
            val synced = runCatching { HealthConnectIntegration.sync(applicationContext) }.getOrNull()
            if (synced != null) {
                SugarliciousWidgets.update(applicationContext)
                val message = when (synced.glucoseExport.state) {
                    HealthConnectExportState.SUCCESS -> "Aktualisiert · ${synced.glucoseExport.acceptedCount} BZ-Werte übertragen"
                    HealthConnectExportState.NO_DATA -> "Aktualisiert · kein neuer BZ-Wert"
                    HealthConnectExportState.PERMISSION_MISSING -> "Aktualisiert · BZ-Schreibrecht fehlt"
                    HealthConnectExportState.FAILED -> "BZ-Export fehlgeschlagen · ${synced.glucoseExport.errorCode}"
                    HealthConnectExportState.UNAVAILABLE -> "Health Connect nicht verfügbar"
                }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@MainActivity, "Health-Connect-Zugriff fehlt", Toast.LENGTH_SHORT).show()
            }
            refresh(forceSettingsRender = true)
        }
    }

    private fun manageHealthConnect() {
        if (HealthConnectIntegration.availability(this) == HealthConnectClient.SDK_AVAILABLE) {
            openExternal(HealthConnectClient.getHealthConnectManageDataIntent(this, HealthConnectIntegration.PROVIDER_PACKAGE))
        } else connectHealthConnect()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        var swipeTarget: DashboardScreen? = null

        if (screen == DashboardScreen.SETTINGS) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val safeStartArea = resources.displayMetrics.widthPixels * 0.70f
                    settingsSwipeTracking = event.pointerCount == 1 && event.rawX <= safeStartArea
                    settingsSwipeStartX = event.rawX
                    settingsSwipeStartY = event.rawY
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    settingsSwipeTracking = false
                }

                MotionEvent.ACTION_UP -> {
                    if (settingsSwipeTracking) {
                        swipeTarget = menuSwipeTarget(
                            screen = screen,
                            deltaX = event.rawX - settingsSwipeStartX,
                            deltaY = event.rawY - settingsSwipeStartY,
                            minimumDistancePx = 72.dp.toFloat(),
                        )
                    }
                    settingsSwipeTracking = false
                }

                MotionEvent.ACTION_CANCEL -> {
                    settingsSwipeTracking = false
                }
            }
        } else {
            settingsSwipeTracking = false
        }

        val handled = super.dispatchTouchEvent(event)
        swipeTarget?.let(::navigate)
        return handled
    }

    override fun onStart() {
        super.onStart()
        diagnostics.registerOnSharedPreferenceChangeListener(diagnosticsListener)
        uiPreferences.registerOnSharedPreferenceChangeListener(uiListener)
        clockJob = scope.launch {
            while (true) {
                runCatching { requestWatchRuntimeStatus(applicationContext) }
                delay(30.seconds)
                refresh()
            }
        }
        scope.launch(Dispatchers.IO) { runCatching { requestWatchRuntimeStatus(applicationContext) } }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::factory.isInitialized) refresh(forceSettingsRender = screen == DashboardScreen.SETTINGS)
    }

    override fun onStop() {
        clockJob?.cancel(); clockJob = null
        diagnostics.unregisterOnSharedPreferenceChangeListener(diagnosticsListener)
        uiPreferences.unregisterOnSharedPreferenceChangeListener(uiListener)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screen", screen.name)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh(forceSettingsRender: Boolean = false) {
        if (!::content.isInitialized || !::factory.isInitialized) return
        SugarliciousColors.apply(SugarliciousColorStore.load(uiPreferences))
        applyRuntimeColors()
        val diagnosticState = DiagnosticsSnapshot.read(diagnostics)
        val uiState = DashboardUiPreferences.read(uiPreferences)
        if (screen != DashboardScreen.SETTINGS || forceSettingsRender) {
            factory.render(content, screen, state, diagnosticState, uiState, System.currentTimeMillis())
        }
        renderFixedWatchHeader()
        updateTopBar()
    }


    @Suppress("DEPRECATION")
    private fun applyRuntimeColors() {
        val backgroundColor =
            SugarliciousColors.argb(
                SugarliciousColorRole.BACKGROUND,
            )
        val surface =
            SugarliciousColors.argb(
                SugarliciousColorRole.SURFACE,
            )
        val border =
            SugarliciousColors.argb(
                SugarliciousColorRole.BORDER,
            )
        val text =
            SugarliciousColors.argb(
                SugarliciousColorRole.TEXT_PRIMARY,
            )

        findViewById<View>(R.id.root)
            .setBackgroundColor(backgroundColor)
        findViewById<View>(R.id.scroll_fade).background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, backgroundColor),
        )
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        val light = SugarliciousColors.palette.isLight
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
        } else {
            @Suppress("DEPRECATION")
            val flags = (if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0) or
                (if (light) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }

        findViewById<TextView>(R.id.app_title)
            .setTextColor(text)

        findViewById<ImageView>(R.id.brand_logo).imageTintList = ColorStateList.valueOf(text)
        findViewById<ImageView>(R.id.top_back).imageTintList = ColorStateList.valueOf(text)
        findViewById<ImageView>(R.id.top_settings).imageTintList = ColorStateList.valueOf(text)
        updateTopBar()
    }
    private fun bindTopNavigation() {
        findViewById<View>(R.id.top_settings).setOnClickListener { navigate(DashboardScreen.SETTINGS) }
        findViewById<View>(R.id.top_back).setOnClickListener { navigate(DashboardScreen.OVERVIEW) }
    }

    private fun navigate(target: DashboardScreen) {
        if (screen == target) return
        screen = target
        scroll.scrollTo(0, 0)
        refresh(forceSettingsRender = true)
        scope.launch(Dispatchers.IO) {
            applicationContext.recordMobileDiagnostic(
                "NAVIGATION",
                "APP-NAV-100",
                "Dashboard screen opened",
                metadata = mapOf("screen" to target.name),
            )
        }
    }

    private fun updateTopBar() {
        val bar = findViewById<View>(R.id.top_app_bar)
        val back = findViewById<View>(R.id.top_back)
        val brand = findViewById<View>(R.id.brand_logo)
        val title = findViewById<TextView>(R.id.app_title)
        val settings = findViewById<View>(R.id.top_settings)
        when (screen) {
            DashboardScreen.OVERVIEW -> {
                bar.visibility = View.GONE
            }
            DashboardScreen.WATCH -> {
                // The Watch screen has its own inline back/title row below the connected-watch
                // summary. Hiding the native bar avoids the duplicated header.
                bar.visibility = View.GONE
                back.visibility = View.GONE
                brand.visibility = View.GONE
                settings.visibility = View.GONE
                title.text = "Watch"
            }
            DashboardScreen.SETTINGS -> {
                bar.visibility = View.VISIBLE
                back.visibility = View.VISIBLE
                brand.visibility = View.GONE
                settings.visibility = View.GONE
                title.text = "Einstellungen"
            }
        }

        scroll.isUserScrollEnabled = screen != DashboardScreen.OVERVIEW
        findViewById<View>(R.id.scroll_fade).visibility =
            if (screen == DashboardScreen.OVERVIEW) View.GONE else View.VISIBLE
        content.setPadding(
            content.paddingLeft,
            content.paddingTop,
            content.paddingRight,
            if (screen == DashboardScreen.OVERVIEW) 0 else 24.dp,
        )
    }

    private fun renderFixedWatchHeader() {
        val container = findViewById<android.widget.FrameLayout>(R.id.watch_fixed_header)
        if (screen != DashboardScreen.WATCH) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        if (container.childCount > 0) return
        val composeView = androidx.compose.ui.platform.ComposeView(this).apply {
            setViewCompositionStrategy(
                androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow,
            )
            setContent {
                app.aapswear.mobile.ui.theme.SugarliciousTheme {
                    WatchMenuHeader(
                        onBack = { navigate(DashboardScreen.OVERVIEW) },
                        onSettings = { navigate(DashboardScreen.SETTINGS) },
                    )
                }
            }
        }
        container.addView(
            composeView,
            android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun styleTitle() {
        val value = SpannableString(getString(R.string.app_name))
        value.setSpan(ForegroundColorSpan(SugarliciousColors.argb(SugarliciousColorRole.PRIMARY)), 5, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        findViewById<TextView>(R.id.app_title).text = value
    }

    private fun syncNow() {
        val latest = state
        if (latest == null) {
            Toast.makeText(this, "Noch keine gültigen AndroidAPS-Daten vorhanden", Toast.LENGTH_SHORT).show()
            return
        }
        diagnostics.edit { putString("lastSyncStatus", "pending") }
        scope.launch {
            runCatching { withTimeout(4.seconds) { publishState(applicationContext, latest) } }
                .onSuccess {
                    diagnostics.edit { putLong("lastSyncAt", System.currentTimeMillis()); putString("lastSyncStatus", "ok"); remove("lastSyncError") }
                    Toast.makeText(this@MainActivity, "An Watch übertragen", Toast.LENGTH_SHORT).show()
                }
                .onFailure { error ->
                    diagnostics.edit { putString("lastSyncStatus", "unavailable"); putString("lastSyncError", error.javaClass.simpleName) }
                    Toast.makeText(this@MainActivity, "Keine Watch erreichbar", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun setLiveNotification(enabled: Boolean) {
        uiPreferences.edit { putBoolean(PersistentBridgeService.PREFERENCE_LIVE_NOTIFICATION, enabled) }
        PersistentBridgeService.refresh(this)
        if (!enabled) return
        if (Build.VERSION.SDK_INT < 36) {
            Toast.makeText(this, "Live-Status benötigt Android 16; normale Benachrichtigung bleibt aktiv", Toast.LENGTH_LONG).show()
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.canPostPromotedNotifications()) {
            openExternal(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestNotificationAccess() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        openExternal(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
    }

    private fun requestUnrestrictedBattery() {
        if (AppRuntimeAccess.isIgnoringBatteryOptimizations(this)) {
            Toast.makeText(this, "Dauerbetrieb ist bereits uneingeschränkt", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName")),
            )
        }.onFailure {
            openExternal(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun exportSettings() {
        settingsExportLauncher.launch("sugarlicious-settings-${LocalDate.now()}.json")
    }

    private fun importSettings() {
        settingsImportLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
    }

    private fun openContactEmail() {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", getString(R.string.contact_email), null))
            .putExtra(Intent.EXTRA_SUBJECT, "Sugarlicious")
        openExternal(intent)
    }

    private fun openProjectGitHub() {
        openExternal(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://github.com/FreDiabetics/aaps_wearable-suite"),
            ),
        )
    }

    private fun openExternal(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Keine passende App installiert", Toast.LENGTH_SHORT).show()
        }
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

}
