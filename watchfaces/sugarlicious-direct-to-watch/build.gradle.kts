plugins { id("com.android.application") }
android {
    enableKotlin=false
    namespace="app.aapswear.watchface.sugarlicious.directtowatch"
    compileSdk=36
    defaultConfig {
        applicationId="app.aapswear.watchfacepush.g6style"
        minSdk=33
        targetSdk=35
        versionCode=2
        versionName="0.1.1"
    }
    buildTypes {
        debug { isMinifyEnabled = true }
        release {
            isMinifyEnabled=true
            isShrinkResources=false
            signingConfig=signingConfigs.getByName("debug")
        }
    }
    buildFeatures { buildConfig=false }
    packaging { resources.excludes+=setOf("kotlin/**","META-INF/*.version","META-INF/*.kotlin_module") }
    lint { checkReleaseBuilds=false }
}
configurations.configureEach { if(name.endsWith("RuntimeClasspath")||name.endsWith("CompileClasspath")) exclude(group="org.jetbrains.kotlin",module="kotlin-stdlib") }
