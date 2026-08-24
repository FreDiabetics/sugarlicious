plugins { kotlin("jvm"); kotlin("plugin.serialization") }
dependencies { api(project(":core-model")); api(project(":dexcom-g7")); implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0"); testImplementation(kotlin("test")) }
tasks.test { useJUnitPlatform() }
