plugins {
    kotlin("jvm")
}

dependencies {
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.14.2")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.junit.platform:junit-platform-launcher:1.14.2")
    testImplementation("io.swagger.parser.v3:swagger-parser:2.1.37")
    testImplementation("com.networknt:json-schema-validator:1.5.6")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.21.1")
}

tasks.test {
    useJUnitPlatform()
}
