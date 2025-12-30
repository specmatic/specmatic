plugins {
    id("java")
    kotlin("jvm")
    kotlin("plugin.serialization") version "1.9.25"
}

dependencies {
    implementation("io.specmatic.build-reporter:specmatic-reporter-min:${project.ext["specmaticReporterVersion"]}") {
        exclude(group = "commons-logging", module = "commons-logging")
    }

    implementation("com.ezylang:EvalEx:3.6.0")

    implementation("org.junit.jupiter:junit-jupiter-api:5.13.4")

    implementation("info.picocli:picocli:4.7.7")
    implementation("io.ktor:ktor-client-core-jvm:2.3.13")
    implementation("io.ktor:ktor-network-tls-certificates:2.3.13")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.1")

    implementation("org.junit.platform:junit-platform-launcher:1.13.4")
    implementation("org.junit.platform:junit-platform-reporting:1.13.4")

    implementation(project(":specmatic-core"))
    implementation(project(":junit5-support"))
    implementation(project(":specmatic-mcp"))

    implementation("io.ktor:ktor-client-cio:2.3.13")

    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.4")

    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("org.assertj:assertj-core:3.27.6")
    testImplementation("com.ginsberg:junit5-system-exit:2.0.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.13.4")

}

tasks.test {
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        val junit5SystemExit =
            configurations.testRuntimeClasspath.get().files.find { it.name.contains("junit5-system-exit") }

        listOf("-javaagent:$junit5SystemExit")
    })
}
