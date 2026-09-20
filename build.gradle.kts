import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.process.ExecOperations

plugins {
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false

    id("com.android.application") version "9.3.1" apply false
    id("com.android.library") version "9.3.1" apply false
    kotlin("android") version "2.4.10" apply false
    kotlin("jvm") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
    id("dev.detekt") version "2.0.0-alpha.6" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
}

subprojects {
    tasks.withType<Test>().configureEach {
        // Robolectric 4.17 uses the JDK's FileDescriptor bridge while bootstrapping
        // Android 17. JDK 25 encapsulates that bridge unless it is opened to tests.
        jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
    }

    fun enableKotlinQualityGates() {
        pluginManager.apply("dev.detekt")
        pluginManager.apply("org.jlleitschuh.gradle.ktlint")
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.android") { enableKotlinQualityGates() }
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { enableKotlinQualityGates() }
    pluginManager.withPlugin("com.android.application") { enableKotlinQualityGates() }
    pluginManager.withPlugin("com.android.library") { enableKotlinQualityGates() }

    if (path.startsWith(":watchfaces:")) {
        pluginManager.withPlugin("com.android.application") {
            extensions.configure<ApplicationExtension>("android") {
                sourceSets
                    .getByName("main")
                    .res
                    .directories
                    .add(rootProject.file("watchfaces/shared-res").absolutePath)
                lint {
                    // Android Lint does not model the platform-loaded WFF roots
                    // (watch_face_info, watch_face_shapes and raw/watchface), so it reports
                    // their complete reachable resource graph as unused. WFF schema validation
                    // and the code-free APK verifier are the authoritative gates here.
                    disable.add("UnusedResources")
                    // R8 is required to strip Android-plugin generated DEX from these hasCode=false
                    // packages. Resource shrinking cannot be enabled safely for WFF's platform-
                    // resolved XML graph, so the code-free verifier enforces the actual contract.
                    disable.add("NotShrinkingResources")
                    // WFF packages intentionally reuse their full-bleed watchface preview as the
                    // package icon. It is picker artwork, not a maskable launcher foreground.
                    disable.add("IconLauncherShape")
                    // Large WFF vectors are full-face picker previews. Raster copies would lose
                    // density-independent fidelity and are not a runtime icon optimization.
                    disable.add("VectorRaster")
                }
            }
        }
    }

    tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
        // Detekt analysis follows the Android/JVM 17 source contract. This affects
        // analysis only; production compilation keeps each module's configured target.
        jvmTarget.set("17")
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    }
}

// Mobile and Wear deliberately share app.aapswear because they are companion variants on
// different devices. Keep their version monotonically aligned so an in-place update never
// becomes a downgrade merely because one variant was built later than the other.
extra["sugarliciousSuiteVersionCode"] = 14
extra["sugarliciousSuiteVersionName"] = "0.6.4"

