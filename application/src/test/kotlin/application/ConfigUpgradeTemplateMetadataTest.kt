package application

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.TemplatableValue
import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.config.SpecmaticConfigVersion.Companion.convertToLatestVersionedConfig
import io.specmatic.core.config.toTemplateAwareSpecmaticConfig
import io.specmatic.core.config.v3.SpecmaticConfigV3
import io.specmatic.core.config.writeYamlPreservingConfigTemplates
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.nio.file.Path
import kotlin.io.path.readText

class ConfigUpgradeTemplateMetadataTest {
    private val yamlMapper = ObjectMapper(YAMLFactory()).apply {
        registerKotlinModule()
        setDefaultPropertyInclusion(
            JsonInclude.Value.construct(
                JsonInclude.Include.CUSTOM,
                JsonInclude.Include.CUSTOM
            ).withValueFilter(EmptyCollectionFilter::class.java)
        )
    }
    private val jsonMapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `template aware load ingests substitution expressions into config values`(@TempDir tempDir: Path) {
        val providerBaseUrl = "\${BASE_URL:http://localhost:8080}"
        val input = writeConfig(
            tempDir,
            """
            version: 2
            contracts:
              provides:
                - specs:
                    - spec1.yaml
                  specType: openapi
                  config:
                    baseUrl: '$providerBaseUrl'
                - specs:
                    - spec2.yaml
                  specType: openapi
                  config:
                    baseUrl: http://localhost:8080
            """.trimIndent()
        )

        val config = input.toFile().toTemplateAwareSpecmaticConfig() as SpecmaticConfigV1V2Common
        val firstProvidedConfig = SpecmaticConfigV1V2Common.getSources(config)
            .single()
            .test
            .orEmpty()
            .first() as SpecExecutionConfig.ConfigValue
        val sourceTemplate = config.configTemplateMetadata.sourceTemplates.single()

        assertThat(firstProvidedConfig.config["baseUrl"])
            .isEqualTo(TemplatableValue(value = "http://localhost:8080", template = providerBaseUrl))
        assertThat(sourceTemplate.path.segments)
            .containsExactly("contracts", "provides", "0", "config", "baseUrl")
        assertThat(sourceTemplate.value)
            .isEqualTo(TemplatableValue(value = "http://localhost:8080", template = providerBaseUrl))
    }

    @Test
    fun `migration transfers template metadata to v3 and export renders only the matching template`(@TempDir tempDir: Path) {
        val providerBaseUrl = "\${BASE_URL:http://localhost:8080}"
        val input = writeConfig(
            tempDir,
            """
            version: 2
            contracts:
              provides:
                - specs:
                    - spec1.yaml
                  specType: openapi
                  config:
                    baseUrl: '$providerBaseUrl'
                - specs:
                    - spec2.yaml
                  specType: openapi
                  config:
                    baseUrl: http://localhost:8080
            """.trimIndent()
        )

        val upgraded = convertToLatestVersionedConfig(input.toFile().toTemplateAwareSpecmaticConfig()) as SpecmaticConfigV3
        val upgradedTree = yamlMapper.readTree(upgraded.writeYamlPreservingConfigTemplates(yamlMapper))
        val openApiSpecs = upgraded.systemUnderTest!!.service.getUnsafe().runOptions!!.getUnsafe().openapi!!.specs!!

        assertThat(upgraded.configTemplateMetadata.sourceTemplates).hasSize(1)
        assertThat(openApiSpecs[0].spec.baseUrl)
            .isEqualTo(TemplatableValue(value = "http://localhost:8080", template = providerBaseUrl))
        assertThat(openApiSpecs[1].spec.baseUrl)
            .isEqualTo(TemplatableValue(value = "http://localhost:8080"))
        assertThat(upgraded.configTemplateMetadata.targetTemplateAt(listOf("systemUnderTest", "service", "runOptions", "openapi", "specs", "0", "spec", "baseUrl")))
            .isEqualTo(TemplatableValue(value = "http://localhost:8080", template = providerBaseUrl))
        assertThat(upgradedTree.at("/systemUnderTest/service/runOptions/openapi/specs/0/spec/baseUrl").asText())
            .isEqualTo(providerBaseUrl)
        assertThat(upgradedTree.at("/systemUnderTest/service/runOptions/openapi/specs/1/spec/baseUrl").asText())
            .isEqualTo("http://localhost:8080")
    }

