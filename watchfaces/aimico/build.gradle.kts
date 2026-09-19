plugins { id("com.android.application") }
android {
    enableKotlin = false
    namespace = "app.aapswear.watchface.aimico"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.aapswear.watchfacepush.aimico"
        minSdk = 33
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { buildConfig = false }
    packaging { resources.excludes += setOf("kotlin/**", "META-INF/*.version", "META-INF/*.kotlin_module") }
    lint { checkReleaseBuilds = false }
}
configurations.configureEach {
    if (name.endsWith("RuntimeClasspath") ||
        name.endsWith("CompileClasspath")
    ) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
}
