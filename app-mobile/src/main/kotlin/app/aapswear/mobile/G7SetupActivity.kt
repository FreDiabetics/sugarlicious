package app.aapswear.mobile

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.edit
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColorStore
import app.aapswear.mobile.ui.theme.SugarliciousColors
import app.aapswear.protocol.G7SetupCommand
import app.aapswear.protocol.WearProtocol
import com.google.android.gms.wearable.Wearable
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class G7SetupActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var pairingCode: EditText
    private lateinit var status: TextView
    private var scannedSerial: String? = null
    private var scannedGtin: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SugarliciousColors.apply(
            SugarliciousColorStore.load(getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE)),
        )
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(color(SugarliciousColorRole.BACKGROUND))
            addView(content())
        })
    }

    private fun content() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20.dp, 22.dp, 20.dp, 28.dp)
        setBackgroundColor(color(SugarliciousColorRole.BACKGROUND))

        addView(label("SUGARLICIOUS", 11f, color(SugarliciousColorRole.PRIMARY), bold = true).apply { letterSpacing = 0.1f })
        addView(label("Direct to Watch", 28f, color(SugarliciousColorRole.TEXT_PRIMARY), bold = true))
        addView(label("Sensor direkt mit der Galaxy Watch verbinden", 14f, color(SugarliciousColorRole.SECONDARY), bold = true))
        addView(label("Beende dafür den direkten Collector in Juggluco. Ein Sensor kann immer nur einen aktiven Collector beliefern.", 12f, color(SugarliciousColorRole.TEXT_SECONDARY)).apply {
            setPadding(2.dp, 7.dp, 2.dp, 12.dp)
        })

        addView(
            LinearLayout(this@G7SetupActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16.dp, 15.dp, 16.dp, 16.dp)
                background = cardBackground()

                addView(label("SENSORCODE", 11f, color(SugarliciousColorRole.PRIMARY), bold = true))
                pairingCode = EditText(this@G7SetupActivity).apply {
                    hint = "4-stelliger Code"
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    filters = arrayOf(InputFilter.LengthFilter(4))
                    setTextColor(color(SugarliciousColorRole.TEXT_PRIMARY))
                    setHintTextColor(color(SugarliciousColorRole.TEXT_SECONDARY))
                    background = inputBackground()
                    gravity = Gravity.CENTER
                    textSize = 24f
                    typeface = Typeface.create("sans", Typeface.BOLD)
                    setPadding(14.dp, 9.dp, 14.dp, 9.dp)
                }
                addView(pairingCode, rowParams())
                addView(actionButton("Data-Matrix-Code scannen") { scanApplicator() }, rowParams())
                addView(actionButton("Sensor auf der Watch einrichten", primary = true) { sendSetup() }, rowParams())
            },
            rowParams(),
        )

        status = label("Noch nicht übertragen", 12f, color(SugarliciousColorRole.TEXT_SECONDARY), bold = true).apply {
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
            background = statusBackground()
        }
        addView(status, rowParams())
        addView(label("Sensorcode und Sitzungsschlüssel bleiben verschlüsselt auf der Watch. Sie werden weder in Diagnosen geschrieben noch an Health Connect übertragen.", 11f, color(SugarliciousColorRole.TEXT_SECONDARY)).apply {
            setPadding(4.dp, 10.dp, 4.dp, 0)
        })
    }

    private fun actionButton(text: String, primary: Boolean = false, action: () -> Unit) =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 13f
            typeface = Typeface.create("sans", Typeface.BOLD)
            setTextColor(if (primary) color(SugarliciousColorRole.ON_PRIMARY) else color(SugarliciousColorRole.TEXT_PRIMARY))
            background = if (primary) primaryActionBackground() else secondaryActionBackground()
            backgroundTintList = null
            setOnClickListener { action() }
        }

    private fun scanApplicator() {
        status.text = "Scanner wird geöffnet …"
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue ?: barcode.rawBytes?.toString(Charsets.ISO_8859_1)
                val parsed = raw?.let(G7ApplicatorBarcodeParser::parse)
                if (parsed == null) {
                    status.text = "Sensorcode nicht erkannt. Bitte den 4-stelligen Code eingeben."
                } else {
                    pairingCode.setText(parsed.pairingCode)
                    scannedSerial = parsed.sensorSerial
                    scannedGtin = parsed.gtin
                    status.text = "Sensor-Applikator erkannt"
                }
            }
            .addOnCanceledListener { status.text = "Scan abgebrochen" }
            .addOnFailureListener { status.text = "Scanner nicht verfügbar – Code bitte manuell eingeben" }
    }

    private fun sendSetup() {
        val code = pairingCode.text?.toString().orEmpty()
        val command = runCatching { G7SetupCommand(code, scannedSerial, scannedGtin) }.getOrNull()
        if (command == null) {
            status.text = "Bitte den 4-stelligen Sensorcode eingeben"
            return
        }
        status.text = "Wird an die Watch übertragen …"
        scope.launch {
            val sent = withContext(Dispatchers.IO) {
                val nodes = runCatching { Wearable.getNodeClient(this@G7SetupActivity).connectedNodes.await() }.getOrDefault(emptyList())
                nodes.count { node ->
                    runCatching {
                        Wearable.getMessageClient(this@G7SetupActivity)
                            .sendMessage(node.id, WearProtocol.G7_SETUP_PATH, WearProtocol.encodeG7Setup(command))
                            .await()
                    }.isSuccess
                }
            }
            if (sent == 0) {
                status.text = "Keine Watch erreichbar. Bluetooth/WLAN-Verbindung prüfen."
            } else {
                getSharedPreferences("dashboard_ui", MODE_PRIVATE).edit {
                    putString("dataSource", DataSourcePreference.AUTOMATIC.name)
                    putBoolean(G7_SOURCE_FALLBACK_MIGRATION_KEY, true)
                }
                scope.launch(Dispatchers.IO) { runCatching { publishWatchConfig(applicationContext) } }
                pairingCode.text?.clear()
                status.text = "Einrichtung übertragen. Kopplungsanfrage auf der Watch bestätigen, falls sie erscheint."
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.START
        if (bold) typeface = Typeface.create("sans", Typeface.BOLD)
        setPadding(2.dp, 4.dp, 2.dp, 4.dp)
    }

    private fun cardBackground() = roundedBackground(
        fill = color(SugarliciousColorRole.SURFACE),
        border = color(SugarliciousColorRole.BORDER),
        radius = 24,
    )

    private fun inputBackground() = roundedBackground(
        fill = color(SugarliciousColorRole.SURFACE_HIGH),
        border = color(SugarliciousColorRole.BORDER),
        radius = 18,
    )

    private fun statusBackground() = roundedBackground(
        fill = color(SugarliciousColorRole.SURFACE),
        border = color(SugarliciousColorRole.BORDER),
        radius = 18,
    )

    private fun primaryActionBackground() = roundedBackground(
        fill = color(SugarliciousColorRole.PRIMARY),
        border = color(SugarliciousColorRole.PRIMARY),
        radius = 22,
    )

    private fun secondaryActionBackground() = roundedBackground(
        fill = color(SugarliciousColorRole.SURFACE_HIGH),
        border = color(SugarliciousColorRole.BORDER),
        radius = 22,
    )

    private fun roundedBackground(fill: Int, border: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = radius.dp.toFloat()
        setColor(fill)
        setStroke(1.dp, border)
    }

    private fun color(role: SugarliciousColorRole): Int = SugarliciousColors.argb(role)

    private fun rowParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        setMargins(0, 7.dp, 0, 0)
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

internal object G7ApplicatorBarcodeParser {
    fun parse(input: String): G7SetupCommand? {
        val normalized = input.trim()
            .removePrefix("]d2")
            .replace("^]", "\u001d")
            .replace(Regex("\\((\\d{2,3})\\)"), "$1")
        val fields = parseGs1(normalized)
        val code = fields["240"]?.take(4)
            ?: Regex("240([0-9]{4})").find(normalized)?.groupValues?.get(1)
            ?: return null
        if (code.length != 4 || !code.all(Char::isDigit)) return null
        val gtin = fields["01"]
        if (gtin != null && (gtin.length != 14 || gtin.substring(1, 8) != "0386270")) return null
        return G7SetupCommand(code, fields["21"], gtin)
    }

    private fun parseGs1(value: String): Map<String, String> {
        val fixed = linkedMapOf("01" to 14, "11" to 6, "17" to 6)
        val variable = listOf("240", "250", "21", "10")
        var offset = 0
        val result = linkedMapOf<String, String>()
        while (offset < value.length) {
            if (value[offset] == '\u001d') {
                offset++
                continue
            }
            val ai = (variable + fixed.keys).firstOrNull { value.startsWith(it, offset) } ?: break
            offset += ai.length
            val end = fixed[ai]?.let { (offset + it).coerceAtMost(value.length) }
                ?: value.indexOf('\u001d', offset).takeIf { it >= 0 }
                ?: value.length
            result[ai] = value.substring(offset, end)
            offset = end
        }
        return result
    }
}
