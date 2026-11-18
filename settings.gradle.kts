// Gradle 8.7 settings for SensorGuard (no local wrapper required)
pluginManagement {
    repositories {
        // Resolve AGP and Kotlin plugins from Google and Maven Central
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Enforce centralized repository declarations across modules
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SensorGuard"
include(":app")
