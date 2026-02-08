plugins {
    id("java")
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.3.0"
}

dependencies {
    implementation("io.netty:netty-codec-http:4.2.9.Final")
    implementation("io.netty:netty-codec-http2:4.2.9.Final")

    implementation("io.specmatic.build-reporter:specmatic-reporter-min:${project.property("specmaticReporterVersion")}") {
        exclude(group = "commons-logging", module = "commons-logging")
    }
    implementation("joda-time:joda-time:2.14.0")
    implementation("net.minidev:json-smart:2.6.0")

    implementation("com.ezylang:EvalEx:3.6.0")
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
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.21.0")
    implementation("com.jayway.jsonpath:json-path:2.10.0")
    implementation("io.github.zenwave360:json-schema-ref-parser-jvm:0.8.10")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.10.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.3.0")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.4.0.202509020913-r")
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.apache:7.4.0.202509020913-r")
    implementation("com.fasterxml.jackson.core:jackson-core:2.21.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.0")

    implementation("com.flipkart.zjsonpatch:zjsonpatch:0.4.16")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.0")

    implementation("io.swagger.parser.v3:swagger-parser:2.1.37")

    implementation("dk.brics:automaton:1.12-4")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.2")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.14.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.14.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.springframework:spring-web:6.2.15")
    testImplementation("io.mockk:mockk-jvm:1.14.9")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("io.ktor:ktor-client-mock-jvm:2.3.13")
    implementation("org.thymeleaf:thymeleaf:3.1.3.RELEASE")
    implementation("org.junit.platform:junit-platform-launcher:1.14.2")
}

configurations.implementation.configure {
    exclude(group = "commons-logging", module = "commons-logging")
}

configurations.testImplementation.configure {
    exclude(group = "commons-logging", module = "commons-logging")
    exclude(group = "org.springframework", module = "spring-jcl")
}
