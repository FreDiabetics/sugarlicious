package app.sugarlicious.tools.maintenance.watchface

import org.w3c.dom.Element

/** The existing complication carries two masks; WFF alone supplies both editor colors. */
internal fun configureWatchfaceGraphPopup(root: Element, family: String) {
    require(family in setOf("orbit", "apex"))
    val orbit = family == "orbit"
    val configuration = if (orbit) "primary" else "materialAccent"
    val accentConfiguration = if (orbit) "secondary" else "materialSubTheme"
    val clip = root.need(".//Group[@name='${if (orbit) "graph-clipped" else "graph-clipped-content"}']")
    clip.removeAttribute("tintColor")
    clip.one("Group[@name='native-graph-popup-tap']")?.remove()
    val scene = root.need("Scene")
    scene.one("Group[@name='native-graph-popup-tap']")?.remove()
    val height = if (orbit) "165" else "97"
    val base =
        clip.one("Group[@name='graph-cap-ink']") ?: clip.add("Group", "name" to "graph-cap-ink", "x" to "0", "y" to "0", "width" to "512", "height" to "512").apply {
            clip.children().filter { it.tagName == "PartImage" }.forEach(::appendChild)
        }
    base.set("tintColor" to "[CONFIGURATION.$configuration.0]")
    val range =
        clip.one("Group[@name='graph-range-ink']") ?: clip.add("Group", "name" to "graph-range-ink", "x" to "0", "y" to "0", "width" to "512", "height" to "512").apply {
            add("PartImage", "x" to "91", "y" to "91", "width" to "330", "height" to height).add("Image", "resource" to "[COMPLICATION.SMALL_IMAGE]")
        }
    range.set("tintColor" to "[CONFIGURATION.$accentConfiguration.0]")
    // Native-hand normalization also visits existing image nodes. Always restore both roles,
    // including on the second and subsequent generator runs.
    range.need("PartImage/Image").set("resource" to "[COMPLICATION.SMALL_IMAGE]")
    base.need("PartImage/Image").set("resource" to "[COMPLICATION.MONOCHROMATIC_IMAGE]")

    fun colors(id: String) =
        root.all("UserConfigurations/ColorConfiguration[@id='$id']/ColorOption").map { it.attr("colors").substringBefore(' ').uppercase() }.distinct().also { list ->
            require(list.isNotEmpty() && list.all { Regex("#FF[0-9A-F]{6}").matches(it) })
        }

    fun comparison(id: String, color: String, tone: Int = 0): String {
        val channels = listOf(3, 5, 7).map { color.substring(it, it + 2).toInt(16) }
        return "[CONFIGURATION.$id.$tone] == colorRgb(${channels.joinToString(",")})"
    }
    // Orbit's graph and TIR slots overlap. A tap inside the graph slot can otherwise be
    // routed to either slot's default action instead of the popup's deep link.
    val tapParent = if (orbit) scene else clip
    val popup = tapParent.add("Group", "name" to "native-graph-popup-tap", "x" to "0", "y" to "0", "width" to "512", "height" to "512")
    if (orbit) scene.one("PartDraw[@name='native-hands-center-tap']")?.let { scene.insertBefore(popup, it) }
    popup.add("Variant", "mode" to "AMBIENT", "target" to "alpha", "value" to "0")
    val outer = popup.add("Condition")
    val expressions = outer.add("Expressions")
    val palettes =
        root
            .all("UserConfigurations/ColorConfiguration[@id='$configuration']/ColorOption")
            .map { it.attr("colors").split(' ').take(2) }
            .distinct()
    palettes.forEachIndexed { index, palette ->
        val color = palette[0]
        val outline = palette[1]
        val expression = "graphPopup_${family}_$index"
        val paletteMatch = "${comparison(configuration, color)} && ${comparison(configuration, outline, 1)}"
        expressions.add("Expression", "name" to expression).textContent = if (orbit) "$paletteMatch && [CONFIGURATION.arcContent] == \"3\"" else paletteMatch
        val inner = outer.add("Compare", "expression" to expression).add("Condition")
        val innerExpressions = inner.add("Expressions")
        colors(accentConfiguration).forEachIndexed { accentIndex, accent ->
            val accentExpression = "graphPopup_${family}_${index}_range_$accentIndex"
            innerExpressions.add("Expression", "name" to accentExpression).textContent = comparison(accentConfiguration, accent)
            val part = inner.add("Compare", "expression" to accentExpression).add("PartDraw", "x" to "91", "y" to "91", "width" to "330", "height" to height)
            val outlineQuery = "&outline=${outline.removePrefix("#")}"
            part.add("Launch", "target" to "app://sugarlicious.watchface-graph/$family?color=${color.removePrefix("#")}&inRange=${accent.removePrefix("#")}$outlineQuery")
            part.add("Rectangle", "x" to "0", "y" to "0", "width" to "330", "height" to height).add("Fill", "color" to "#01000000")
        }
    }
}
