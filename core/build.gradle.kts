plugins {
    kotlin("jvm")
    `java-test-fixtures`
}

kotlin { jvmToolchain(21) }

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Shared ContextStorage contract cases, reused by every storage adapter's test suite.
    testFixturesApi("org.junit.jupiter:junit-jupiter-api:5.12.2")
}

tasks.test { useJUnitPlatform() }