abstract class InstallSugarliciousDebugTask
    @Inject
    constructor(
        private val execOperations: ExecOperations,
    ) : DefaultTask() {
        @get:InputFile
        abstract val mobileApk: RegularFileProperty

        @get:InputFile
        abstract val wearApk: RegularFileProperty

        @get:InputFile
        abstract val g7WatchApk: RegularFileProperty

        @get:InputFile
        abstract val vigilApk: RegularFileProperty

        @TaskAction
        fun install() {
            val devices =
                adb("devices")
                    .lineSequence()
                    .drop(1)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it.endsWith("\tdevice") }
                    .map { it.substringBefore('\t') }
                    // Wireless debugging may publish the same physical device through more than
                    // one mDNS alias. Keep the safety gate for multiple real devices, but do not
                    // reject duplicate transports that report the same hardware serial number.
                    .distinctBy(::physicalId)
                    .toList()

            check(devices.isNotEmpty()) {
                "Keine per ADB verbundenen Geräte gefunden. Smartphone und Watch müssen beide als 'device' erreichbar sein."
            }

            val watches = devices.filter(::isWatch)
            val phones = devices.filterNot { it in watches }

            check(phones.size == 1) {
                "Genau ein Smartphone wird erwartet, gefunden: ${describe(phones)}. " +
                    "Weitere Phones/Emulatoren vor der Installation trennen."
            }
            check(watches.size == 1) {
                "Genau eine Wear-OS-Watch wird erwartet, gefunden: ${describe(watches)}. " +
                    "Weitere Watches/Emulatoren vor der Installation trennen."
            }

            val phone = phones.single()
            val watch = watches.single()

            logger.lifecycle("Sugarlicious Phone: ${model(phone)} [$phone]")
            logger.lifecycle("Sugarlicious Watch: ${model(watch)} [$watch]")

            installApk(phone, mobileApk.get().asFile, "Mobile")
            removeAccidentalPhoneCollector(phone)
            installApk(watch, wearApk.get().asFile, "Wear")
            installApk(watch, g7WatchApk.get().asFile, "SugarWear")
            installApk(watch, vigilApk.get().asFile, "Vigil")
        }

        private fun isWatch(serial: String): Boolean =
            adb("-s", serial, "shell", "getprop", "ro.build.characteristics")
                .trim()
                .split(',')
                .any { it.trim().equals("watch", ignoreCase = true) }

        private fun model(serial: String): String =
            adb("-s", serial, "shell", "getprop", "ro.product.model")
                .trim()
                .ifBlank { "unbekannt" }

        private fun physicalId(serial: String): String =
            adb("-s", serial, "shell", "getprop", "ro.serialno")
                .trim()
                .ifBlank { serial }

        private fun describe(serials: List<String>): String =
            if (serials.isEmpty()) "0"
            else serials.joinToString { serial -> "${model(serial)} [$serial]" }

        private fun installApk(
            serial: String,
            apk: File,
            label: String,
        ) {
            check(apk.isFile) { "$label-APK fehlt: ${apk.absolutePath}" }
            logger.lifecycle("Installiere $label auf ${model(serial)} …")
            val output = adb("-s", serial, "install", "-r", "-t", apk.absolutePath).trim()
            check(output.lineSequence().any { it.trim().equals("Success", ignoreCase = true) }) {
                "$label wurde von ADB nicht als erfolgreich bestätigt:\n$output"
            }
        }

        private fun removeAccidentalPhoneCollector(serial: String) {
            val installed =
                adb("-s", serial, "shell", "pm", "list", "packages", "--user", "0", G7_WATCH_PACKAGE)
                    .lineSequence()
                    .any { it.trim() == "package:$G7_WATCH_PACKAGE" }
            if (!installed) return

            logger.lifecycle(
                "Entferne versehentlich auf dem Smartphone installiertes G7-Watch-Paket; " +
                    "Paketdaten bleiben mit -k erhalten …",
            )
            val output =
                adb(
                    "-s",
                    serial,
                    "shell",
                    "pm",
                    "uninstall",
                    "-k",
                    "--user",
                    "0",
                    G7_WATCH_PACKAGE,
                ).trim()
            check(output.lineSequence().any { it.trim().equals("Success", ignoreCase = true) }) {
                "Das Wear-only G7-Watch-Paket konnte auf dem Smartphone nicht sauber entfernt werden:\n$output"
            }
        }

        private fun adb(vararg arguments: String): String {
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val result =
                execOperations.exec {
                    commandLine(adbExecutable(), *arguments)
                    standardOutput = stdout
                    errorOutput = stderr
                    isIgnoreExitValue = true
                }
            val output = stdout.toString(Charsets.UTF_8)
            val error = stderr.toString(Charsets.UTF_8)
            check(result.exitValue == 0) {
                "ADB fehlgeschlagen (${result.exitValue}): adb ${arguments.joinToString(" ")}\n$error\n$output"
            }
            return output
        }

        private fun adbExecutable(): String {
            val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
            val executableName = if (windows) "adb.exe" else "adb"
            val sdkRoot = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
            if (!sdkRoot.isNullOrBlank()) {
                val sdkAdb = File(sdkRoot, "platform-tools/$executableName")
                if (sdkAdb.isFile) return sdkAdb.absolutePath
            }
            return executableName
        }

        private companion object {
            const val G7_WATCH_PACKAGE = "app.aapswear.g7watch"
        }
    }

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

tasks.register<InstallSugarliciousDebugTask>("installSugarliciousDebug") {
    group = "install"
    description = "Builds and installs Mobile on the phone and Wear + G7 Collector + Vigil on the watch."
    dependsOn(
        ":app-mobile:assembleDebug",
        ":app-wear:assembleDebug",
        ":g7watch:assembleDebug",
        ":watchfaces:sugarlicious-direct-to-watch:assembleDebug",
    )
    mobileApk.set(layout.projectDirectory.file("app-mobile/build/outputs/apk/debug/app-mobile-debug.apk"))
    wearApk.set(layout.projectDirectory.file("app-wear/build/outputs/apk/debug/app-wear-debug.apk"))
    g7WatchApk.set(layout.projectDirectory.file("g7watch/build/outputs/apk/debug/g7watch-debug.apk"))
    vigilApk.set(layout.projectDirectory.file("watchfaces/sugarlicious-direct-to-watch/build/outputs/apk/debug/sugarlicious-direct-to-watch-debug.apk"))
}
