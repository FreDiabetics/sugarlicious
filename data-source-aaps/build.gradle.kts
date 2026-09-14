plugins { id("com.android.library") }
android {
    namespace = "app.aapswear.datasource.aaps"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    testOptions {
        unitTests.isIncludeAndroidResources =
            true
    }
}
dependencies {
    api(project(":data-source-api"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
tasks.withType<Test>().configureEach { useJUnitPlatform() }