    @Test
    fun `upgrade command preserves only the matching contract entry template`(@TempDir tempDir: Path) {
        val providerBaseUrl = "\${BASE_URL:http://localhost:8080}"
        val input = writeConfig(
            tempDir,
            """
            version: 2
            contracts:
              provides:
                - specs:
                    - spec1.yaml
                  specType: openapi
                  config:
                    baseUrl: '$providerBaseUrl'
                - specs:
                    - spec2.yaml
                  specType: openapi
                  config:
                    baseUrl: http://localhost:8080
            """.trimIndent()
        )
        val output = tempDir.resolve("specmatic-v3.yaml")

        val exitCode = CommandLine(ConfigCommand.Upgrade()).execute(
            "--input", input.toString(),
            "--output", output.toString(),
        )

        val upgraded = yamlMapper.readTree(output.readText())
        assertThat(exitCode).isEqualTo(0)
        assertThat(upgraded.at("/systemUnderTest/service/runOptions/openapi/specs/0/spec/baseUrl").asText())
            .isEqualTo(providerBaseUrl)
        assertThat(upgraded.at("/systemUnderTest/service/runOptions/openapi/specs/1/spec/baseUrl").asText())
            .isEqualTo("http://localhost:8080")
    }

    @Test
    fun `export uses migrated metadata for renamed and moved fields`(@TempDir tempDir: Path) {
        val disableTelemetry = "\${DISABLE_TELEMETRY:false}"
        val licensePath = "\${LICENSE_PATH:licenses/specmatic.lic}"
        val reportDirPath = "\${REPORT_DIR_PATH:build/reports/specmatic}"
        val specExamplesDirectoryTemplate = "\${SPEC_EXAMPLES_DIRECTORY_TEMPLATE:<SPEC_FILE_NAME>_examples}"
        val hotReload = "\${STUB_HOT_RELOAD:enabled}"
        val migratedHotReload = "\${STUB_HOT_RELOAD:true}"
        val input = writeConfig(
            tempDir,
            """
            version: 2
            disableTelemetry: '$disableTelemetry'
            licensePath: '$licensePath'
            reportDirPath: '$reportDirPath'
            globalSettings:
              specExamplesDirectoryTemplate: '$specExamplesDirectoryTemplate'
            stub:
              hotReload: '$hotReload'
            """.trimIndent()
        )

        val upgraded = convertToLatestVersionedConfig(input.toFile().toTemplateAwareSpecmaticConfig())
        val upgradedTree = yamlMapper.readTree(upgraded.writeYamlPreservingConfigTemplates(yamlMapper))

        val v3Config = upgraded as SpecmaticConfigV3
        assertThat(v3Config.configTemplateMetadata.targetTemplateAt(listOf("specmatic", "settings", "mock", "hotReload")))
            .isEqualTo(TemplatableValue(value = "true", template = migratedHotReload))
        assertThat(upgradedTree.allTextValues()).contains(
            disableTelemetry,
            licensePath,
            reportDirPath,
            specExamplesDirectoryTemplate,
            migratedHotReload,
        )
        assertThat(upgradedTree.at("/specmatic/governance/report/outputDirectory").asText()).isEqualTo(reportDirPath)
    }

    @Test
    fun `structured template values are resolved but not preserved as scalar expressions`(@TempDir tempDir: Path) {
        val patternValues = """${'$'}{PATTERN_VALUES:{"id":10,"name":"Alice"}}"""
        val input = writeConfig(
            tempDir,
            """
            version: 2
            default_pattern_values: '$patternValues'
            """.trimIndent()
        )

        val upgraded = convertToLatestVersionedConfig(input.toFile().toTemplateAwareSpecmaticConfig())
        val upgradedTree = yamlMapper.readTree(upgraded.writeYamlPreservingConfigTemplates(yamlMapper))

        assertThat(upgradedTree.allTextValues()).doesNotContain(patternValues)
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

    private fun writeConfig(tempDir: Path, contents: String): Path {
        return tempDir.resolve("specmatic-v2.yaml").also { path ->
            path.toFile().writeText(contents)
        }
    }

    private fun testResource(fileName: String): Path {
        val resource = requireNotNull(javaClass.getResource("/$fileName")) {
            "Missing test resource: $fileName"
        }
        return Path.of(resource.toURI())
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

    private class EmptyCollectionFilter {
        override fun equals(other: Any?): Boolean {
            if (other == null) return true
            if (other.javaClass == this.javaClass) return true

            return when (other) {
                is Map<*, *> -> other.all { it.key is String && equals(it.value) }
                is Collection<*> -> other.all { equals(it) }
                is Array<*> -> other.all { equals(it) }
                is String -> other.isBlank()
                else -> false
            }
        }

        override fun hashCode(): Int {
            return javaClass.hashCode()
        }
    }

    private companion object {
        const val ARRAY_SEGMENT = "[]"
        const val WILDCARD_SEGMENT = "*"
    }
}
