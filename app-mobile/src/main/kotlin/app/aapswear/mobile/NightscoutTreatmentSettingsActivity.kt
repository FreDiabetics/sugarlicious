package app.aapswear.mobile

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("SetTextI18n")
class NightscoutTreatmentSettingsActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingLocalNetworkAction: (() -> Unit)? = null
    private val localNetworkPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingLocalNetworkAction
            pendingLocalNetworkAction = null
            if (granted) {
                action?.invoke()
            } else {
                Toast
                    .makeText(
                        this,
                        "Lokaler Netzwerkzugriff wurde nicht freigegeben",
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Nightscout Treatments"
        val configuration = NightscoutConfigurationStore.read(this)
        val light = app.aapswear.mobile.ui.theme.SugarliciousColors.palette.isLight
        val padding = (20 * resources.displayMetrics.density).toInt()
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding, padding, padding)
            }

        fun label(value: String) =
            TextView(this).apply {
                text = value
                setTextColor(MobileSettingsStyle.secondary(light))
                textSize = 13f
                setPadding(0, padding / 2, 0, padding / 4)
            }
        val enabled =
            mobileSettingsSwitch(this, configuration.enabled) {}.apply {
                text = "Nightscout Treatment-Anreicherung aktivieren"
                textSize = 18f
                setTextColor(MobileSettingsStyle.text(light))
            }
        val url =
            EditText(this).apply {
                hint = "https://dein-nightscout.example"
                setTextColor(MobileSettingsStyle.text(light))
                setHintTextColor(MobileSettingsStyle.secondary(light))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setText(configuration.baseUrl)
                setSingleLine(true)
            }
        val auth =
            Spinner(this).apply {
                // Keep the selection and popup interaction while omitting the platform arrow.
                background = null
                adapter =
                    ArrayAdapter(
                        this@NightscoutTreatmentSettingsActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        listOf("API Secret", "Access Token"),
                    )
                setSelection(if (configuration.authMode == NightscoutAuthMode.API_SECRET) 0 else 1)
            }
        val secret =
            EditText(this).apply {
                setTextColor(MobileSettingsStyle.text(light))
                setHintTextColor(MobileSettingsStyle.secondary(light))
                hint =
                    if (NightscoutSecretStore
                            .read(
                                this@NightscoutTreatmentSettingsActivity,
                            ).isNullOrBlank()
                    ) {
                        "Secret oder Token"
                    } else {
                        "Gespeichert – leer lassen zum Beibehalten"
                    }
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
            }
        val status =
            TextView(this).apply {
                val diagnostics = getSharedPreferences("diagnostics", MODE_PRIVATE)
                val last = diagnostics.getLong("nightscoutTreatmentLastSuccess", 0L)
                val state = diagnostics.getString("nightscoutTreatmentStatus", "Noch nicht synchronisiert")
                text = if (last > 0L) "$state · zuletzt ${java.time.Instant.ofEpochMilli(last)}" else state
                setPadding(0, padding, 0, padding)
            }

        fun requestedConfiguration() =
            NightscoutConfiguration(
                enabled.isChecked,
                url.text.toString(),
                if (auth.selectedItemPosition ==
                    0
                ) {
                    NightscoutAuthMode.API_SECRET
                } else {
                    NightscoutAuthMode.ACCESS_TOKEN
                },
            )

        fun withRequiredNetworkAccess(action: () -> Unit) {
            val requested = requestedConfiguration()
            val granted =
                ContextCompat.checkSelfPermission(this, LocalNetworkAccessPolicy.PERMISSION) ==
                    PackageManager.PERMISSION_GRANTED
            scope.launch {
                val needsPermission =
                    withContext(Dispatchers.IO) {
                        LocalNetworkAccessPolicy.needsPermissionAfterResolution(
                            sdkInt = Build.VERSION.SDK_INT,
                            enabled = requested.enabled,
                            baseUrl = requested.baseUrl,
                            permissionGranted = granted,
                        )
                    }
                if (needsPermission) {
                    pendingLocalNetworkAction = action
                    localNetworkPermissionLauncher.launch(LocalNetworkAccessPolicy.PERMISSION)
                } else {
                    action()
                }
            }
        }

        fun save(showToast: Boolean): Boolean =
            runCatching {
                NightscoutConfigurationStore.save(this, requestedConfiguration(), secret.text.toString().takeIf(String::isNotBlank))
                scope.launch(Dispatchers.IO) { NightscoutTreatmentSync.applyConfigurationState(applicationContext) }
                if (showToast) Toast.makeText(this, "Nightscout-Einstellungen gespeichert", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(this, it.message ?: "Einstellungen ungültig", Toast.LENGTH_LONG).show() }.isSuccess

        val test =
            Button(this).apply {
                text = "Verbindung testen und Treatments laden"
                setOnClickListener {
                    withRequiredNetworkAccess {
                        if (!save(false)) return@withRequiredNetworkAccess
                        isEnabled = false
                        status.text = "Verbindung wird geprüft …"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { NightscoutTreatmentSync.sync(applicationContext) }
                            status.text = result.message
                            isEnabled = true
                        }
                    }
                }
            }
        val saveButton =
            Button(this).apply {
                text = "Speichern"
                setOnClickListener {
                    withRequiredNetworkAccess {
                        if (save(true)) finish()
                    }
                }
            }

        fun card(vararg views: android.view.View) =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, padding / 2)
                background =
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(MobileSettingsStyle.card(light))
                        cornerRadius = 28 * resources.displayMetrics.density
                    }
                views.forEach { addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = padding }
            }
        content.addView(
            TextView(this).apply {
                text = "‹  Nightscout Treatments"
                textSize = 20f
                setTextColor(MobileSettingsStyle.text(light))
                setPadding(0, padding / 2, 0, padding)
                contentDescription = "Zurück – Nightscout Treatments"
                isFocusable = true
                setOnClickListener { finish() }
            },
        )
        content.addView(card(enabled))
        content.addView(card(label("Nightscout URL"), url, label("Authentifizierung"), auth, label("Zugangsdaten (verschlüsselt gespeichert)"), secret))
        content.addView(status)
        content.addView(test)
        content.addView(saveButton)
        content.addView(
            TextView(this).apply {
                text =
                    "Nightscout ergänzt ausschließlich fehlende Bolus- und Carb-Marker. CGM, IOB- und COB-Kurven bleiben AndroidAPS-Daten."
                gravity = Gravity.CENTER
                setPadding(0, padding, 0, padding)
            },
        )
        setContentView(
            ScrollView(this).apply {
                fitsSystemWindows = true
                setBackgroundColor(MobileSettingsStyle.page(light))
                addView(content)
            },
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
