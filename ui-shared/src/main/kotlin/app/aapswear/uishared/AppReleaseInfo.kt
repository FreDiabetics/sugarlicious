package app.aapswear.uishared

import android.content.Context

/** About pages use the installed package version, not a shared or hardcoded suite version. */
object AppReleaseInfo {
    enum class Product { MOBILE, WEAR, COLLECTOR }

    fun version(context: Context): String = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unbekannt"

    fun changes(product: Product): List<String> =
        when (product) {
            Product.MOBILE -> listOf("Slot-Vorschauen passen sich der ausgewählten Complication an.", "Watch-Aktionen im Header und überarbeitete Vorschauen.", "Übertragungsprotokoll unter Datenverwaltung.", "Verbesserte Darstellung bei größerer Systemschrift.")
            Product.WEAR -> listOf("Mehr Abstand innerhalb der Menü-Icons.", "Überarbeitete ApeX-Graphfläche oberhalb der mittleren Complications.", "Aktualisierte Watchface-Vorlagen und Vorschauen.")
            Product.COLLECTOR -> listOf("Vigil mit transparenter Zeigerauswahl und äußeren Markierungen.", "Besser lesbare Graphskalen und systemabhängiges Uhrzeitformat.", "Neues SugarWear-Vektoricon.", "Uhrzeit wird auch nach Zeit- und Zeitzonenänderungen aktualisiert.")
        }

    fun summary(context: Context, product: Product): String =
        buildString {
            append("Version ").append(version(context))
            val changes = changes(product)
            if (changes.isNotEmpty()) append("\n\nNeu\n").append(changes.joinToString("\n") { "• $it" })
        }
}
