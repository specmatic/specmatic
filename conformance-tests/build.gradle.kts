plugins {
    kotlin("jvm")
}

val cpuCount = Runtime.getRuntime().availableProcessors()

dependencies {
    implementation("ch.qos.logback:logback-core:1.5.32")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("org.junit.platform:junit-platform-launcher:1.14.3")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.32")

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.3")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.14.3")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.14.3")
    testImplementation("org.assertj:assertj-core:3.27.7")

}

tasks.test {
    dependsOn(":specmatic-executable:dockerBuild")
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
    systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", cpuCount.coerceIn(2, 6))
    systemProperty("junit.jupiter.execution.parallel.config.fixed.max-pool-size", cpuCount.toString())
}
