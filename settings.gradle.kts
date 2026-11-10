pluginManagement {
    plugins {
        id("com.android.application") version "8.6.1"
        id("org.jetbrains.kotlin.android") version "1.9.24"
    }
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
        maven("https://jitpack.io")
    }
}
rootProject.name = "fotoproje"
include(":app")
