plugins { kotlin("jvm") }

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.4")
    testImplementation(project(":storage-memory"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }
