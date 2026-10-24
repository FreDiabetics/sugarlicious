package app.aapswear.model

/** Shared semantic weights; an emphasized value is real bold, not medium. */
object SystemTypographyTokens {
    const val NORMAL_WEIGHT = 400
    const val BOLD_WEIGHT = 700

    fun weight(bold: Boolean): Int = if (bold) BOLD_WEIGHT else NORMAL_WEIGHT

    fun fontFamily(manufacturer: String): String = if (manufacturer.equals("samsung", ignoreCase = true)) "sec" else "sans-serif"
}
