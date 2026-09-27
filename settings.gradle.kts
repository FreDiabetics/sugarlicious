pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.xgouchet") }
        }
    }
}

rootProject.name = "Sugarlicious"

include(
    ":core-model",
    ":dexcom-g7",
    ":data-source-api",
    ":data-source-aaps",
    ":data-source-xdrip",
    ":wear-protocol",
    ":wear-storage",
    ":ui-shared",
    ":complications",
    ":app-mobile",
    ":app-wear",
    ":g7watch",
    ":watchfaces:test-wff",
    ":watchfaces:sugarlicious-digital",
    ":watchfaces:sugarlicious-direct-to-watch",
    ":tools:aaps-cwf-parser",
    ":tools:wff-generator",
    ":tools:screenshot-comparator",
)
