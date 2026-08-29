plugins {
    id("com.android.library")
}

android {
    namespace = "app.aapswear.uishared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(project(":core-model"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
