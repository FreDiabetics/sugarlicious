package app.aapswear.mobile

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("SetTextI18n")
class NightscoutTreatmentSettingsActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Nightscout Treatments"
        val configuration = NightscoutConfigurationStore.read(this)
        val padding = (20 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        fun label(value: String) = TextView(this).apply {
            text = value
            setTextColor(Color.LTGRAY)
            textSize = 14f
            setPadding(0, padding / 2, 0, padding / 4)
        }
        val enabled = Switch(this).apply { text = "Nightscout Treatment-Anreicherung aktivieren"; isChecked = configuration.enabled }
        val url = EditText(this).apply {
            hint = "https://dein-nightscout.example"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(configuration.baseUrl)
            setSingleLine(true)
        }
        val auth = Spinner(this).apply {
            adapter = ArrayAdapter(this@NightscoutTreatmentSettingsActivity, android.R.layout.simple_spinner_dropdown_item, listOf("API Secret", "Access Token"))
            setSelection(if (configuration.authMode == NightscoutAuthMode.API_SECRET) 0 else 1)
        }
        val secret = EditText(this).apply {
            hint = if (NightscoutSecretStore.read(this@NightscoutTreatmentSettingsActivity).isNullOrBlank()) "Secret oder Token" else "Gespeichert – leer lassen zum Beibehalten"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val status = TextView(this).apply {
            val diagnostics = getSharedPreferences("diagnostics", MODE_PRIVATE)
            val last = diagnostics.getLong("nightscoutTreatmentLastSuccess", 0L)
            val state = diagnostics.getString("nightscoutTreatmentStatus", "Noch nicht synchronisiert")
            text = if (last > 0L) "$state · zuletzt ${java.time.Instant.ofEpochMilli(last)}" else state
            setPadding(0, padding, 0, padding)
        }
        fun requestedConfiguration() = NightscoutConfiguration(enabled.isChecked, url.text.toString(), if (auth.selectedItemPosition == 0) NightscoutAuthMode.API_SECRET else NightscoutAuthMode.ACCESS_TOKEN)
        fun save(showToast: Boolean): Boolean = runCatching {
            NightscoutConfigurationStore.save(this, requestedConfiguration(), secret.text.toString().takeIf(String::isNotBlank))
            scope.launch(Dispatchers.IO) { NightscoutTreatmentSync.applyConfigurationState(applicationContext) }
            if (showToast) Toast.makeText(this, "Nightscout-Einstellungen gespeichert", Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(this, it.message ?: "Einstellungen ungültig", Toast.LENGTH_LONG).show() }.isSuccess

        val test = Button(this).apply {
            text = "Verbindung testen und Treatments laden"
            setOnClickListener {
                if (!save(false)) return@setOnClickListener
                isEnabled = false
                status.text = "Verbindung wird geprüft …"
                scope.launch {
                    val result = withContext(Dispatchers.IO) { NightscoutTreatmentSync.sync(applicationContext) }
                    status.text = result.message
                    isEnabled = true
                }
            }
        }
        val saveButton = Button(this).apply {
            text = "Speichern"
            setOnClickListener { if (save(true)) finish() }
        }
        content.addView(enabled, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(label("Nightscout URL")); content.addView(url)
        content.addView(label("Authentifizierung")); content.addView(auth)
        content.addView(label("Zugangsdaten (verschlüsselt gespeichert)")); content.addView(secret)
        content.addView(status); content.addView(test); content.addView(saveButton)
        content.addView(TextView(this).apply {
            text = "Nightscout ergänzt ausschließlich fehlende Bolus- und Carb-Marker. CGM, IOB- und COB-Kurven bleiben AndroidAPS-Daten."
            gravity = Gravity.CENTER
            setPadding(0, padding, 0, padding)
        })
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
