plugins {
    id("java")
    kotlin("jvm")
    kotlin("plugin.serialization") version "1.9.25"
}

dependencies {
    implementation("io.specmatic.build-reporter:specmatic-reporter-min:${project.property("specmaticReporterVersion")}") {
        exclude(group = "commons-logging", module = "commons-logging")
    }

    implementation("com.ezylang:EvalEx:3.6.0")
    implementation(project(":specmatic-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.assertj:assertj-core:3.27.7")

    implementation("org.junit.jupiter:junit-jupiter-engine:5.13.4")
    implementation("org.junit.jupiter:junit-jupiter-api:5.13.4")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.13.4")

    implementation("io.ktor:ktor-client-core-jvm:2.3.13")
    implementation("io.ktor:ktor-client-cio:2.3.13")

    implementation("org.junit.platform:junit-platform-launcher:1.13.4")
    implementation("org.junit.platform:junit-platform-reporting:1.13.4")

    implementation("org.fusesource.jansi:jansi:2.4.2")
}
