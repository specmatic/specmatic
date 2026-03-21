plugins {
    kotlin("jvm")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.14.2")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.junit.platform:junit-platform-launcher:1.14.2")
    testImplementation("io.swagger.parser.v3:swagger-parser:2.1.37")
    testImplementation("com.networknt:json-schema-validator:1.5.6")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.21.1")
    testImplementation("ch.qos.logback:logback-core:1.5.27")
    testImplementation("org.slf4j:slf4j-api:2.0.17")

    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.27")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.2")
}

val generateConformanceTests by tasks.registering {
    val specsDir = file("src/test/resources/specs")
    val outputDir = file("build/generated/sources/conformance/kotlin")

    inputs.dir(specsDir)
    outputs.dir(outputDir)

    doLast {
        outputDir.deleteRecursively()
        outputDir.mkdirs()

        specsDir.walkTopDown()
            .filter { it.isFile && it.extension in listOf("yaml", "yml") }
            .forEach { specFile ->
                val relativePath = specFile.relativeTo(specsDir).path
                val segments = relativePath.replace("\\", "/").split("/")
                val className = "S" + segments.joinToString("_") { segment ->
                    segment.removeSuffix(".yaml").removeSuffix(".yml")
                        .split("-")
                        .joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }
                } + "Test"

                val displayName = segments.joinToString(" / ") { segment ->
                    segment.removeSuffix(".yaml").removeSuffix(".yml")
                }

                val code = buildString {
                    appendLine("import org.junit.jupiter.api.DisplayName")
                    appendLine()
                    appendLine("@DisplayName(\"$displayName\")")
                    appendLine("class $className : AbstractConformanceTest(\"$relativePath\")")
                    appendLine()
                }

                outputDir.resolve("$className.kt").writeText(code)
            }

        val count = outputDir.listFiles()?.size ?: 0
        println("Generated $count conformance test classes")
    }
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

tasks.test {
    useJUnitPlatform()

    val concurrency = System.getProperty("conformance.concurrency")
    if (concurrency != null) {
        systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", concurrency)
        systemProperty("junit.jupiter.execution.parallel.config.fixed.max-pool-size", concurrency)
    }
}
