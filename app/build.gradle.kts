plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(21) }

application { mainClass.set("net.kigawa.recl.app.MainKt") }

dependencies {
    implementation(project(":core"))
    implementation(project(":mcp"))
    implementation(project(":storage-memory"))
    implementation("com.nimbusds:nimbus-jose-jwt:10.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
