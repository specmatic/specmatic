plugins {
    kotlin("jvm")
}

dependencies {
    implementation("ch.qos.logback:logback-core:1.5.37")
    implementation("org.slf4j:slf4j-api:2.0.20")
    implementation("org.junit.platform:junit-platform-launcher:1.14.4")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.4")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.22.4")
    implementation("io.swagger.parser.v3:swagger-parser:${project.property("swaggerParserVersion")}")
    implementation("info.picocli:picocli:4.7.7")
    implementation("com.networknt:json-schema-validator:2.0.8")

    runtimeOnly("ch.qos.logback:logback-classic:1.5.37")

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.4")

    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.14.4")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.14.4")
    testImplementation("org.assertj:assertj-core:3.27.7")
}

val enableConformanceTests = findProperty("enableConformanceTests") as String?
val specmaticVersionForConformanceTests = findProperty("specmaticVersionForConformanceTests") as String?
val succeedOnExpectedFailures = findProperty("succeedOnExpectedFailures") as String?

val conformanceTestResultsJsonlFile = layout.buildDirectory.file("conformance-test-results.jsonl")

tasks.test {
    useJUnitPlatform()

    maxParallelForks = 1

    if (enableConformanceTests?.toBoolean() == true) {
        dependsOn(":specmatic-executable:dockerBuild")
        if(specmaticVersionForConformanceTests != null) {
            systemProperty("specmaticVersionForConformanceTests", specmaticVersionForConformanceTests.toString())
        }
        if(succeedOnExpectedFailures?.toBoolean() == true) {
            systemProperty("succeedOnExpectedFailures", "true")
        }
        val recordsFile = conformanceTestResultsJsonlFile.get().asFile
        systemProperty("conformanceTestResultsJsonlFile", recordsFile.absolutePath)
        doFirst {
            recordsFile.delete()
            recordsFile.resolveSibling("${recordsFile.nameWithoutExtension}.csv").delete()
        }
    } else {
        exclude("io/specmatic/conformance_tests/")
    }
}

val generateConformanceTests = tasks.register<GenerateConformanceTestsTask>("generateConformanceTests") {
    specsDirectory.set(layout.projectDirectory.dir("src/test/resources/specs"))
    generatedSourcesDirectory.set(layout.buildDirectory.dir("generated/sources/conformance/kotlin"))
}

kotlin {
    sourceSets {
        test {
            kotlin.srcDir("build/generated/sources/conformance/kotlin")
        }
    }
}

tasks.named("compileTestKotlin") {
    dependsOn(generateConformanceTests)
}

tasks.register<JavaExec>("generateConformanceSummary") {
    group = "verification"
    description = "Generate markdown summary of conformance test results"
    mainClass.set("io.specmatic.conformance_test_support.GenerateConformanceSummaryCommandKt")
    classpath = sourceSets["main"].runtimeClasspath
    systemProperty("conformanceTestResultsJsonlFile", conformanceTestResultsJsonlFile.get().asFile.absolutePath)
}
