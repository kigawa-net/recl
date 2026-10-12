rootProject.name = "recl"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

include(":core", ":storage-memory", ":app", ":source-github")
