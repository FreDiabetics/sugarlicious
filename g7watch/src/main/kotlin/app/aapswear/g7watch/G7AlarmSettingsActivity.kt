package app.aapswear.g7watch

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.aapswear.g7.CgmAlarmSettings
import app.aapswear.g7.CgmAlarmType
import java.util.Locale

/** Immediate-save alarm configuration. Test notifications never touch the real alarm state. */
class G7AlarmSettingsActivity : Activity() {
    private lateinit var root: LinearLayout
    private var lastDndState: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        val granted = G7AlarmNotificationPolicy.isAccessGranted(this)
        if (lastDndState != null && lastDndState != granted) render()
        lastDndState = granted
        G7CgmAlarmNotifier.ensureAllChannels(this, G7AlarmSettingsStore.read(this))
    }

    private fun render() {
        val palette = G7AppearanceStore(this).load()
        val background = palette.argb(G7AppearanceRole.MENU_BACKGROUND)
        window.statusBarColor = background
        window.navigationBarColor = background
        val settings = G7AlarmSettingsStore.read(this)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(18.dp, 8.dp, 18.dp, 28.dp)
            setBackgroundColor(background)
            addView(topBar(palette), fullWidth())
            addView(dndCard(palette), cardParams(7))
            addView(globalCard(settings, palette), cardParams(7))
            CgmAlarmType.entries.forEach { type -> addView(alarmCard(type, settings, palette), cardParams(7)) }
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(root)
        })
    }

    private fun dndCard(palette: G7AppearancePalette): LinearLayout {
        val granted = G7AlarmNotificationPolicy.isAccessGranted(this)
        lastDndState = granted
        return card(palette).apply {
            addView(label("NICHT-STÖREN-ÜBERSTEUERUNG", 8f, palette.argb(G7AppearanceRole.MENU_PRIMARY), true))
            addView(label(if (granted) "Aktiv" else "Systemfreigabe erforderlich", 12f, if (granted) palette.argb(G7AppearanceRole.MENU_PRIMARY) else palette.argb(G7AppearanceRole.GLUCOSE_STALE), true))
            addView(label("Damit kritische Glukose- und Sensoralarme auch bei Nicht stören hörbar bleiben.", 8.5f, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY)))
            if (!granted) addView(actionButton("Systemfreigabe öffnen", palette) {
                runCatching { startActivity(G7AlarmNotificationPolicy.settingsIntent()) }
                    .onFailure { Toast.makeText(this@G7AlarmSettingsActivity, "Systemfreigabe konnte nicht geöffnet werden", Toast.LENGTH_LONG).show() }
            })
        }
    }

    private fun globalCard(settings: CgmAlarmSettings, palette: G7AppearancePalette): LinearLayout = card(palette).apply {
        addView(label("ALARMVERHALTEN", 8f, palette.argb(G7AppearanceRole.MENU_PRIMARY), true))
        addView(toggle("Sound", settings.soundEnabled, palette) { save(settings.copy(soundEnabled = it)) })
        addView(toggle("Vibration", settings.vibrationEnabled, palette) { save(settings.copy(vibrationEnabled = it)) })
        addView(toggle("Wiederholen", settings.repeatEnabled, palette) { save(settings.copy(repeatEnabled = it)) })
        addView(stepper("Wiederholung", "${settings.repeatIntervalMinutes} min", palette, {
            save(settings.copy(repeatIntervalMinutes = (settings.repeatIntervalMinutes - 5).coerceAtLeast(5)))
        }, {
            save(settings.copy(repeatIntervalMinutes = (settings.repeatIntervalMinutes + 5).coerceAtMost(120)))
        }))
    }

    private fun alarmCard(type: CgmAlarmType, settings: CgmAlarmSettings, palette: G7AppearancePalette): LinearLayout = card(palette).apply {
        addView(toggle(g7AlarmTitle(type), alarmEnabled(settings, type), palette) { save(withAlarmEnabled(settings, type, it)) })
        thresholdText(type, settings)?.let { addView(label("Schwelle · $it", 9f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true)) }
        addView(label("Sound · ${g7AlarmSoundName(type)}", 8.5f, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY)))
        addView(label(
            "${if (settings.vibrationEnabled) "Vibration aktiv" else "Vibration aus"} · ${if (settings.repeatEnabled) "Wiederholung ${settings.repeatIntervalMinutes} min" else "Keine Wiederholung"}",
            8.5f,
            palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY),
        ))
        if (type in setOf(CgmAlarmType.VERY_HIGH, CgmAlarmType.HIGH, CgmAlarmType.LOW, CgmAlarmType.VERY_LOW, CgmAlarmType.RAPID_RISE, CgmAlarmType.RAPID_FALL)) {
            addView(stepper("Schwelle anpassen", thresholdText(type, settings).orEmpty(), palette, {
                save(adjustThreshold(settings, type, -thresholdStep(type)))
            }, {
                save(adjustThreshold(settings, type, thresholdStep(type)))
            }))
        }
        addView(actionButton("Test", palette) {
            G7CgmAlarmNotifier.showTest(this@G7AlarmSettingsActivity, type, G7AlarmSettingsStore.read(this@G7AlarmSettingsActivity))
        })
    }

    private fun save(settings: CgmAlarmSettings) {
        G7AlarmSettingsStore.write(this, settings)
        G7CgmAlarmNotifier.ensureAllChannels(this, settings)
        render()
    }

    private fun topBar(palette: G7AppearancePalette) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label("‹", 25f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true).apply {
            gravity = Gravity.CENTER
            background = rounded(palette.argb(G7AppearanceRole.MENU_SURFACE), palette.argb(G7AppearanceRole.MENU_BORDER), 999f)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(44.dp, 44.dp))
        addView(LinearLayout(this@G7AlarmSettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp, 0, 0, 0)
            addView(label("G7 DIRECT TO WATCH", 8f, palette.argb(G7AppearanceRole.MENU_PRIMARY), true))
            addView(label("Alarme", 17f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    @Suppress("DEPRECATION")
    private fun toggle(title: String, checked: Boolean, palette: G7AppearancePalette, changed: (Boolean) -> Unit) = Switch(this).apply {
        text = title
        isChecked = checked
        setTextColor(palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY))
        textSize = 10f
        setTypeface(typeface, Typeface.BOLD)
        setOnCheckedChangeListener { _, value -> if (value != checked) changed(value) }
    }

    private fun stepper(title: String, value: String, palette: G7AppearancePalette, minus: () -> Unit, plus: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label("$title · $value", 8.5f, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(actionButton("−", palette, minus), LinearLayout.LayoutParams(42.dp, 38.dp))
        addView(actionButton("+", palette, plus), LinearLayout.LayoutParams(42.dp, 38.dp).apply { marginStart = 4.dp })
    }

    private fun actionButton(title: String, palette: G7AppearancePalette, action: () -> Unit) = Button(this).apply {
        text = title
        textSize = 8.5f
        isAllCaps = false
        setTextColor(palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY))
        background = rounded(palette.argb(G7AppearanceRole.MENU_SURFACE), palette.argb(G7AppearanceRole.MENU_BORDER), 16f)
        setOnClickListener { action() }
    }

    private fun card(palette: G7AppearancePalette) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(13.dp, 10.dp, 13.dp, 10.dp)
        background = rounded(palette.argb(G7AppearanceRole.MENU_SURFACE), palette.argb(G7AppearanceRole.MENU_BORDER), 19f)
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
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
    private fun cardParams(top: Int) = fullWidth().apply { topMargin = top.dp }
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

internal fun alarmEnabled(settings: CgmAlarmSettings, type: CgmAlarmType): Boolean = when (type) {
    CgmAlarmType.VERY_HIGH -> settings.veryHighEnabled
    CgmAlarmType.HIGH -> settings.highEnabled
    CgmAlarmType.LOW -> settings.lowEnabled
    CgmAlarmType.VERY_LOW -> settings.veryLowEnabled
    CgmAlarmType.RAPID_RISE -> settings.rapidRiseEnabled
    CgmAlarmType.RAPID_FALL -> settings.rapidFallEnabled
    CgmAlarmType.SIGNAL_LOSS -> settings.signalLossEnabled
    CgmAlarmType.SENSOR_ERROR -> settings.sensorErrorEnabled
}

internal fun withAlarmEnabled(settings: CgmAlarmSettings, type: CgmAlarmType, enabled: Boolean): CgmAlarmSettings = when (type) {
    CgmAlarmType.VERY_HIGH -> settings.copy(veryHighEnabled = enabled)
    CgmAlarmType.HIGH -> settings.copy(highEnabled = enabled)
    CgmAlarmType.LOW -> settings.copy(lowEnabled = enabled)
    CgmAlarmType.VERY_LOW -> settings.copy(veryLowEnabled = enabled)
    CgmAlarmType.RAPID_RISE -> settings.copy(rapidRiseEnabled = enabled)
    CgmAlarmType.RAPID_FALL -> settings.copy(rapidFallEnabled = enabled)
    CgmAlarmType.SIGNAL_LOSS -> settings.copy(signalLossEnabled = enabled)
    CgmAlarmType.SENSOR_ERROR -> settings.copy(sensorErrorEnabled = enabled)
}

internal fun thresholdText(type: CgmAlarmType, settings: CgmAlarmSettings): String? = when (type) {
    CgmAlarmType.VERY_HIGH -> "${settings.veryHighThreshold.toInt()} mg/dL"
    CgmAlarmType.HIGH -> "${settings.highThreshold.toInt()} mg/dL"
    CgmAlarmType.LOW -> "${settings.lowThreshold.toInt()} mg/dL"
    CgmAlarmType.VERY_LOW -> "${settings.veryLowThreshold.toInt()} mg/dL"
    CgmAlarmType.RAPID_RISE -> String.format(Locale.US, "%.1f mg/dL/min", settings.rapidRiseThreshold)
    CgmAlarmType.RAPID_FALL -> String.format(Locale.US, "%.1f mg/dL/min", settings.rapidFallThreshold)
    CgmAlarmType.SIGNAL_LOSS -> "${settings.signalLossMinutes} min"
    CgmAlarmType.SENSOR_ERROR -> null
}

private fun thresholdStep(type: CgmAlarmType): Double =
    if (type == CgmAlarmType.RAPID_RISE || type == CgmAlarmType.RAPID_FALL) 0.5 else 5.0

internal fun adjustThreshold(settings: CgmAlarmSettings, type: CgmAlarmType, delta: Double): CgmAlarmSettings = when (type) {
    CgmAlarmType.VERY_HIGH -> settings.copy(veryHighThreshold = (settings.veryHighThreshold + delta).coerceIn(settings.highThreshold + 5.0, 400.0))
    CgmAlarmType.HIGH -> settings.copy(highThreshold = (settings.highThreshold + delta).coerceIn(settings.lowThreshold + 5.0, settings.veryHighThreshold - 5.0))
    CgmAlarmType.LOW -> settings.copy(lowThreshold = (settings.lowThreshold + delta).coerceIn(settings.veryLowThreshold + 5.0, settings.highThreshold - 5.0))
    CgmAlarmType.VERY_LOW -> settings.copy(veryLowThreshold = (settings.veryLowThreshold + delta).coerceIn(40.0, settings.lowThreshold - 5.0))
    CgmAlarmType.RAPID_RISE -> settings.copy(rapidRiseThreshold = (settings.rapidRiseThreshold + delta).coerceIn(0.5, 10.0))
    CgmAlarmType.RAPID_FALL -> settings.copy(rapidFallThreshold = (settings.rapidFallThreshold + delta).coerceIn(0.5, 10.0))
    CgmAlarmType.SIGNAL_LOSS, CgmAlarmType.SENSOR_ERROR -> settings
}
