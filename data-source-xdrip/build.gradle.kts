plugins { id("com.android.library") }

android {
    namespace = "app.aapswear.datasource.xdrip"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
}

dependencies {
    api(project(":data-source-api"))
    testImplementation("junit:junit:4.13.2")
}
