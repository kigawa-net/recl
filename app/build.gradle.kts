plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(21) }

application { mainClass.set("net.kigawa.recl.app.MainKt") }

val fortisInproc = findProject(":storage-fortis-inproc")

dependencies {
    implementation(project(":core"))
    implementation(project(":mcp"))
    implementation(project(":storage-memory"))
    fortisInproc?.let { implementation(it) }
    implementation("com.nimbusds:nimbus-jose-jwt:10.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("recl.test.fortisIncluded", fortisInproc != null)
}
