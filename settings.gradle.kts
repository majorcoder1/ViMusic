pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ViMusic"

include(":app")
include(":compose-routing")
include(":compose-reordering")
include(":compose-persist")
include(":innertube")
include(":ktor-client-brotli")
include(":kugou")
