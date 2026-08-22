import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

plugins { id("com.android.application") }

@CacheableTask
abstract class PrepareDefaultWatchFaceTask
    @Inject
    constructor(
        private val execOperations: ExecOperations,
    ) : DefaultTask() {
        @get:InputFile
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val watchFaceApk: RegularFileProperty

        @get:Classpath
        abstract val validatorJars: ConfigurableFileCollection

        @get:OutputFile
        abstract val outputApk: RegularFileProperty

        @get:OutputFile
        abstract val outputTokenResource: RegularFileProperty

        @TaskAction
        fun prepare() {
            val validator =
                validatorJars.files
                    .filter { file ->
                        file.name.startsWith("validator-push-cli-") && file.extension == "jar"
                    }
                    .maxByOrNull { it.lastModified() }
                    ?: error("Watch Face Push validator CLI is missing")
            val validatorOutput = ByteArrayOutputStream()
            val result =
                execOperations.exec {
                    commandLine(
                        "java",
                        "-jar",
                        validator.absolutePath,
                        "--apk_path=${watchFaceApk.get().asFile.absolutePath}",
                        "--package_name=app.aapswear",
                    )
                    standardOutput = validatorOutput
                    errorOutput = validatorOutput
                    isIgnoreExitValue = true
                }
            val output = validatorOutput.toString(Charsets.UTF_8)
            check(result.exitValue == 0) { output }
            val token =
                Regex("(?im)(?:Validation token:|generated token:)\\s*([^\\r\\n]+)")
                    .find(output)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: error("Default watchface validation token is missing:\n$output")

            val asset = outputApk.get().asFile
            asset.parentFile.mkdirs()
            watchFaceApk.get().asFile.copyTo(asset, overwrite = true)

            val resource = outputTokenResource.get().asFile
            resource.parentFile.mkdirs()
            resource.writeText(
                "<resources>\n" +
                    "    <string name=\"default_wf_token\" translatable=\"false\">$token</string>\n" +
                    "</resources>\n",
            )
        }
    }

val generatedWatchFaceAssets = layout.buildDirectory.dir("generated/watchfacePushAssets")
val generatedWatchFaceResources = layout.buildDirectory.dir("generated/watchfacePushRes")
val defaultWatchFaceApk =
    rootProject.layout.projectDirectory.file(
        "watchfaces/sugarlicious-analog/build/outputs/apk/release/sugarlicious-analog-release.apk",
    )
val validatorDirectory = rootProject.layout.buildDirectory.dir("watchface-push/tools")

val prepareDefaultWatchFace = tasks.register<PrepareDefaultWatchFaceTask>("prepareDefaultWatchFace") {
    dependsOn(
        ":watchfaces:sugarlicious-analog:assembleRelease",
        ":prepareWatchFaceValidatorCli",
    )

    watchFaceApk.set(defaultWatchFaceApk)
    validatorJars.from(
        rootProject.fileTree(validatorDirectory) {
            include("validator-push-cli-*.jar")
        },
    )
    outputApk.set(generatedWatchFaceAssets.map { it.file("default_watchface.apk") })
    outputTokenResource.set(
        generatedWatchFaceResources.map {
            it.file("values/default_watchface_token.xml")
        },
    )
}

tasks.configureEach {
    if (
        name != prepareDefaultWatchFace.name &&
        (
            name.contains("Assets") ||
                name.contains("Resources") ||
                name.endsWith("SourceSetPaths")
        )
    ) {
        dependsOn(prepareDefaultWatchFace)
    }
}

android {
    namespace="app.aapswear.wear"
    compileSdk=37
    defaultConfig { applicationId="app.aapswear"; minSdk=33; targetSdk=36; versionCode=12; versionName="0.6.2" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets["main"].assets.directories.add(generatedWatchFaceAssets.get().asFile.path)
    sourceSets["main"].res.directories.add(generatedWatchFaceResources.get().asFile.path)
}
dependencies {
    implementation(project(":wear-protocol"))
    implementation(project(":wear-storage"))
    implementation(project(":complications"))
    implementation(project(":dexcom-g7"))
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.3.0")
    implementation("androidx.wear.watchfacepush:watchfacepush:1.0.0")
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-material3:1.4.2")
    implementation("com.google.guava:guava:33.5.0-android")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
