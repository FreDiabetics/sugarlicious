plugins { id("com.android.library") }
android {
    namespace="app.aapswear.complications"
    compileSdk=36
    defaultConfig { minSdk=33 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    api(project(":wear-storage"))
    implementation(project(":wear-protocol"))
    implementation(project(":ui-shared"))
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
