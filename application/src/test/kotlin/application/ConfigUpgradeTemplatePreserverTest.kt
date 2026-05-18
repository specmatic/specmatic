package application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.specmatic.core.loadSpecmaticConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class ConfigUpgradeTemplatePreserverTest {
    private val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
    private val jsonMapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `preserves scalar template expressions across migrated config sections`() {
        val maxCombinations = "\${MAX_COMBOS:1}"
        val timeout = "\${TEST_TIMEOUT:2000}"
        val appUrl = "\${APP_URL:http://localhost:8080}"
        val stubDelay = "\${STUB_DELAY:250}"
        val stubBaseUrl = "\${STUB_BASE_URL:http://localhost:9000}"
        val dictionary = "\${DICTIONARY:dictionary.yaml}"
        val contractDirectory = "\${CONTRACT_DIR:./specs}"
        val sutSpec = "\${SUT_SPEC:sut.yaml}"
        val dependencySpec = "\${DEP_SPEC:dep.yaml}"
        val examplesDirectory = "\${EXAMPLES_DIR:templated_examples}"
        val bearerEnvironmentVariable = "\${BEARER_ENV:SPECMATIC_BEARER}"
        val reportDirectory = "\${REPORT_DIR:build/reports/specmatic}"
        val minCoverage = "\${MIN_COVERAGE:80}"
        val maxMissedOperations = "\${MAX_MISSED:0}"
        val loggingLevel = "\${LOG_LEVEL:INFO}"
        val logDirectory = "\${LOG_DIR:build/logs}"

        val upgraded = preserveTemplates(
            originalConfigYaml =
            """
            version: 2
            auth:
              bearer-environment-variable: '$bearerEnvironmentVariable'
            contracts:
              - filesystem:
                  directory: '$contractDirectory'
                provides:
                  - '$sutSpec'
                consumes:
                  - '$dependencySpec'
            test:
              maxTestRequestCombinations: $maxCombinations
              timeoutInMilliseconds: $timeout
              baseUrl: '$appUrl'
            stub:
              delayInMilliseconds: $stubDelay
              baseUrl: '$stubBaseUrl'
              dictionary: '$dictionary'
            report_dir_path: '$reportDirectory'
            report:
              types:
                APICoverage:
                  OpenAPI:
                    successCriteria:
                      minThresholdPercentage: $minCoverage
                      maxMissedEndpointsInSpec: $maxMissedOperations
            logging:
              level: '$loggingLevel'
              json:
                directory: '$logDirectory'
            examples:
              - fixed_examples
              - '$examplesDirectory'
            """.trimIndent(),
            upgradedConfigYaml =
            """
            version: 3
            specmatic:
              governance:
                report:
                  outputDirectory: build/reports/specmatic
                successCriteria:
                  minCoveragePercentage: 80
                  maxMissedOperationsInSpec: 0
              settings:
                general:
                  logging:
                    level: INFO
                    json:
                      directory: build/logs
                test:
                  maxTestRequestCombinations: 1
                  timeoutInMilliseconds: 2000
                mock:
                  delayInMilliseconds: 250
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: ./specs
                      specs:
                        - spec:
                            path: sut.yaml
                  - definition:
                      source:
                        git:
                          url: https://example.com/specs.git
                          auth:
                            bearerEnvironmentVariable: SPECMATIC_BEARER
                      specs:
                        - spec:
                            path: git-sut.yaml
                runOptions:
                  openapi:
                    baseUrl: http://localhost:8080
                data:
                  dictionary:
                    path: dictionary.yaml
                  examples:
                    - directories:
                        - fixed_examples
                        - templated_examples
                settings:
                  junitReportDir: build/reports/specmatic
            dependencies:
              settings:
                delayInMilliseconds: 250
              services:
                - service:
                    definitions:
                      - definition:
                          specs:
                            - spec:
                                path: dep.yaml
                    runOptions:
                      openapi:
                        baseUrl: http://localhost:9000
              data:
                dictionary:
                  path: dictionary.yaml
                examples:
                  - directories:
                      - fixed_examples
                      - templated_examples
            """.trimIndent(),
        )

        assertThat(upgraded.allTextValues()).contains(
            maxCombinations,
            timeout,
            appUrl,
            stubDelay,
            stubBaseUrl,
            dictionary,
            contractDirectory,
            sutSpec,
            dependencySpec,
            examplesDirectory,
            bearerEnvironmentVariable,
            reportDirectory,
            minCoverage,
            maxMissedOperations,
            loggingLevel,
            logDirectory,
        )
    }

    @Test
    fun `preserves scalar templates inside contract execution array objects`() {
        val providerBaseUrl = "\${BASE_URL:http://localhost:8080}"
        val dependencyBaseUrl = "\${DEPENDENCY_BASE_URL:http://localhost:7070}"
        val dependencyHost = "\${CONSUMES_PARTIAL_HOST:127.0.0.1}"
        val dependencyBasePath = "\${CONSUMES_PARTIAL_BASE_PATH:/api}"

        val upgraded = preserveTemplates(
            originalConfigYaml =
            """
            version: 2
            contracts:
              - provides:
                  - specs:
                      - spec1.yaml
                    specType: openapi
                    config:
                      baseUrl: $providerBaseUrl
                  - specs:
                      - spec2.yaml
                    specType: openapi
                    config:
                      baseUrl: http://localhost:9090
                consumes:
                  - specs:
                      - dependency.yaml
                    specType: openapi
                    config:
                      baseUrl: $dependencyBaseUrl
                  - host: $dependencyHost
                    port: 9091
                    basePath: $dependencyBasePath
                    specs:
                      - dependency-partial.yaml
            """.trimIndent(),
            upgradedConfigYaml =
            """
            version: 3
            systemUnderTest:
              service:
                runOptions:
                  openapi:
                    specs:
                      - spec:
                          baseUrl: http://localhost:8080
                      - spec:
                          baseUrl: http://localhost:9090
            dependencies:
              services:
                - service:
                    runOptions:
                      openapi:
                        specs:
                          - spec:
                              baseUrl: http://localhost:7070
                - service:
                    definitions:
                      - definition:
                          specs:
                            - spec:
                                path: dependency-partial.yaml
                                urlPathPrefix: /api
                    runOptions:
                      openapi:
                        specs:
                          - spec:
                              host: 127.0.0.1
                              port: 9091
            components:
              runOptions:
                provider:
                  openapi:
                    specs:
                      - spec:
                          baseUrl: http://localhost:8080
            """.trimIndent(),
        )

        assertThat(upgraded.at("/systemUnderTest/service/runOptions/openapi/specs/0/spec/baseUrl").asText())
            .isEqualTo(providerBaseUrl)
        assertThat(upgraded.at("/systemUnderTest/service/runOptions/openapi/specs/1/spec/baseUrl").asText())
            .isEqualTo("http://localhost:9090")
        assertThat(upgraded.at("/dependencies/services/0/service/runOptions/openapi/specs/0/spec/baseUrl").asText())
            .isEqualTo(dependencyBaseUrl)
        assertThat(upgraded.at("/dependencies/services/1/service/definitions/0/definition/specs/0/spec/urlPathPrefix").asText())
            .isEqualTo(dependencyBasePath)
        assertThat(upgraded.at("/dependencies/services/1/service/runOptions/openapi/specs/0/spec/host").asText())
            .isEqualTo(dependencyHost)
        assertThat(upgraded.at("/components/runOptions/provider/openapi/specs/0/spec/baseUrl").asText())
            .isEqualTo(providerBaseUrl)
    }

    @Test
    fun `preserves resiliency test template when the field is renamed and moved`() {
        val resiliencyTests = "\${RESILIENCY_TESTS:all}"

        val upgraded = preserveTemplates(
            originalConfigYaml =
            """
            version: 2
            contracts:
              - provides:
                  - specs:
                      - spec1.yaml
                    resiliencyTests:
                      enable: $resiliencyTests
            """.trimIndent(),
            upgradedConfigYaml =
            """
            version: 3
            systemUnderTest:
              service:
                settings:
                  test:
                    schemaResiliencyTests: all
            """.trimIndent(),
        )

        assertThat(upgraded.allTextValues()).contains(resiliencyTests)
    }

    @Test
    fun `preserves workflow templates using matching workflow path`() {
        val operationExtract = "\${WORKFLOW_OPERATION_EXTRACT:$.id}"
        val operationUse = "\${WORKFLOW_OPERATION_USE:$.id}"
        val defaultExtract = "\${WORKFLOW_DEFAULT_EXTRACT:$.id}"
        val defaultUse = "\${WORKFLOW_DEFAULT_USE:$.id}"

        val upgraded = preserveTemplates(
            originalConfigYaml =
            """
            version: 2
            workflow:
              ids:
                operationId:
                  extract: '$operationExtract'
                  use: '$operationUse'
                '*':
                  extract: '$defaultExtract'
                  use: '$defaultUse'
            """.trimIndent(),
            upgradedConfigYaml =
            """
            version: 3
            systemUnderTest:
              service:
                runOptions:
                  openapi:
                    workflow:
                      ids:
                        operationId:
                          extract: $.id
                          use: $.id
                        '*':
                          extract: $.id
                          use: $.id
            """.trimIndent(),
        )

        assertThat(upgraded.at("/systemUnderTest/service/runOptions/openapi/workflow/ids/operationId/extract").asText())
            .isEqualTo(operationExtract)
        assertThat(upgraded.at("/systemUnderTest/service/runOptions/openapi/workflow/ids/operationId/use").asText())
            .isEqualTo(operationUse)
        assertThat(upgraded.at("/systemUnderTest/service/runOptions/openapi/workflow/ids/*/extract").asText())
            .isEqualTo(defaultExtract)
        assertThat(upgraded.at("/systemUnderTest/service/runOptions/openapi/workflow/ids/*/use").asText())
            .isEqualTo(defaultUse)
    }

    @Test
    fun `preserves camel case top level templates in their v3 locations`() {
        val disableTelemetry = "\${DISABLE_TELEMETRY:false}"
        val licensePath = "\${LICENSE_PATH:licenses/specmatic.lic}"
        val reportDirPath = "\${REPORT_DIR_PATH:build/reports/specmatic}"
        val specExamplesDirectoryTemplate = "\${SPEC_EXAMPLES_DIRECTORY_TEMPLATE:<SPEC_FILE_NAME>_examples}"
        val hotReload = "\${STUB_HOT_RELOAD:enabled}"
        val migratedHotReload = "\${STUB_HOT_RELOAD:true}"

        val upgraded = preserveTemplates(
            originalConfigYaml =
            """
            version: 2
            disableTelemetry: '$disableTelemetry'
            licensePath: '$licensePath'
            reportDirPath: '$reportDirPath'
            globalSettings:
              specExamplesDirectoryTemplate: '$specExamplesDirectoryTemplate'
            stub:
              hotReload: '$hotReload'
            """.trimIndent(),
            upgradedConfigYaml =
            """
            version: 3
            systemUnderTest:
              service:
                settings:
                  junitReportDir: build/reports/specmatic
            dependencies:
              settings:
                hotReload: true
            specmatic:
              license:
                path: licenses/specmatic.lic
              settings:
                general:
                  disableTelemetry: false
                  specExamplesDirectoryTemplate: <SPEC_FILE_NAME>_examples
            """.trimIndent(),
        )

        assertThat(upgraded.allTextValues()).contains(
            disableTelemetry,
            licensePath,
            reportDirPath,
            specExamplesDirectoryTemplate,
            migratedHotReload,
        )
    }

    @Test
    fun `does not preserve template expressions that resolve to structured values`() {
        val stubConfig = """${'$'}{STUB_CONFIG:{"delayInMilliseconds":250}}"""
        val examples = """${'$'}{EXAMPLE_DIRS:["examples/a","examples/b"]}"""

        val upgraded = preserveTemplates(
            originalConfigYaml =
            """
            version: 2
            stub: '$stubConfig'
            examples: '$examples'
            """.trimIndent(),
            upgradedConfigYaml =
            """
            version: 3
            specmatic:
              settings:
                mock:
                  delayInMilliseconds: 250
            dependencies:
              data:
                examples:
                  - directories:
                      - examples/a
                      - examples/b
            """.trimIndent(),
        )

        assertThat(upgraded.allTextValues()).doesNotContain(stubConfig, examples)
    }

    @Test
    fun `upgrades complete template fixture to expected v3 config and loads it`(@TempDir tempDir: Path) {
        val v2Config = tempFixture("specmatic-v2-complete-template.yaml")
        val expectedV3Config = tempFixture("specmatic-v3-complete-template.yaml")
        assumeTrue(v2Config.exists(), "Missing local fixture: $v2Config")
        assumeTrue(expectedV3Config.exists(), "Missing local fixture: $expectedV3Config")

        val generatedV3Config = tempDir.resolve("specmatic-v3-complete-template.yaml")
        val exitCode = CommandLine(ConfigCommand.Upgrade()).execute(
            "--input", v2Config.toString(),
            "--output", generatedV3Config.toString(),
        )

        assertThat(exitCode).isEqualTo(0)
        assertThat(generatedV3Config.readText().trim()).isEqualTo(expectedV3Config.readText().trim())
        assertDoesNotThrow { loadSpecmaticConfig(generatedV3Config.toString()) }
    }

    @Test
    fun `complete template fixtures cover official config schema paths exactly`() {
        val v2Config = testResource("specmaticConfigFiles/schemaCoverage/specmatic-v2-schema-coverage.yaml")
        val v3Config = testResource("specmaticConfigFiles/schemaCoverage/specmatic-v3-schema-coverage.yaml")
        val v3RefConfig = testResource("specmaticConfigFiles/schemaCoverage/specmatic-v3-ref-schema-coverage.yaml")

        val schemaPaths = JsonSchemaPathExtractor(jsonMapper.readTree(testResource("specmatic-config-schema.json").toFile()))
            .paths()
        val configPaths = listOf(v2Config, v3Config, v3RefConfig)
            .flatMap { configFile -> yamlMapper.readTree(configFile.toFile()).configLeafPaths() }
            .toSet()

        val missingConfigCoverage = schemaPaths
            .filter { schemaPath -> configPaths.none { configPath -> schemaPath.matches(configPath) } }
            .sortedBy(ConfigPath::toString)
        val extraConfigPaths = configPaths
            .filter { configPath -> schemaPaths.none { schemaPath -> schemaPath.matches(configPath) } }
            .sortedBy(ConfigPath::toString)

        assertThat(schemaCoverageFailure(missingConfigCoverage, extraConfigPaths)).isEmpty()
    }

    private fun tempFixture(fileName: String): Path {
        val workingDirectory = Path.of("").toAbsolutePath()
        return generateSequence(workingDirectory, Path::getParent)
            .map { directory -> directory.resolve("temp").resolve(fileName) }
            .firstOrNull(Path::exists)
            ?: workingDirectory.resolve("temp").resolve(fileName)
    }

    private fun testResource(fileName: String): Path {
        val resource = requireNotNull(javaClass.getResource("/$fileName")) {
            "Missing test resource: $fileName"
        }
        return Path.of(resource.toURI())
    }

    private fun preserveTemplates(originalConfigYaml: String, upgradedConfigYaml: String): JsonNode {
        val templatePreservedYaml = ConfigUpgradeTemplatePreserver(yamlMapper)
            .preserveTemplates(originalConfigYaml, upgradedConfigYaml)
        return yamlMapper.readTree(templatePreservedYaml)
    }

    private fun JsonNode.allTextValues(): List<String> {
        return when {
            isTextual -> listOf(asText())
            isObject -> properties().asSequence().flatMap { it.value.allTextValues().asSequence() }.toList()
            isArray -> elements().asSequence().flatMap { it.allTextValues().asSequence() }.toList()
            else -> emptyList()
        }
    }

    private fun JsonNode.configLeafPaths(path: List<String> = emptyList()): Set<ConfigPath> {
        return when {
            isObject -> properties().asSequence()
                .flatMap { entry -> entry.value.configLeafPaths(path + entry.key).asSequence() }
                .toSet()
            isArray -> elements().asSequence()
                .flatMap { element -> element.configLeafPaths(path + ARRAY_SEGMENT).asSequence() }
                .toSet()
            else -> setOf(ConfigPath(path))
        }
    }

    private fun schemaCoverageFailure(missingConfigCoverage: List<ConfigPath>, extraConfigPaths: List<ConfigPath>): String {
        return buildString {
            if (missingConfigCoverage.isNotEmpty()) {
                appendLine("Missing config fixture coverage for schema paths:")
                missingConfigCoverage.forEach { path -> appendLine("- $path") }
            }

            if (extraConfigPaths.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("Config fixture paths not present in the schema:")
                extraConfigPaths.forEach { path -> appendLine("- $path") }
            }
        }
    }

    private data class ConfigPath(val segments: List<String>) {
        fun matches(configPath: ConfigPath): Boolean {
            if (segments.size != configPath.segments.size) return false
            return segments.zip(configPath.segments).all { (schemaSegment, configSegment) ->
                schemaSegment == WILDCARD_SEGMENT || schemaSegment == configSegment
            }
        }

        override fun toString(): String {
            return segments.fold("") { currentPath, segment ->
                when {
                    currentPath.isEmpty() -> segment
                    segment == ARRAY_SEGMENT -> "$currentPath$ARRAY_SEGMENT"
                    else -> "$currentPath.$segment"
                }
            }
        }
    }

    private class JsonSchemaPathExtractor(private val rootSchema: JsonNode) {
        fun paths(): Set<ConfigPath> {
            return collect(rootSchema, emptyList(), emptySet())
        }

        private fun collect(schema: JsonNode, path: List<String>, visitedRefs: Set<String>): Set<ConfigPath> {
            schema.localRef()?.let { ref ->
                if (ref in visitedRefs) return emptySet()
                return collect(rootSchema.at(ref.removePrefix("#")), path, visitedRefs + ref)
            }

            val paths = mutableSetOf<ConfigPath>()
            schema.compositionBranches().forEach { branch ->
                paths += collect(branch, path, visitedRefs)
            }
            schema.conditionalSchemaBranches().forEach { branch ->
                paths += collect(branch, path, visitedRefs)
            }

            schema.get("properties")?.takeIf(JsonNode::isObject)?.properties()?.asSequence()?.forEach { entry ->
                if (!entry.value.isEmptyObjectSchema()) {
                    paths += collect(entry.value, path + entry.key, visitedRefs)
                }
            }

            schema.get("items")?.takeUnless { it.isBoolean }?.let { items ->
                paths += collect(items, path + ARRAY_SEGMENT, visitedRefs)
            }

            schema.get("additionalProperties")?.takeIf(JsonNode::isObject)?.let { additionalProperties ->
                paths += when {
                    additionalProperties.isEmptyObjectSchema() -> setOf(ConfigPath(path + WILDCARD_SEGMENT))
                    else -> collect(additionalProperties, path + WILDCARD_SEGMENT, visitedRefs)
                }
            }

            schema.get("patternProperties")?.takeIf(JsonNode::isObject)?.properties()?.asSequence()?.forEach { entry ->
                paths += collect(entry.value, path + WILDCARD_SEGMENT, visitedRefs)
            }

            if (paths.isEmpty() && schema.isLeafSchema()) paths += ConfigPath(path)
            return paths
        }

        private fun JsonNode.localRef(): String? {
            return get("\$ref")?.asText()?.takeIf { ref -> ref.startsWith("#/") }
        }

        private fun JsonNode.compositionBranches(): Sequence<JsonNode> {
            return sequenceOf("allOf", "anyOf", "oneOf")
                .mapNotNull { keyword -> get(keyword)?.takeIf(JsonNode::isArray) }
                .flatMap { branches -> branches.elements().asSequence() }
        }

        private fun JsonNode.conditionalSchemaBranches(): Sequence<JsonNode> {
            return sequenceOf("then", "else").mapNotNull { keyword -> get(keyword)?.takeIf(JsonNode::isObject) }
        }

        private fun JsonNode.isLeafSchema(): Boolean {
            return !isObject ||
                size() > 0 &&
                !has("properties") &&
                !has("items") &&
                !has("additionalProperties") &&
                !has("patternProperties") &&
                !has("allOf") &&
                !has("anyOf") &&
                !has("oneOf") &&
                !has("then") &&
                !has("else")
        }

        private fun JsonNode.isEmptyObjectSchema(): Boolean {
            return isObject && size() == 0
        }
    }

    private companion object {
        const val ARRAY_SEGMENT = "[]"
        const val WILDCARD_SEGMENT = "*"
    }
}
