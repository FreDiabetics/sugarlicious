plugins {
    kotlin("jvm")
    application
}

dependencies { testImplementation(kotlin("test")) }

application { mainClass.set("app.aapswear.tools.screenshot.ComparatorCliKt") }

tasks.test { useJUnitPlatform() }
