package app.aapswear.mobile

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.aapswear.datasource.aaps.AapsCapabilityDetector

class AapsSourceSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        val store = AapsSourceBindingStore(this)
        val current = store.read()
        val packageInput =
            EditText(this).apply {
                hint = "z. B. info.nightscout.androidaps"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setText(current?.packageName ?: AapsSourceTrustPolicy.OFFICIAL_PACKAGE)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                contentDescription = "AndroidAPS-Paketname"
            }
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 36, 32, 36)
                setBackgroundColor(Color.rgb(18, 18, 18))
                addView(text("AndroidAPS-Datenquelle", 24f))
                addView(text("Nur das hier festgelegte, installierte Paket darf als AndroidAPS-Quelle verwendet werden.", 15f))
                addView(packageInput, fullWidth())
                val installed =
                    AapsCapabilityDetector.KNOWN_PACKAGES.filter {
                        AapsCapabilityDetector.detectInstallation(this@AapsSourceSettingsActivity, it) != null
                    }
                if (installed.isNotEmpty()) {
                    addView(text("Erkannte Installationen", 15f))
                    installed.forEach { packageName ->
                        addView(
                            Button(this@AapsSourceSettingsActivity).apply {
                                text = packageName
                                setOnClickListener { packageInput.setText(packageName) }
                            },
                            fullWidth(),
                        )
                    }
                }
                addView(
                    Button(this@AapsSourceSettingsActivity).apply {
                        text = "Paket prüfen und speichern"
                        setOnClickListener {
                            val binding = store.bind(packageInput.text.toString())
                            if (binding == null) {
                                Toast.makeText(this@AapsSourceSettingsActivity, "Paket ist ungültig oder nicht installiert.", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this@AapsSourceSettingsActivity, "AndroidAPS-Paket wurde gebunden.", Toast.LENGTH_SHORT).show()
                                render()
                            }
                        }
                    },
                    fullWidth(),
                )
                addView(
                    text(
                        if (current == null) {
                            "Status: Nicht eingerichtet – Broadcasts werden verworfen."
                        } else {
                            "Status: ${current.packageName} · Zertifikat gebunden"
                        },
                        14f,
                    ),
                )
                addView(
                    text(
                        "Hinweis: Ältere AndroidAPS-Broadcasts teilen ihre echte Absenderidentität nicht mit. Sie werden als Legacy-Kompatibilität markiert; moderne, identifizierbare Absender müssen exakt passen.",
                        13f,
                    ),
                )
            }
        setContentView(root)
    }

    private fun text(value: String, size: Float) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.WHITE)
            gravity = Gravity.START
            setPadding(0, 12, 0, 12)
        }

    private fun fullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
