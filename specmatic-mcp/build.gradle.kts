plugins {
    id("java")
    kotlin("jvm")
}

dependencies {
    implementation(project(":specmatic-core"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.ktor:ktor-client-core:2.3.13")
    implementation("io.ktor:ktor-client-cio:2.3.13")
    implementation("io.ktor:ktor-serialization-jackson:2.3.13")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.13")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.1")
    implementation("com.fasterxml.jackson.core:jackson-core:2.19.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.1")

    implementation("org.junit.platform:junit-platform-launcher:1.13.4")
    implementation("org.junit.platform:junit-platform-reporting:1.13.4")
    implementation("org.junit.jupiter:junit-jupiter-api:5.13.4")
    implementation("org.junit.jupiter:junit-jupiter-engine:5.13.4")
    implementation("org.assertj:assertj-core:3.27.6")

    implementation("info.picocli:picocli:4.7.7")
    implementation("org.fusesource.jansi:jansi:2.4.2")

    testImplementation(kotlin("test"))
}
