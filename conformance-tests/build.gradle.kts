plugins {
    kotlin("jvm")
}
dependencies {
    implementation("ch.qos.logback:logback-core:1.5.37")
    implementation("org.slf4j:slf4j-api:2.0.19")
    implementation("org.junit.platform:junit-platform-launcher:1.14.4")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.3")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.22.3")
    implementation("io.swagger.parser.v3:swagger-parser:${project.property("swaggerParserVersion")}")
    implementation("info.picocli:picocli:4.7.7")
    implementation("com.networknt:json-schema-validator:2.0.1")

    runtimeOnly("ch.qos.logback:logback-classic:1.5.37")

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.4")

    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.14.4")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.14.4")
    testImplementation("org.assertj:assertj-core:3.27.7")
}

val enableConformanceTests: String? by project
val specmaticVersionForConformanceTests: String? by project
val succeedOnExpectedFailures: String? by project

val conformanceTestResultsJsonlFile = layout.buildDirectory.file("conformance-test-results.jsonl")

tasks.test {
    useJUnitPlatform()

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

val generateConformanceTests by tasks.registering(GenerateConformanceTestsTask::class) {
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
