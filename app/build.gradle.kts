plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(21) }

application { mainClass.set("net.kigawa.recl.app.MainKt") }

dependencies { implementation(project(":core")) }
