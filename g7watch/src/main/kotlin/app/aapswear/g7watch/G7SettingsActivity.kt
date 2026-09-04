package app.aapswear.g7watch

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal enum class G7SettingsSection(val title: String, val summary: String) {
    COLLECTOR("Collector", "Betrieb, Zeitplanung und Hintergrundstatus"),
    SENSOR_SESSION("Sensor und Session", "Sensoridentität, Laufzeit und Kopplung"),
    ALARMS("Alarme", "Glukosealarme und notwendige Systemrechte"),
    DISPLAY("Anzeige", "Farben und Darstellung des Collectors"),
    DIRECT_TO_WATCH("Direct to Watch", "Watchface"),
    HARDWARE_TEST("Hardwaretest", "BLE-, GATT- und Sensorfenster-Diagnose"),
    DIAGNOSTICS("Diagnose", "Attempts, Fehlercodes und Recovery"),
    DATA_MANAGEMENT("Datenverwaltung", "Lokale Collector- und Sitzungsdaten"),
    ABOUT("Über", "G7 Direct to Watch by Sugarlicious"),
}

class G7SettingsActivity : Activity() {
    private val expandedSections = linkedSetOf<G7SettingsSection>()
    private lateinit var pageRoot: LinearLayout
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::pageRoot.isInitialized) render()
    }

    private fun render() {
        val palette = G7AppearanceStore(this).load()
        val background = palette.argb(G7AppearanceRole.MENU_BACKGROUND)
        window.statusBarColor = background
        window.navigationBarColor = background
        val state = G7SensorStateStore(this).read()

        val restoreScrollY = if (::scrollView.isInitialized) scrollView.scrollY else 0
        pageRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(18.dp, 8.dp, 18.dp, 30.dp)
            setBackgroundColor(background)
            addView(topBar(palette), fullWidth())
            addView(
                infoCard(
                    "LIVE COLLECTOR STATUS",
                    if (state.collectorEnabled) "Aktiv" else "Inaktiv",
                    state.lastReading?.let { "${it.glucoseMgDl.toInt()} · ${relativeAge(it.timestampEpochMs)}" }
                        ?: state.lastError?.safeMessage
                        ?: "Noch kein gültiger Sensorwert",
                    palette,
                ),
                cardParams(top = 5),
            )
        }

        G7SettingsSection.entries.forEach { section -> addSection(section, palette) }

        if (!::scrollView.isInitialized) {
            scrollView = ScrollView(this).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
            }
            setContentView(scrollView)
        } else {
            scrollView.removeAllViews()
        }
        scrollView.setBackgroundColor(background)
        scrollView.addView(pageRoot)
        scrollView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                scrollView.viewTreeObserver.removeOnPreDrawListener(this)
                val maxScroll = (pageRoot.measuredHeight - scrollView.height).coerceAtLeast(0)
                scrollView.scrollTo(0, restoreScrollY.coerceAtMost(maxScroll))
                return true
            }
        })
    }

    private fun addSection(section: G7SettingsSection, palette: G7AppearancePalette) {
        val isInlineSection = section == G7SettingsSection.ABOUT
        val expanded = isInlineSection && section in expandedSections
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
            when (section) {
                G7SettingsSection.ABOUT -> {
                    val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
                    addView(infoCard("G7 Watch Collector", "Version $version", "Eigenständiger Dexcom-G7-Empfang auf der Watch.", palette), cardParams())
                }
                else -> Unit
            }
        }
        val chevron = text(if (expanded) "⌄" else "›", 20f, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY), true).apply {
            gravity = Gravity.CENTER
        }
        val header = LinearLayout(this).apply {
            tag = "settings-category-${section.name.lowercase()}"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 58.dp
            setPadding(13.dp, 8.dp, 8.dp, 8.dp)
            background = rounded(palette.argb(G7AppearanceRole.MENU_SURFACE), palette.argb(G7AppearanceRole.MENU_BORDER), 20f)
            isClickable = true
            isFocusable = true
            addView(LinearLayout(this@G7SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(section.title, 11f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true))
                addView(text(section.summary, 8f, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY)).apply { maxLines = 2 })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron, LinearLayout.LayoutParams(28.dp, 38.dp))
            setOnClickListener {
                if (isInlineSection) {
                    val wasExpanded = section in expandedSections
                    if (wasExpanded) expandedSections.remove(section) else expandedSections.add(section)
                    content.visibility = if (wasExpanded) View.GONE else View.VISIBLE
                    chevron.text = if (wasExpanded) "›" else "⌄"
                } else {
                    openSection(section)
                }
            }
        }
        pageRoot.addView(header, cardParams(top = 6))
        pageRoot.addView(content, fullWidth())
    }

    private fun openSection(section: G7SettingsSection) {
        val intent = when (section) {
            G7SettingsSection.DISPLAY -> Intent(this, G7AppearanceActivity::class.java)
            G7SettingsSection.DIRECT_TO_WATCH -> Intent(this, G7DirectToWatchSettingsActivity::class.java)
            G7SettingsSection.ALARMS -> Intent(this, G7AlarmSettingsActivity::class.java)
            G7SettingsSection.ABOUT -> return
            else -> Intent(this, G7SystemStatusActivity::class.java)
                .putExtra(G7SystemStatusActivity.EXTRA_SECTION, section.name)
        }
        startActivity(intent)
    }

    private fun sectionActionTitle(section: G7SettingsSection): String = when (section) {
        G7SettingsSection.COLLECTOR -> "Collector-Status und Aktionen"
        G7SettingsSection.SENSOR_SESSION -> "Sensor- und Sessionstatus"
        G7SettingsSection.ALARMS -> "Alarm- und Berechtigungsstatus"
        G7SettingsSection.HARDWARE_TEST -> "Hardwaretest öffnen"
        G7SettingsSection.DIAGNOSTICS -> "Collector-Diagnose öffnen"
        G7SettingsSection.DATA_MANAGEMENT -> "Lokale Daten und Status"
        G7SettingsSection.DISPLAY, G7SettingsSection.DIRECT_TO_WATCH, G7SettingsSection.ABOUT -> section.title
    }

    private fun topBar(palette: G7AppearancePalette) = g7SettingsHeader("Einstellungen", palette)

    private fun actionCard(title: String, value: String, palette: G7AppearancePalette, action: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(13.dp, 10.dp, 13.dp, 10.dp)
            background = rounded(palette.argb(G7AppearanceRole.MENU_SURFACE), palette.argb(G7AppearanceRole.MENU_BORDER), 18f)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            addView(text(title, 10f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(value, 9f, palette.argb(G7AppearanceRole.MENU_PRIMARY), true))
        }

    private fun infoCard(title: String, value: String, detail: String, palette: G7AppearancePalette): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(13.dp, 10.dp, 13.dp, 10.dp)
            background = rounded(palette.argb(G7AppearanceRole.MENU_SURFACE), palette.argb(G7AppearanceRole.MENU_BORDER), 18f)
            addView(text(title, 8f, palette.argb(G7AppearanceRole.MENU_PRIMARY), true).apply { letterSpacing = 0.08f })
            addView(text(value, 12f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true))
            addView(text(detail, 8.5f, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY)))
        }

    private fun relativeAge(timestamp: Long): String {
        val minutes = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0L) / 60_000L)
        return if (minutes == 0L) "jetzt" else "vor ${minutes} min"
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(1.dp, stroke)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    private fun fullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun cardParams(top: Int = 3) = fullWidth().apply { topMargin = top.dp; bottomMargin = 3.dp }
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
