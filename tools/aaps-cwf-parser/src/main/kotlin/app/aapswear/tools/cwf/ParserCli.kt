package app.aapswear.tools.cwf

import java.nio.file.Path

fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: aaps-cwf-parser <CustomWatchface.zip>" }
    val document = AapsCwfParser().parse(Path.of(args[0]))
    println("VALID ${document.metadata.name} by ${document.metadata.author}")
    println("Canvas ${document.canvasWidth}x${document.canvasHeight}; ${document.elements.size} layout elements")
    document.warnings.forEach { println("WARNING $it") }
}
