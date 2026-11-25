rootProject.name = "recl"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

include(":core", ":storage-memory", ":storage-fortis", ":app", ":source-github", ":api-rest", ":mcp", ":dev-mcp", ":index")

val fortisDir = file(providers.gradleProperty("fortis.dir").getOrElse("../fortis"))
if (fortisDir.isDirectory) {
    includeBuild(fortisDir)
    include(":storage-fortis-inproc")
}
