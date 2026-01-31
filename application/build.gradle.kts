plugins {
    id("java")
    kotlin("jvm")
    kotlin("plugin.serialization") version "1.9.25"
}

dependencies {
    implementation("io.specmatic.build-reporter:specmatic-reporter-min:${project.property("specmaticReporterVersion")}") {
        exclude(group = "commons-logging", module = "commons-logging")
    }

    implementation("io.netty:netty-codec-http:4.2.7.Final")

    implementation("com.ezylang:EvalEx:3.6.0")

    implementation("org.assertj:assertj-core:3.27.7")
    implementation("org.junit.jupiter:junit-jupiter-api:5.13.4")

    implementation("io.ktor:ktor-client-core-jvm:2.3.13")
    implementation("io.ktor:ktor-network-tls:2.3.13")
    implementation("io.ktor:ktor-network-tls-certificates:2.3.13")

    implementation("org.junit.platform:junit-platform-launcher:1.13.4")
    implementation("org.junit.platform:junit-platform-reporting:1.13.4")

    implementation("org.eclipse.jgit:org.eclipse.jgit:7.4.0.202509020913-r")
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.apache:7.4.0.202509020913-r")

    implementation(project(":specmatic-core"))
    implementation(project(":junit5-support"))
    implementation(project(":specmatic-mcp"))

    implementation("io.ktor:ktor-client-cio:2.3.13")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.6.3")

    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.4")

    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("com.ginsberg:junit5-system-exit:2.0.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.13.4")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.13.4")
    testImplementation("io.kotest:kotest-assertions-core-jvm:6.0.7")

}

tasks.test {
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        val junit5SystemExit =
            configurations.testRuntimeClasspath.get().files.find { it.name.contains("junit5-system-exit") }

        listOf("-javaagent:$junit5SystemExit")
    })
}
