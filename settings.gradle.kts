rootProject.name = "recl"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

include(":core", ":storage-memory", ":storage-fortis", ":app", ":source-github", ":api-rest", ":mcp", ":dev-mcp", ":index")
