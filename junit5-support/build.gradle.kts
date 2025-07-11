plugins {
    id("java")
    kotlin("jvm")
    kotlin("plugin.serialization") version "1.9.25"
}

dependencies {
    implementation("io.netty:netty-codec-http:4.2.2.Final")
    implementation("net.minidev:json-smart:2.5.2")
    implementation("com.ezylang:EvalEx:3.5.0")
    implementation(project(":specmatic-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.assertj:assertj-core:3.27.3")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.1")

    implementation("org.junit.jupiter:junit-jupiter-engine:${project.ext["org.junit.jupiter.version"]}")
    implementation("org.junit.jupiter:junit-jupiter-api:${project.ext["org.junit.jupiter.version"]}")
    testImplementation("org.junit.jupiter:junit-jupiter-params:${project.ext["org.junit.jupiter.version"]}")

    implementation("org.thymeleaf:thymeleaf:3.1.3.RELEASE")

    implementation("io.ktor:ktor-client-core-jvm:2.3.13")
    implementation("io.ktor:ktor-client-cio:2.3.13")

    implementation("org.junit.platform:junit-platform-launcher:${project.ext["org.junit.platform.version"]}")
    implementation("org.junit.platform:junit-platform-reporting:${project.ext["org.junit.platform.version"]}")

    implementation("org.fusesource.jansi:jansi:2.4.2")
}
