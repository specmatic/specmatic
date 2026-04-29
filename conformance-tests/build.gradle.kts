plugins {
    kotlin("jvm")
}
dependencies {
    implementation("ch.qos.logback:logback-core:1.5.32")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("org.junit.platform:junit-platform-launcher:1.14.4")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.21.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.21.2")
    implementation("io.swagger.parser.v3:swagger-parser:${project.property("swaggerParserVersion")}")
    implementation("info.picocli:picocli:4.7.7")
    implementation("com.networknt:json-schema-validator:3.0.2")

    runtimeOnly("ch.qos.logback:logback-classic:1.5.32")

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.4")

    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.14.4")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.14.4")
    testImplementation("org.assertj:assertj-core:3.27.7")
}

val enableConformanceTests: String? by project
val specmaticVersionForConformanceTests: String? by project
val succeedOnExpectedFailures: String? by project

val expectedFailuresJsonlFile = layout.buildDirectory.file("conformance-expected-failures.jsonl")

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
        val recordsFile = expectedFailuresJsonlFile.get().asFile
        doFirst {
            recordsFile.delete()
        }
    } else {
        exclude("io/specmatic/conformance_tests/")
    }
}

val generateConformanceTests by tasks.registering {
    val specsDir = file("src/test/resources/specs")
    val outputDir = file("build/generated/sources/conformance/kotlin")

    inputs.dir(specsDir)
    outputs.dir(outputDir)

    doLast {
        outputDir.deleteRecursively()
        outputDir.mkdirs()

        val specFiles = specsDir.walkTopDown()
            .filter { it.isFile && it.extension in listOf("yaml", "yml") }
            .map { it.relativeTo(specsDir).path.replace("\\", "/") }
            .sorted()
            .toList()

        val header = """package io.specmatic.conformance_tests
import org.junit.jupiter.api.DisplayName
"""

        val classes = specFiles.joinToString("\n") { relativePath ->
            val className = generateClassName(relativePath)
            val displayName = relativePath.substringBefore(".")

            """
                |@DisplayName("$displayName")
                |class $className : AbstractConformanceTest("$relativePath")
                |""".trimMargin()
        }

        outputDir.resolve("ConformanceTests.kt").writeText(header + "\n" + classes)

        println("Generated ${specFiles.size} conformance test classes")
    }
}

fun generateClassName(relativePath: String): String {
    val segments = relativePath.split("/")
    return "S" + segments.joinToString("_") { segment ->
        segment.removeSuffix(".yaml").removeSuffix(".yml")
            .split("-")
            .joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }
    } + "Test"
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

tasks.register<JavaExec>("generateSummaryOfExpectedFailures") {
    group = "verification"
    description = "Generate markdown summary of expected conformance test failures"
    mainClass.set("io.specmatic.conformance_test_support.GenerateSummaryOfExpectedFailuresCommandKt")
    classpath = sourceSets["main"].runtimeClasspath
    systemProperty("expectedFailuresJsonlFile", expectedFailuresJsonlFile.get().asFile.absolutePath)
}
