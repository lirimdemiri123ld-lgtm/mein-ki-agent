plugins {
    kotlin("jvm") version "2.2.21"
    application
}
dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-jvm:0.18.1")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.4.0")
    implementation("org.json:json:20240303")
}
kotlin {
    jvmToolchain(21)
    compilerOptions { freeCompilerArgs.addAll(listOf("-Xskip-metadata-version-check")) }
}
application { mainClass.set("MainKt") }
