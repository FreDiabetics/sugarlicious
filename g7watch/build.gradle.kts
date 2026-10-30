plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.aapswear.g7watch"
    compileSdk = 37
    defaultConfig {
        applicationId = if (providers.gradleProperty("legacyProductMigration").orNull == "true") "app.aapswear.g7watch" else "app.sugarwear"
        minSdk = 33
        targetSdk = 37
        versionCode = rootProject.extra["sugarliciousSuiteVersionCode"] as Int
        versionName = rootProject.extra["sugarliciousSuiteVersionName"] as String
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

val vigilPreviewDebugAssets = layout.buildDirectory.dir("generated/vigilPreviewDebug/assets")
val syncVigilPreviewDebugAssets =
    tasks.register<Sync>("syncVigilPreviewDebugAssets") {
        into(vigilPreviewDebugAssets)
        from(rootProject.file("watchfaces/sugarlicious-direct-to-watch/src/main/res/raw/watchface.xml")) {
            // Reference application data, not a res/raw Watch Face Format application.
            rename { "vigil_preview_definition.xml" }
        }
        from(rootProject.file("watchfaces/sugarlicious-direct-to-watch/src/main/res/drawable-nodpi")) {
            include("vigil_*markers_close.png", "vigil_*markers_wide.png", "second_hand_transparent.png")
        }
    }
android.sourceSets
    .getByName("debug")
    .assets.directories
    .add(vigilPreviewDebugAssets.get().asFile.path)
tasks.configureEach {
    if (name == "preDebugBuild") dependsOn(syncVigilPreviewDebugAssets)
}

dependencies {
    implementation(project(":dexcom-g7"))
    implementation(project(":wear-storage"))
    implementation(project(":wear-protocol"))
    implementation(project(":ui-shared"))
    implementation(project(":complications"))
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("com.google.guava:guava:33.7.2-android")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.17")
}

abstract class CollectorPreviewResources : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val previewFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:javax.inject.Inject
    abstract val fileOperations: org.gradle.api.file.FileSystemOperations

    @TaskAction
    fun stage() {
        fileOperations.sync {
            from(previewFiles)
            into(outputDirectory.dir("xml"))
        }
    }
}

val syncCollectorComplicationPreviews =
    tasks.register<CollectorPreviewResources>("syncCollectorComplicationPreviews") {
        outputDirectory.set(layout.buildDirectory.dir("generated/collectorComplicationPreviews/res"))
        previewFiles.from(
            rootProject.fileTree("app-wear/src/main/res/xml") {
                include("complication_preview_01a_short.xml", "complication_preview_01b_long.xml", "complication_preview_01c_ranged.xml")
                include("complication_preview_02a_short.xml", "complication_preview_02b_long.xml", "complication_preview_02c_ranged.xml")
                include("complication_preview_08_short.xml", "complication_preview_11_short.xml")
            },
        )
    }
androidComponents.onVariants { variant ->
    variant.sources.res?.addGeneratedSourceDirectory(syncCollectorComplicationPreviews) { it.outputDirectory }
}
