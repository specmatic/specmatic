plugins {
    id("java")
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.3.0"
}

dependencies {
    implementation("io.specmatic.build-reporter:specmatic-reporter-min:${project.property("specmaticReporterVersion")}") {
        exclude(group = "commons-logging", module = "commons-logging")
    }

    implementation("net.minidev:json-smart:2.6.0")
    implementation("com.ezylang:EvalEx:3.6.0")
    implementation(project(":specmatic-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.10.0")
    implementation("org.assertj:assertj-core:3.27.7")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.0")

    implementation("org.junit.jupiter:junit-jupiter-engine:5.14.2")
    implementation("org.junit.jupiter:junit-jupiter-api:5.14.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.14.2")

    implementation("org.thymeleaf:thymeleaf:3.1.3.RELEASE")

    implementation("io.ktor:ktor-client-core-jvm:2.3.13")
    implementation("io.ktor:ktor-client-cio-jvm:2.3.13")

    implementation("org.junit.platform:junit-platform-launcher:1.14.2")
    implementation("org.junit.platform:junit-platform-reporting:1.14.2")

    implementation("org.fusesource.jansi:jansi:2.4.2")
}
