plugins {
    kotlin("jvm") version "2.2.20" apply false
}

allprojects {
    group = "net.kigawa.recl"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    repositories { mavenCentral() }
}
