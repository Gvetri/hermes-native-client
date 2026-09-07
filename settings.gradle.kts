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

rootProject.name = "hermes-native-client"

include(":app")
include(":feature:entry:domain")
include(":feature:entry:application")
include(":feature:entry:data")
include(":feature:entry:presentation")
include(":feature:entry:wiring")
include(":fixtures:hermes:runner")
