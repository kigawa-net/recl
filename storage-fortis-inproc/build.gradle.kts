plugins { kotlin("jvm") }

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core"))
    implementation(project(":storage-fortis"))
    implementation("fortis:raft")
    implementation("fortis:storage-engine")
    implementation("fortis:io")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    testImplementation(testFixtures(project(":core")))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }
