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

rootProject.name = "tunnel"
include(":app")
include(":core:ui")
include(":core:data")
include(":core:database")
include(":feature:profiles")
include(":feature:connection")
