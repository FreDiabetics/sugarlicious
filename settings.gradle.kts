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
    ":watchfaces:aaps-v4",
    ":watchfaces:aaps-v2",
    ":watchfaces:aaps-circle",
    ":watchfaces:aaps-digital-style",
    ":watchfaces:aaps-standard",
    ":watchfaces:aaps-big-chart",
    ":watchfaces:aaps-large",
    ":watchfaces:aaps-no-chart",
    ":watchfaces:aaps-cockpit",
    ":watchfaces:aaps-v2-tt-dark",
    ":watchfaces:aaps-community",
    ":watchfaces:aimico",
    ":watchfaces:analog-g-watch",
    ":watchfaces:blue-ring",
    ":watchfaces:digital-big-graph",
    ":watchfaces:digital-g-watch",
    ":watchfaces:gears",
    ":watchfaces:gota",
    ":watchfaces:lucky-loop-koeln",
    ":watchfaces:p-zero",
    ":watchfaces:robby",
    ":watchfaces:simple-digital",
    ":watchfaces:steam-punk",
    ":watchfaces:sugarlicious-digital",
    ":watchfaces:sugarlicious-analog",
    ":watchfaces:sugarlicious-orbit",
    ":watchfaces:sugarlicious-rings",
    ":watchfaces:sugarlicious-graph",
    ":watchfaces:sugarlicious-direct-to-watch",
    ":tools:aaps-cwf-parser",
    ":tools:wff-generator",
    ":tools:screenshot-comparator",
)
