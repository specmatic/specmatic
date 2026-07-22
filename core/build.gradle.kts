plugins {
    id("java")
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.3.21"
}

dependencies {
    // Being explicitly added due a HIGH vulnerability GHSA-rwm7-x88c-3g2p, being pulled transitively
    // Remove and upgrade io.ktor:ktor-server-netty-jvm:2.3.13 once it has been updated
    implementation("io.netty:netty-transport-native-epoll:4.2.15.Final")
    implementation("io.netty:netty-codec-http:4.2.15.Final")
    implementation("io.netty:netty-codec-http2:4.2.15.Final")
    implementation("io.specmatic.build-reporter:specmatic-reporter-min:${project.property("specmaticReporterVersion")}") {
        exclude(group = "commons-logging", module = "commons-logging")
        exclude(group = "io.swagger.parser.v3", module = "swagger-parser")
    }
    implementation("joda-time:joda-time:2.14.2")
    implementation("net.minidev:json-smart:2.6.0")

    implementation("com.ezylang:EvalEx:3.6.2")
    implementation("org.apache.commons:commons-lang3:3.20.0")
    implementation("io.cucumber:gherkin:33.0.0")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.13")
    implementation("io.ktor:ktor-server-core-jvm:2.3.13")
    implementation("io.ktor:ktor-client-core-jvm:2.3.13")
    implementation("io.ktor:ktor-client-apache-jvm:2.3.13")
    implementation("io.ktor:ktor-server-cors-jvm:2.3.13")
    implementation("io.ktor:ktor-server-double-receive-jvm:2.3.13")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:2.3.13")
    implementation("io.ktor:ktor-serialization-jackson-jvm:2.3.13")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.1")
    implementation("com.jayway.jsonpath:json-path:2.10.0")

    implementation("io.zenwave360:json-schema-ref-parser-jvm:0.9.12")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.11.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.3.21")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.4.0.202509020913-r")
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.apache:7.4.0.202509020913-r")
    implementation("com.fasterxml.jackson.core:jackson-core:2.22.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")

    implementation("com.flipkart.zjsonpatch:zjsonpatch:0.4.16")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")

    implementation("io.swagger.parser.v3:swagger-parser:${project.property("swaggerParserVersion")}")

    implementation("dk.brics:automaton:1.12-4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.4")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.14.4")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.14.4")
    testImplementation("org.json:json:20250517")
    testImplementation("com.networknt:json-schema-validator:2.0.1")
    testImplementation("org.springframework:spring-web:7.0.8")
    testImplementation("io.mockk:mockk-jvm:1.14.11")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("io.ktor:ktor-client-mock-jvm:2.3.13")
    implementation("org.junit.platform:junit-platform-launcher:1.14.4")
}

configurations.implementation.configure {
    exclude(group = "commons-logging", module = "commons-logging")
}

configurations.testImplementation.configure {
    exclude(group = "commons-logging", module = "commons-logging")
    exclude(group = "org.springframework", module = "spring-jcl")
}
