plugins {
    id("java")
    kotlin("jvm")
    kotlin("plugin.serialization") version "1.9.25"
}

dependencies {
    implementation("io.specmatic.build-reporter:specmatic-reporter-min:${project.property("specmaticReporterVersion")}") {
        exclude(group = "commons-logging", module = "commons-logging")
    }

    implementation("joda-time:joda-time:2.14.0")
    implementation("net.minidev:json-smart:2.6.0")

    implementation("com.ezylang:EvalEx:3.6.0")
    implementation("com.arakelian:java-jq:2.0.0")
    testImplementation("com.arakelian:java-jq:2.0.0")

    implementation("org.assertj:assertj-core:3.27.7")
    implementation("org.junit.jupiter:junit-jupiter-api:5.14.2")

    implementation("info.picocli:picocli:4.7.7")
    implementation("io.ktor:ktor-client-core-jvm:2.3.13")
    implementation("io.ktor:ktor-network-tls-jvm:2.3.13")
    implementation("io.ktor:ktor-network-tls-certificates-jvm:2.3.13")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.21.0")

    implementation("org.junit.platform:junit-platform-launcher:1.14.2")
    implementation("org.junit.platform:junit-platform-reporting:1.14.2")

    implementation("org.eclipse.jgit:org.eclipse.jgit:7.4.0.202509020913-r")
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.apache:7.4.0.202509020913-r")

    implementation("org.apache.ant:ant-junit:1.10.15")

    implementation(project(":specmatic-core"))
    implementation(project(":junit5-support"))
    implementation(project(":specmatic-mcp"))

    implementation("io.ktor:ktor-client-cio-jvm:2.3.13")
    implementation("io.swagger.parser.v3:swagger-parser:2.1.36") {
        exclude(group = "org.mozilla", module = "rhino")
    }
    implementation("org.mozilla:rhino:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.6.3")

    implementation("org.jetbrains.kotlin:kotlin-reflect:1.9.25")

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.2")

    testImplementation("io.mockk:mockk-jvm:1.14.9")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("com.ginsberg:junit5-system-exit:2.0.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.14.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.14.2")
    testImplementation("io.kotest:kotest-assertions-core-jvm:6.0.7")

}

tasks.test {
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        val junit5SystemExit =
            configurations.testRuntimeClasspath.get().files.find { it.name.contains("junit5-system-exit") }

        listOf("-javaagent:$junit5SystemExit")
    })
}
