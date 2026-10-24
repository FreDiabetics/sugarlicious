plugins {
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.android.application")
}

val generatedAnalogPreviewRes = layout.buildDirectory.dir("generated/sugarliciousAnalogPreviewRes")
val generatedVigilPreviewRes = layout.buildDirectory.dir("generated/vigilPreviewRes")
val generatedOrbitPreviewRes = layout.buildDirectory.dir("generated/orbitPreviewRes")
val syncOrbitPreviewAsset =
    tasks.register<Sync>("syncOrbitPreviewAsset") {
        from(rootProject.file("watchfaces/sugarlicious-orbit/src/main/res/drawable-nodpi/preview.png")) {
            rename { "sugarlicious_orbit_preview.png" }
        }
        into(generatedOrbitPreviewRes.map { it.dir("drawable-nodpi") })
    }
val syncSugarliciousAnalogPreviewAssets =
    tasks.register<Sync>("syncSugarliciousAnalogPreviewAssets") {
        from(rootProject.file("watchfaces/sugarlicious-analog/src/main/res/drawable-nodpi/apex_template_preview.png"))
        into(generatedAnalogPreviewRes)
        eachFile { path = "drawable-nodpi/$name" }
        includeEmptyDirs = false
    }
val syncVigilPreviewAsset =
    tasks.register<Sync>("syncVigilPreviewAsset") {
        from(rootProject.file("watchfaces/sugarlicious-direct-to-watch/src/main/res/drawable-nodpi/preview.png")) {
            rename { "vigil_preview.png" }
        }
        into(generatedVigilPreviewRes)
        eachFile { path = "drawable-nodpi/$name" }
        includeEmptyDirs = false
    }

android {
    buildFeatures { compose = true }

    namespace = "app.aapswear.mobile"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.aapswear"
        minSdk = 26
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = rootProject.extra["sugarliciousSuiteVersionCode"] as Int
        versionName = rootProject.extra["sugarliciousSuiteVersionName"] as String
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets["main"].res.directories.add(generatedAnalogPreviewRes.get().asFile.path)
    sourceSets["main"].res.directories.add(generatedVigilPreviewRes.get().asFile.path)
    sourceSets["main"].res.directories.add(generatedOrbitPreviewRes.get().asFile.path)
}

tasks.matching { task -> task.name == "preBuild" }.configureEach {
    dependsOn(syncSugarliciousAnalogPreviewAssets)
    dependsOn(syncVigilPreviewAsset)
    dependsOn(syncOrbitPreviewAsset)
}

dependencies {
    implementation("androidx.core:core:1.19.1")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation(project(":data-source-aaps"))
    implementation(project(":wear-protocol"))
    implementation(project(":wear-storage"))
    implementation(project(":ui-shared"))
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.17")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
