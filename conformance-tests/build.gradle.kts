plugins {
    kotlin("jvm")
}

dependencies {
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.14.2")
testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.junit.platform:junit-platform-launcher:1.14.2")
}

tasks.test {
    useJUnitPlatform()
    val parallelism = minOf(Runtime.getRuntime().availableProcessors(), 8)
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
    systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", parallelism)
}
