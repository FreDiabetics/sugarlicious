plugins {
    kotlin("jvm")
    application
}
dependencies {
    implementation(project(":tools:aaps-cwf-parser"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation(kotlin("test"))
}
application { mainClass.set("app.aapswear.tools.wff.GeneratorCliKt") }
tasks.test { useJUnitPlatform() }
