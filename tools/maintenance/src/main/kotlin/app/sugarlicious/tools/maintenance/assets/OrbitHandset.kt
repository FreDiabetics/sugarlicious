package app.sugarlicious.tools.maintenance.assets

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import app.sugarlicious.tools.maintenance.watchface.add as addNode
import app.sugarlicious.tools.maintenance.watchface.all as selectAll
import app.sugarlicious.tools.maintenance.watchface.need as requireNode
import app.sugarlicious.tools.maintenance.watchface.one as selectOne
import app.sugarlicious.tools.maintenance.watchface.parseXml as parseFace
import app.sugarlicious.tools.maintenance.watchface.set as setAttrs
import app.sugarlicious.tools.maintenance.watchface.writeXml as writeFace

private val handsetFiles = listOf("1hours_hand.svg", "2minutes_hand.svg", "3dots.svg", "4second_hand.svg")

private fun handsetSvg(source: Path): Document {
    val doc = parse(source)
    val root = doc.documentElement
    require(root.getAttribute("viewBox") == "0 0 512 512") { "Expected centered 512x512 Orbit artwork" }
    val fills =
        root
            .all("style")
            .flatMap { style ->
                Regex("""\.([\w-]+)\s*\{\s*fill:\s*([^;]+);""")
                    .findAll(style.textContent)
                    .map { match -> match.groupValues[1] to match.groupValues[2].trim() }
                    .toList()
            }.toMap()
    root.children().filter { it.localName == "defs" }.forEach(root::removeChild)

    fun normalize(element: Element, inheritedFill: String) {
        val fill =
            element.getAttribute("fill").ifEmpty {
                fills[element.getAttribute("class")] ?: inheritedFill
            }
        element.removeAttribute("class")
        if (element.localName !in setOf("svg", "g")) element.setAttribute("fill", fill)
        element.children().forEach { normalize(it, fill) }
    }
    root.children().forEach { normalize(it, "#000000") }
    return doc
}

internal fun importOrbitHandset(source: Path, resources: Path) {
    handsetFiles.forEach { require(Files.isRegularFile(source.resolve(it))) { "Missing Orbit layer $it" } }
    val output = resources.resolve("drawable-nodpi")
    Files.createDirectories(output)

    fun render(doc: Document, name: String, size: Int = 512) {
        val temp = Files.createTempFile("orbit-handset", ".svg")
        try {
            writeXml(temp, doc)
            svgPng(Files.readString(temp), output.resolve("$name.png"), size)
        } finally {
            Files.delete(temp)
        }
    }
    listOf("hour", "minute").forEachIndexed { index, hand ->
        render(handsetSvg(source.resolve(handsetFiles[index])), "orbit_custom_${hand}_4")
    }
    render(handsetSvg(source.resolve(handsetFiles[2])), "orbit_custom_hub_4")
    render(handsetSvg(source.resolve(handsetFiles[3])), "orbit_custom_second_4")
    listOf("orbit_custom_hour_4_accent.png", "orbit_custom_minute_4_accent.png", "orbit_custom_top_4.png")
        .forEach { Files.deleteIfExists(output.resolve(it)) }

    val path = resources.resolve("raw/watchface.xml")
    val face = parseFace(path)
    require(face.getAttribute("width") == "512" && face.getAttribute("height") == "512")
    val config = face.requireNode("UserConfigurations/ListConfiguration[@id='apexHands']")
    if (config.selectOne("ListOption[@id='3']") == null) config.addNode("ListOption", "id" to "3", "displayName" to "apexHands_3", "icon" to "orbit_hand_preview_18")
    val hands = face.requireNode("Scene/Group[@name='hands']")
    hands.selectOne("Group[@name='hands-3']")?.let(hands::removeChild)
    val group = hands.requireNode("Group[@name='hands-2']").cloneNode(true) as Element
    group.setAttrs("name" to "hands-3")
    group.requireNode("Transform[@target='alpha']").setAttrs("value" to "[CONFIGURATION.apexHands] == \"3\" ? 255 : 0")
    val clock = group.requireNode("AnalogClock")
    listOf("HourHand" to "hour", "MinuteHand" to "minute").forEach { (tag, name) ->
        clock.requireNode(tag).apply {
            setAttrs("resource" to "orbit_custom_${name}_4")
            removeAttribute("tintColor")
        }
    }
    group.selectAll(".//Group").forEach { it.setAttrs("name" to it.getAttribute("name").replace("seconds-2-", "seconds-3-")) }
    // Keep the existing seconds color and off options from the cloned hand configuration.
    group.selectAll(".//SecondHand").forEach { it.setAttrs("resource" to "orbit_custom_second_4") }
    // The composite hub already includes the top dot; draw it after the seconds layers.
    group
        .addNode("PartImage", "name" to "custom-hub-4", "x" to 0, "y" to 0, "width" to 512, "height" to 512)
        .addNode("Image", "resource" to "orbit_custom_hub_4")
    val ambient = hands.requireNode(".//Group[@name='ambient-seconds-hub']")
    hands.insertBefore(group, hands.selectOne("Group[@name='legacy-ambient-hub-gate']") ?: ambient)
    ambient.requireNode("Transform").setAttrs("value" to "[CONFIGURATION.secondSweep] == \"3\" && [CONFIGURATION.apexHands] != \"3\" ? 255 : 0")
    if (ambient.selectOne("Variant[@mode='AMBIENT']") != null) {
        // An outer visibility gate keeps the existing ambient override from covering the custom hub.
        val gate = hands.selectOne("Group[@name='legacy-ambient-hub-gate']") ?: hands.addNode("Group", "name" to "legacy-ambient-hub-gate", "x" to 0, "y" to 0, "width" to 512, "height" to 512)
        if (gate.selectOne("Transform") == null) gate.addNode("Transform", "target" to "alpha", "value" to "[CONFIGURATION.apexHands] == \"3\" ? 0 : 255")
        gate.appendChild(ambient)
    }
    writeFace(face, path)
    val stringsPath = resources.resolve("values/strings.xml")
    val strings = parseFace(stringsPath)
    if (strings.selectOne("string[@name='apexHands_3']") == null) strings.addNode("string", "name" to "apexHands_3").textContent = "Orbit · 4"
    writeFace(strings, stringsPath)

    val preview = parseXml("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 512 512\"><rect width=\"512\" height=\"512\" fill=\"#000000\"/></svg>")
    listOf(0, 1, 3, 2).forEach { index ->
        val layer = handsetSvg(source.resolve(handsetFiles[index]))
        val rotation = mapOf(0 to 304, 1 to 48, 3 to 192)[index]
        val target = preview.createElementNS("http://www.w3.org/2000/svg", "g")
        rotation?.let { target.setAttribute("transform", "rotate($it 256 256)") }
        layer.documentElement.children().forEach { shape ->
            target.appendChild(preview.importNode(shape, true))
        }
        preview.documentElement.appendChild(target)
    }
    render(preview, "orbit_hand_preview_18", 300)
    require(ImageIO.read(output.resolve("orbit_custom_hub_4.png").toFile()).width == 512)
}
