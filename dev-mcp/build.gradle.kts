plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(21) }

application { mainClass.set("net.kigawa.recl.devmcp.DevMcpMain") }

dependencies {
    implementation(project(":core"))
    implementation(project(":mcp"))
    implementation(project(":storage-memory"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }
