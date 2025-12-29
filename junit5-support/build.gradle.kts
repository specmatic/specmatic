plugins {
    id("java")
    kotlin("jvm")
    kotlin("plugin.serialization") version "1.9.25"
}

dependencies {
    implementation("io.specmatic.build-reporter:specmatic-reporter-min:${project.ext["specmaticReporterVersion"]}") {
        exclude(group = "commons-logging", module = "commons-logging")
    }

    implementation("io.netty:netty-codec-http:4.2.7.Final")
    implementation("net.minidev:json-smart:2.6.0")
    implementation("com.ezylang:EvalEx:3.5.0")
    implementation(project(":specmatic-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.assertj:assertj-core:3.27.6")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.1")

    implementation("org.junit.jupiter:junit-jupiter-engine:5.13.4")
    implementation("org.junit.jupiter:junit-jupiter-api:5.13.4")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.13.4")

    implementation("org.thymeleaf:thymeleaf:3.1.3.RELEASE")

    implementation("io.ktor:ktor-client-core-jvm:2.3.13")
    implementation("io.ktor:ktor-client-cio:2.3.13")

    implementation("org.junit.platform:junit-platform-launcher:1.13.4")
    implementation("org.junit.platform:junit-platform-reporting:1.13.4")

    implementation("org.fusesource.jansi:jansi:2.4.2")
}
