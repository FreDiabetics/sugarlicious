package app.aapswear.uishared

import android.content.Context

/** About pages use the installed package version, not a shared or hardcoded suite version. */
object AppReleaseInfo {
    enum class Product { MOBILE, WEAR, COLLECTOR }

    fun version(context: Context): String = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unbekannt"

    fun changes(product: Product): List<String> =
        when (product) {
            Product.MOBILE -> listOf("BR-/TBR-Details im 4×2-Therapiewidget und neue Therapie-Icons.", "Gemeinsame Zeitraumwahl für CGM- und Therapie-Widgets.", "Einstellungen mit gleitendem Titel und schwebendem Zurück-Button.", "Verbesserte IOB-/COB-Skalierung und Ausrichtung der Insulinaktivität.")
            Product.WEAR -> listOf("Neue IOB- und Basal-Icons auch für Tiles und Complications.", "IOB und COB nebeneinander; Basal mit BR-/TBR-Zeitangaben.", "Gemeinsame Kachelkontur für Übersicht und Graph.", "Glukose-Farbinformation für ApeX.")
            Product.COLLECTOR -> emptyList()
        }

    fun summary(context: Context, product: Product): String =
        buildString {
            append("Version ").append(version(context))
            val changes = changes(product)
            if (changes.isNotEmpty()) append("\n\nNeu\n").append(changes.joinToString("\n") { "• $it" })
        }
}
