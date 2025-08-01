plugins {
    id("java")
    kotlin("jvm")
    kotlin("plugin.serialization") version "1.9.25"
}

dependencies {
    implementation("io.netty:netty-codec-http:4.2.2.Final")
    implementation("joda-time:joda-time:2.14.0")
    implementation("net.minidev:json-smart:2.5.2")

    implementation("com.ezylang:EvalEx:3.5.0")
    implementation("com.arakelian:java-jq:2.0.0")
    testImplementation("com.arakelian:java-jq:2.0.0")

    implementation("org.assertj:assertj-core:3.27.3")
    implementation("org.junit.jupiter:junit-jupiter-api:5.13.4")

    implementation("info.picocli:picocli:4.7.7")
    implementation("io.ktor:ktor-client-core-jvm:2.3.13")
    implementation("io.ktor:ktor-network-tls:2.3.13")
    implementation("io.ktor:ktor-network-tls-certificates:2.3.13")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.1")

    implementation("org.junit.platform:junit-platform-launcher:1.13.4")
    implementation("org.junit.platform:junit-platform-reporting:1.13.4")

    implementation("org.eclipse.jgit:org.eclipse.jgit:7.3.0.202506031305-r")
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.apache:7.3.0.202506031305-r")

    implementation("org.apache.ant:ant-junit:1.10.15")

    implementation(project(":specmatic-core"))
    implementation(project(":junit5-support"))

    implementation("io.ktor:ktor-client-cio:2.3.13")
    implementation("io.swagger.parser.v3:swagger-parser:2.1.31") {
        exclude(group = "org.mozilla", module = "rhino")
    }
    implementation("org.mozilla:rhino:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.6.3")

    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.4")

    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("com.ginsberg:junit5-system-exit:2.0.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.13.4")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.13.4")
    testImplementation("io.kotest:kotest-assertions-core-jvm:5.9.1")

}

tasks.test {
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        val junit5SystemExit =
            configurations.testRuntimeClasspath.get().files.find { it.name.contains("junit5-system-exit") }

        listOf("-javaagent:$junit5SystemExit")
    })
}
