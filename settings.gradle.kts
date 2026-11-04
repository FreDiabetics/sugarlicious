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
    ":cgm-protocol",
    ":data-source-api",
    ":data-source-therapy",
    ":data-source-manufacturer",
    ":data-source-external",
    ":wear-protocol",
    ":wear-storage",
    ":ui-shared",
    ":complications",
    ":app-mobile",
    ":app-wear",
    ":watchfaces:test-wff",
    ":watchfaces:sugarlicious-digital",
    ":watchfaces:sugarlicious-analog",
    ":watchfaces:sugarlicious-orbit",
    ":tools:watchface-package-parser",
    ":tools:wff-generator",
    ":tools:screenshot-comparator",
    ":tools:maintenance",
)
