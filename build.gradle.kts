import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false

    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    kotlin("android") version "2.4.10" apply false
    kotlin("jvm") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
    id("dev.detekt") version "2.0.0-alpha.6" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
}

apply(from = "gradle/shared-conventions.gradle")

// Mobile and Wear deliberately share app.sugarlicious because they are companion variants on
// different devices. Keep their version monotonically aligned so an in-place update never
// becomes a downgrade merely because one variant was built later than the other.
extra["sugarliciousSuiteVersionCode"] = 71
extra["sugarliciousSuiteVersionName"] = "0.7.1"

val watchFaceValidatorCli = configurations.create("watchFaceValidatorCli")

dependencies {
    watchFaceValidatorCli(
        "com.google.android.wearable.watchface.validator:validator-push-cli:1.0.0-alpha09",
    )
}

tasks.register<Copy>("prepareWatchFaceValidatorCli") {
    from(watchFaceValidatorCli)
    include("validator-push-cli-*.jar")
    into(layout.buildDirectory.dir("watchface-push/tools"))
}

extra["productInstallTaskName"] = "installSugarliciousDebug"
extra["productPhoneApks"] = listOf("app-mobile/build/outputs/apk/debug/app-mobile-debug.apk")
extra["productWatchApks"] = listOf("app-wear/build/outputs/apk/debug/app-wear-debug.apk")
apply(from = "gradle/product-installer.gradle")
tasks.named("installSugarliciousDebug") {
    description = "Builds and installs only Sugarlicious Mobile and its Wear companion."
    dependsOn(":app-mobile:assembleDebug", ":app-wear:assembleDebug")
}

abstract class VerifyArchitectureTask : DefaultTask() {
    @get:org.gradle.api.tasks.Input
    abstract val dependencyGraph: org.gradle.api.provider.MapProperty<String, List<String>>

    @get:InputFile
    abstract val mobileManifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val applicationModules = setOf(":app-mobile", ":app-wear")
        val sharedModules =
            setOf(
                ":core-model",
                ":data-source-api",
                ":data-source-aaps",
                ":data-source-dexcom",
                ":data-source-external",
                ":dexcom-g7",
                ":wear-protocol",
                ":wear-storage",
                ":ui-shared",
                ":complications",
            )

        val graph = dependencyGraph.get()
        fun projectDependencies(module: String): Set<String> = graph.getValue(module).toSet()

        sharedModules.forEach { module ->
            val forbidden = projectDependencies(module).intersect(applicationModules)
            check(forbidden.isEmpty()) {
                "$module must not depend on application modules: ${forbidden.sorted()}"
            }
        }

        val mobileDependencies = projectDependencies(":app-mobile")
        check(":dexcom-g7" !in mobileDependencies && ":g7watch" !in mobileDependencies) {
            "Mobile must remain a bridge without a direct G7 BLE collector: ${mobileDependencies.sorted()}"
        }

        fun manifestValues(
            manifestFile: File,
            element: String,
            attribute: String,
        ): Set<String> {
            val document =
                DocumentBuilderFactory
                    .newInstance()
                    .apply { isNamespaceAware = true }
                    .newDocumentBuilder()
                    .parse(manifestFile)
            val nodes = document.getElementsByTagName(element)
            return buildSet {
                repeat(nodes.length) { index ->
                    val value = nodes.item(index).attributes?.getNamedItemNS("http://schemas.android.com/apk/res/android", attribute)?.nodeValue
                    if (!value.isNullOrBlank()) add(value)
                }
            }
        }

        val mobilePermissions =
            manifestValues(mobileManifest.get().asFile, "uses-permission", "name")
        check("android.permission.BLUETOOTH_SCAN" !in mobilePermissions) {
            "Mobile must not request BLE scan permission; direct collection is Wear-only"
        }
        check("android.permission.BLUETOOTH_CONNECT" !in mobilePermissions) {
            "Mobile must not request BLE connect permission; direct collection is Wear-only"
        }
    }
}

val architectureModules = setOf(
    ":app-mobile", ":app-wear", ":core-model", ":data-source-api",
    ":data-source-aaps", ":data-source-dexcom", ":data-source-external", ":dexcom-g7", ":wear-protocol", ":wear-storage",
    ":ui-shared", ":complications",
)
val verifyArchitecture = tasks.register<VerifyArchitectureTask>("verifyArchitecture") {
    group = "verification"
    description = "Verifies Sugarlicious module direction and shared library boundaries."
    mobileManifest.set(layout.projectDirectory.file("app-mobile/src/main/AndroidManifest.xml"))
}
// Snapshot the configured dependency graph once, before execution. The verification task
// receives plain values and declared file inputs, never live Project/script references.
gradle.projectsEvaluated {
    val graph = architectureModules.associateWith { module ->
        project(module).configurations.flatMap { configuration ->
            configuration.dependencies.withType(ProjectDependency::class.java).map { it.path }
        }.distinct().sorted()
    }
    verifyArchitecture.configure { dependencyGraph.set(graph) }
}
