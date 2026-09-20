plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation(kotlin("test"))
}
application { mainClass.set("app.aapswear.tools.cwf.ParserCliKt") }
tasks.test { useJUnitPlatform() }
