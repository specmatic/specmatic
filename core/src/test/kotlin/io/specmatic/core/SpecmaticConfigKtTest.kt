package io.specmatic.core

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import io.specmatic.core.config.v3.SpecExecutionConfig
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.specmatic.core.config.v2.ContractConfig
import io.specmatic.core.utilities.ContractSourceEntry
import io.specmatic.core.utilities.Flags.Companion.EXAMPLE_DIRECTORIES
import io.specmatic.core.config.v2.SpecmaticConfigV2
import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.utilities.Flags.Companion.EXTENSIBLE_SCHEMA
import io.specmatic.core.utilities.Flags.Companion.MAX_TEST_REQUEST_COMBINATIONS
import io.specmatic.core.utilities.Flags.Companion.ONLY_POSITIVE
import io.specmatic.core.utilities.Flags.Companion.SCHEMA_EXAMPLE_DEFAULT
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_GENERATIVE_TESTS
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_STUB_DELAY
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_TEST_TIMEOUT
import io.specmatic.core.utilities.Flags.Companion.VALIDATE_RESPONSE_VALUE
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.stream.Stream

internal class SpecmaticConfigKtTest {

    @CsvSource(
        "./src/test/resources/specmaticConfigFiles/specmatic.yaml",
        "./src/test/resources/specmaticConfigFiles/specmatic.yml",
        "./src/test/resources/specmaticConfigFiles/specmatic.json",
    )
    @ParameterizedTest
    fun `parse specmatic config file with all values`(configFile: String) {
        val config: SpecmaticConfig = loadSpecmaticConfig(configFile)

        val sources = SpecmaticConfig.getSources(config)
        assertThat(sources).isNotEmpty

        assertThat(sources.first().provider).isEqualTo(SourceProvider.git)
        assertThat(sources.first().repository).isEqualTo("https://contracts")
        assertThat(sources.first().test).isEqualTo(listOf("com/petstore/1.spec"))
        assertThat(sources.first().specsUsedAsStub()).isEqualTo(listOf("com/petstore/payment.spec"))

        assertThat(config.getAuthBearerFile()).isEqualTo("bearer.txt")
        assertThat(config.getAuthBearerEnvironmentVariable()).isNull()

        assertThat(config.getPipelineProvider()).isEqualTo(PipelineProvider.azure)
        assertThat(config.getPipelineOrganization()).isEqualTo("xnsio")
        assertThat(config.getPipelineProject()).isEqualTo("XNSIO")
        assertThat(config.getPipelineDefinitionId()).isEqualTo(1)

        assertThat(
            SpecmaticConfig.getEnvironments(config)?.get("staging")?.baseurls?.get("auth.spec")
        ).isEqualTo("http://localhost:8080")
        assertThat(
            SpecmaticConfig.getEnvironments(config)?.get("staging")?.variables?.get("username")
        ).isEqualTo("jackie")
        assertThat(
            SpecmaticConfig.getEnvironments(config)?.get("staging")?.variables?.get("password")
        ).isEqualTo("PaSsWoRd")

        assertThat(SpecmaticConfig.getReport(config)?.formatters?.get(0)?.type).isEqualTo(ReportFormatterType.TEXT)
        assertThat(SpecmaticConfig.getReport(config)?.formatters?.get(0)?.layout).isEqualTo(ReportFormatterLayout.TABLE)
        assertThat(SpecmaticConfig.getReport(config)?.types?.apiCoverage?.openAPI?.successCriteria?.minThresholdPercentage).isEqualTo(
            70
        )
        assertThat(SpecmaticConfig.getReport(config)?.types?.apiCoverage?.openAPI?.successCriteria?.maxMissedEndpointsInSpec).isEqualTo(
            3
        )
        assertThat(SpecmaticConfig.getReport(config)?.types?.apiCoverage?.openAPI?.successCriteria?.enforce).isTrue()
        assertThat(SpecmaticConfig.getReport(config)?.types?.apiCoverage?.openAPI?.excludedEndpoints?.get(0)).isEqualTo(
            "/heartbeat"
        )
        assertThat(SpecmaticConfig.getReport(config)?.types?.apiCoverage?.openAPI?.excludedEndpoints?.get(1)).isEqualTo(
            "/health"
        )

        assertThat(
            (config.getOpenAPISecurityConfigurationScheme("oAuth2AuthCode") as OAuth2SecuritySchemeConfiguration).token
        ).isEqualTo("OAUTH1234")
        assertThat(
            (config.getOpenAPISecurityConfigurationScheme("BearerAuth") as BearerSecuritySchemeConfiguration).token
        ).isEqualTo("BEARER1234")
        assertThat(
            (config.getOpenAPISecurityConfigurationScheme("ApiKeyAuthHeader") as APIKeySecuritySchemeConfiguration).value
        ).isEqualTo("API-HEADER-USER")
        assertThat(
            (config.getOpenAPISecurityConfigurationScheme("ApiKeyAuthQuery") as APIKeySecuritySchemeConfiguration).value
        ).isEqualTo("API-QUERY-PARAM-USER")

        assertThat(
            (config.getOpenAPISecurityConfigurationScheme("BasicAuth") as BasicAuthSecuritySchemeConfiguration).token
        ).isEqualTo("Abc123")

        assertThat(config.getExamples()).isEqualTo(listOf("folder1/examples", "folder2/examples"))

        assertThat(config.isResiliencyTestingEnabled()).isEqualTo(true)
        assertThat(config.isExtensibleSchemaEnabled()).isTrue()
        assertThat(config.isResponseValueValidationEnabled()).isTrue()

        assertThat(config.getStubDelayInMilliseconds()).isEqualTo(1000L)
        assertThat(config.getStubGenerative()).isEqualTo(false)

        val htmlConfig = SpecmaticConfig.getReport(config)?.formatters?.first { it.type == ReportFormatterType.HTML }
        assertThat(htmlConfig?.title).isEqualTo("Test Report")
        assertThat(htmlConfig?.heading).isEqualTo("Test Results")
        assertThat(htmlConfig?.outputDirectory).isEqualTo("output")

        assertThat(config.getTestTimeoutInMilliseconds()).isEqualTo(3000)
    }

    @Test
    fun `parse specmatic config file with only required values`() {
        val config = ObjectMapper(YAMLFactory()).registerKotlinModule().readValue(
            """
            {
                "sources": [
                    {
                        "provider": "git",
                        "test": [
                            "path/to/contract.spec"
                        ]
                    }
                ]
            }
        """.trimIndent(), SpecmaticConfig::class.java)

        val sources = SpecmaticConfig.getSources(config)
        assertThat(sources).isNotEmpty

        assertThat(sources.first().provider).isEqualTo(SourceProvider.git)
        assertThat(sources.first().test).isEqualTo(listOf("path/to/contract.spec"))
    }

    @CsvSource(
        "./src/test/resources/specmaticConfigFiles/specmatic_alias.yaml",
        "./src/test/resources/specmaticConfigFiles/specmatic_alias.yml",
        "./src/test/resources/specmaticConfigFiles/specmatic_alias.json",
    )
    @ParameterizedTest
    fun `parse specmatic config file with aliases`(configFile: String) {
        val config: SpecmaticConfig = loadSpecmaticConfig(configFile)

        val sources = SpecmaticConfig.getSources(config)
        assertThat(sources).isNotEmpty

        assertThat(sources.first().provider).isEqualTo(SourceProvider.git)
        assertThat(sources.first().repository).isEqualTo("https://contracts")
        assertThat(sources.first().test).isEqualTo(listOf("com/petstore/1.yaml"))
        assertThat(sources.first().specsUsedAsStub()).isEqualTo(listOf("com/petstore/payment.yaml"))

        assertThat(config.getAuthBearerFile()).isEqualTo("bearer.txt")
        assertThat(config.getAuthBearerEnvironmentVariable()).isNull()

        assertThat(config.getPipelineProvider()).isEqualTo(PipelineProvider.azure)
        assertThat(config.getPipelineOrganization()).isEqualTo("xnsio")
        assertThat(config.getPipelineProject()).isEqualTo("XNSIO")
        assertThat(config.getPipelineDefinitionId()).isEqualTo(1)

        assertThat(SpecmaticConfig.getEnvironments(config)?.get("staging")?.baseurls?.get("auth.spec")).isEqualTo("http://localhost:8080")
        assertThat(SpecmaticConfig.getEnvironments(config)?.get("staging")?.variables?.get("username")).isEqualTo("jackie")
        assertThat(SpecmaticConfig.getEnvironments(config)?.get("staging")?.variables?.get("password")).isEqualTo("PaSsWoRd")

        assertThat(SpecmaticConfig.getReport(config)?.formatters?.get(0)?.type).isEqualTo(ReportFormatterType.TEXT)
        assertThat(SpecmaticConfig.getReport(config)?.formatters?.get(0)?.layout).isEqualTo(ReportFormatterLayout.TABLE)
        assertThat(SpecmaticConfig.getReport(config)?.types?.apiCoverage?.openAPI?.successCriteria?.minThresholdPercentage).isEqualTo(70)
        assertThat(SpecmaticConfig.getReport(config)?.types?.apiCoverage?.openAPI?.successCriteria?.maxMissedEndpointsInSpec).isEqualTo(3)
        assertThat(SpecmaticConfig.getReport(config)?.types?.apiCoverage?.openAPI?.successCriteria?.enforce).isTrue()
        assertThat(SpecmaticConfig.getReport(config)?.types?.apiCoverage?.openAPI?.excludedEndpoints?.get(0)).isEqualTo("/heartbeat")
        assertThat(SpecmaticConfig.getReport(config)?.types?.apiCoverage?.openAPI?.excludedEndpoints?.get(1)).isEqualTo("/health")

        assertThat(
            (config.getOpenAPISecurityConfigurationScheme("oAuth2AuthCode") as OAuth2SecuritySchemeConfiguration).token
        ).isEqualTo("OAUTH1234")
        assertThat(
            (config.getOpenAPISecurityConfigurationScheme("BearerAuth") as BearerSecuritySchemeConfiguration).token
        ).isEqualTo("BEARER1234")
        assertThat(
            (config.getOpenAPISecurityConfigurationScheme("ApiKeyAuthHeader") as APIKeySecuritySchemeConfiguration).value
        ).isEqualTo("API-HEADER-USER")
        assertThat(
            (config.getOpenAPISecurityConfigurationScheme("ApiKeyAuthQuery") as APIKeySecuritySchemeConfiguration).value
        ).isEqualTo("API-QUERY-PARAM-USER")

        assertThat(
            (config.getOpenAPISecurityConfigurationScheme("BasicAuth") as BasicAuthSecuritySchemeConfiguration).token
        ).isEqualTo("Abc123")
    }

    @Test
    fun `should create SpecmaticConfig with flag values read from system properties`() {
        val properties = mapOf(
            SPECMATIC_GENERATIVE_TESTS to "true",
            ONLY_POSITIVE to "false",
            VALIDATE_RESPONSE_VALUE to "true",
            EXTENSIBLE_SCHEMA to "false",
            SCHEMA_EXAMPLE_DEFAULT to "true",
            MAX_TEST_REQUEST_COMBINATIONS to "50",
            EXAMPLE_DIRECTORIES to "folder1/examples,folder2/examples",
            SPECMATIC_STUB_DELAY to "1000",
            SPECMATIC_TEST_TIMEOUT to "5000"
        )
        try {
            properties.forEach { System.setProperty(it.key, it.value) }
            val config = SpecmaticConfig()
            assertThat(config.isResiliencyTestingEnabled()).isTrue()
            assertThat(config.isOnlyPositiveTestingEnabled()).isFalse()
            assertThat(config.isResponseValueValidationEnabled()).isTrue()
            assertThat(config.isExtensibleSchemaEnabled()).isFalse()
            assertThat(config.getExamples()).isEqualTo(listOf("folder1/examples", "folder2/examples"))
            assertThat(config.getTestTimeoutInMilliseconds()).isEqualTo(5000)
            assertThat(config.getStubDelayInMilliseconds()).isEqualTo(1000L)
        } finally {
            properties.forEach { System.clearProperty(it.key) }
        }
    }

    @Test
    fun `isResiliencyTestingEnabled should return true if either of SPECMATIC_GENERATIVE_TESTS and ONLY_POSITIVE is true`() {
        try {
            System.setProperty(SPECMATIC_GENERATIVE_TESTS, "true")

            assertThat(SpecmaticConfig().isResiliencyTestingEnabled()).isTrue()
        } finally {
            System.clearProperty(SPECMATIC_GENERATIVE_TESTS)
        }

        try {
            System.setProperty(ONLY_POSITIVE, "true")

            assertThat(SpecmaticConfig().isResiliencyTestingEnabled()).isTrue()
        } finally {
            System.clearProperty(ONLY_POSITIVE)
        }
    }

    @CsvSource(
        "./src/test/resources/specmaticConfigFiles/specmatic.yaml",
        "./src/test/resources/specmaticConfigFiles/specmatic.yml",
        "./src/test/resources/specmaticConfigFiles/specmatic.json",
    )
    @ParameterizedTest
    fun `should give preferences to values coming from config file over the env vars or system properties`(configFile: String) {
        val props = mapOf(
            SPECMATIC_GENERATIVE_TESTS to "false",
            VALIDATE_RESPONSE_VALUE to "false",
            EXTENSIBLE_SCHEMA to "false",
            EXAMPLE_DIRECTORIES to "folder1/examples,folder2/examples",
            SPECMATIC_STUB_DELAY to "5000",
            EXAMPLE_DIRECTORIES to "folder1/examples,folder2/examples",
            SPECMATIC_TEST_TIMEOUT to "5000"
        )
        try {
            props.forEach { System.setProperty(it.key, it.value) }
            val config: SpecmaticConfig = loadSpecmaticConfig(configFile)
            assertThat(config.isResiliencyTestingEnabled()).isTrue()
            assertThat(config.isResponseValueValidationEnabled()).isTrue()
            assertThat(config.isExtensibleSchemaEnabled()).isTrue()
            assertThat(config.getExamples()).isEqualTo(listOf("folder1/examples", "folder2/examples"))
            assertThat(config.getStubDelayInMilliseconds()).isEqualTo(1000L)
            assertThat(config.getTestTimeoutInMilliseconds()).isEqualTo(3000)
        } finally {
            props.forEach { System.clearProperty(it.key) }
        }
    }

    @Nested
    inner class StubConfigTests {
        @Test
        fun `should return all stub baseUrls from sources`() {
            val source1 = Source(
                stub = listOf(
                    SpecExecutionConfig.StringValue("9000_first.yaml"),
                    SpecExecutionConfig.StringValue("9000_second.yaml"),
                    SpecExecutionConfig.ObjectValue.FullUrl(
                        specs = listOf("9001_first.yaml", "9001_second.yaml"),
                        baseUrl = "http://localhost:9001"
                    ),
                    SpecExecutionConfig.ObjectValue.FullUrl(
                        specs = listOf("9002_first.yaml"),
                        baseUrl = "http://localhost:9002"
                    ),
                )
            )

            val source2 = Source(
                stub = listOf(
                    SpecExecutionConfig.StringValue("9000_third.yaml"),
                    SpecExecutionConfig.ObjectValue.FullUrl(
                        specs = listOf("9001_third.yaml", "9001_fourth.yaml"),
                        baseUrl = "http://localhost:9001"
                    ),
                    SpecExecutionConfig.ObjectValue.FullUrl(
                        specs = listOf("9002_second.yaml"),
                        baseUrl = "http://localhost:9002"
                    ),
                )
            )

            val specmaticConfig = SpecmaticConfig(
                sources = listOf(source1, source2)
            )

            assertThat(
                specmaticConfig.stubBaseUrls(
                   "http://localhost:9000"
                )
            ).containsAll(
                listOf(
                    "http://localhost:9000",
                    "http://localhost:9001",
                    "http://localhost:9002"
                )
            )
        }

        @Test
        fun `should return all stub contracts from sources`() {
            val source1 = Source(
                stub = listOf(
                    SpecExecutionConfig.StringValue("9000_first.yaml"),
                    SpecExecutionConfig.StringValue("9000_second.yaml"),
                    SpecExecutionConfig.ObjectValue.FullUrl(
                        specs = listOf("9001_first.yaml", "9001_second.yaml"),
                        baseUrl = "http://localhost:9001"
                    ),
                    SpecExecutionConfig.ObjectValue.FullUrl(
                        specs = listOf("9002_first.yaml"),
                        baseUrl = "http://localhost:9002"
                    ),
                )
            )

            val source2 = Source(
                stub = listOf(
                    SpecExecutionConfig.StringValue("9000_third.yaml"),
                    SpecExecutionConfig.ObjectValue.FullUrl(
                        specs = listOf("9001_third.yaml", "9001_fourth.yaml"),
                        baseUrl = "http://localhost:9001"
                    ),
                    SpecExecutionConfig.ObjectValue.FullUrl(
                        specs = listOf("9002_second.yaml"),
                        baseUrl = "http://localhost:9002",
                    ),
                )
            )

            val specmaticConfig = SpecmaticConfig(
                sources = listOf(source1, source2)
            )

            assertThat(
                specmaticConfig.stubContracts().map { it.substringAfterLast(File.separator) }
            ).isEqualTo(
                listOf(
                    "9000_first.yaml",
                    "9000_second.yaml",
                    "9001_first.yaml",
                    "9001_second.yaml",
                    "9002_first.yaml",
                    "9000_third.yaml",
                    "9001_third.yaml",
                    "9001_fourth.yaml",
                    "9002_second.yaml"
                )
            )
        }

        @ParameterizedTest
        @MethodSource("io.specmatic.core.SpecmaticConfigKtTest#consumesProvider")
        fun `should return a complete baseUrl when accessing value of a consumes object`(specExecutionConfig: SpecExecutionConfig.ObjectValue, defaultBaseUrl: String?, expectedValue: String) {
            val finalBaseUrl = specExecutionConfig.toBaseUrl(defaultBaseUrl)
            assertThat(finalBaseUrl).isEqualTo(expectedValue)
        }
    }

    @Nested
    inner class ProvidesConfigTests {
        private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

        @Test
        fun `should return all test baseUrls from sources`() {
            val source1 = Source(
                test = listOf(
                    "9000_first.yaml",
                    "9000_second.yaml",
                    "9001_first.yaml",
                    "9001_second.yaml",
                    "9002_first.yaml",
                ),
                testConsumes = listOf(
                    SpecExecutionConfig.StringValue("9000_first.yaml"),
                    SpecExecutionConfig.StringValue("9000_second.yaml"),
                    SpecExecutionConfig.ObjectValue.FullUrl(
                        specs = listOf("9001_first.yaml", "9001_second.yaml"),
                        baseUrl = "http://localhost:9001"
                    ),
                    SpecExecutionConfig.ObjectValue.FullUrl(
                        specs = listOf("9002_first.yaml"),
                        baseUrl = "http://localhost:9002"
                    ),
                )
            )

            val source2 = Source(
                test = listOf(
                    "9000_third.yaml",
                    "9001_third.yaml",
                    "9001_fourth.yaml",
                    "9002_second.yaml",
                ),
                testConsumes = listOf(
                    SpecExecutionConfig.StringValue("9000_third.yaml"),
                    SpecExecutionConfig.ObjectValue.FullUrl(
                        specs = listOf("9001_third.yaml", "9001_fourth.yaml"),
                        baseUrl = "http://localhost:9001"
                    ),
                    SpecExecutionConfig.ObjectValue.FullUrl(
                        specs = listOf("9002_second.yaml"),
                        baseUrl = "http://localhost:9002"
                    ),
                )
            )

            val specmaticConfig = SpecmaticConfig(
                sources = listOf(source1, source2)
            )

            val testToBaseUrl = specmaticConfig
                .let { SpecmaticConfig.getSources(it) }
                .flatMap { it.specToTestBaseUrlMap().entries }
                .associate { it.key to it.value }

            assertThat(testToBaseUrl).containsAllEntriesOf(
                mapOf(
                    "9000_first.yaml" to null,
                    "9000_second.yaml" to null,
                    "9001_first.yaml" to "http://localhost:9001",
                    "9001_second.yaml" to "http://localhost:9001",
                    "9002_first.yaml" to "http://localhost:9002",
                    "9000_third.yaml" to null,
                    "9001_third.yaml" to "http://localhost:9001",
                    "9001_fourth.yaml" to "http://localhost:9001",
                    "9002_second.yaml" to "http://localhost:9002"
                )
            )
        }

        @Test
        fun `should support provides with baseUrl alongside simple spec entries`() {
            val providesYaml = """
                version: 2
                contracts:
                  - filesystem:
                      directory: contracts
                    provides:
                      - baseUrl: http://localhost:9100
                        specs:
                          - com/petstore/a.yaml
                          - com/petstore/b.yaml
                      - com/petstore/c.yaml
            """.trimIndent()

            val specmaticConfig = ObjectMapper(YAMLFactory()).registerKotlinModule()
                .readValue(providesYaml, SpecmaticConfigV2::class.java)
                .transform()

            val sources = SpecmaticConfig.getSources(specmaticConfig)
            assertThat(sources).hasSize(1)

            val testMap = sources.single().specToTestBaseUrlMap()
            assertThat(testMap["com/petstore/a.yaml"]).isEqualTo("http://localhost:9100")
            assertThat(testMap["com/petstore/b.yaml"]).isEqualTo("http://localhost:9100")
            assertThat(testMap["com/petstore/c.yaml"]).isNull()

            // Also ensure loadSources wires baseUrl into ContractSourceEntry
            val contractSources = specmaticConfig.loadSources()
            val entries: List<ContractSourceEntry> = contractSources.flatMap { source -> source.testContracts }
            assertThat(entries).contains(
                ContractSourceEntry("com/petstore/a.yaml", "http://localhost:9100"),
                ContractSourceEntry("com/petstore/b.yaml", "http://localhost:9100"),
                ContractSourceEntry("com/petstore/c.yaml", null)
            )
        }

        @Test
        fun `should wire resiliencyTests enable into ContractSourceEntry`() {
            val providesYaml = """
                version: 2
                contracts:
                  - provides:
                      - baseUrl: http://localhost:9100
                        specs:
                          - com/petstore/a.yaml
                          - com/petstore/b.yaml
                        resiliencyTests:
                          enable: positiveOnly
            """.trimIndent()

            val specmaticConfig = ObjectMapper(YAMLFactory()).registerKotlinModule()
                .readValue(providesYaml, SpecmaticConfigV2::class.java)
                .transform()

            val entries: List<ContractSourceEntry> = specmaticConfig
                .loadSources()
                .flatMap { source -> source.testContracts }

            assertThat(entries).contains(
                ContractSourceEntry("com/petstore/a.yaml", "http://localhost:9100", ResiliencyTestSuite.positiveOnly),
                ContractSourceEntry("com/petstore/b.yaml", "http://localhost:9100", ResiliencyTestSuite.positiveOnly),
            )
        }

        @Test
        fun `should complain when basePath is used in provides object value`() {
            val providesString = """
            provides:
              - host: "127.0.0.1"
                port: 8080
                basePath: "/api/v2"
                specs:
                - "com/order.yaml"
            """.trimIndent()

            val exception = assertThrows<JsonMappingException> {
                mapper.readValue<ContractConfig>(providesString)
            }

            assertThat(exception.originalMessage).isEqualToNormalizingWhitespace("""
            Field 'basePath' is not supported in provides
            """.trimIndent())
        }

        @Test
        fun `should parse provides with resiliencyTests enable positiveOnly`() {
            val providesString = """
            provides:
              - baseUrl: "http://localhost:9100"
                specs:
                  - "com/petstore/a.yaml"
                resiliencyTests:
                  enable: positiveOnly
            """.trimIndent()

            val config = mapper.readValue<ContractConfig>(providesString)
            val provides = requireNotNull(config.provides)
            val obj = provides.single() as SpecExecutionConfig.ObjectValue.FullUrl
            assertThat(obj.resiliencyTests?.enable).isEqualTo(ResiliencyTestSuite.positiveOnly)
        }

        @Test
        fun `should parse provides with resiliencyTests enable all`() {
            val providesString = """
            provides:
              - host: "127.0.0.1"
                port: 8080
                specs:
                  - "com/petstore/a.yaml"
                resiliencyTests:
                  enable: all
            """.trimIndent()

            val config = mapper.readValue<ContractConfig>(providesString)
            val provides = requireNotNull(config.provides)
            val obj = provides.single() as SpecExecutionConfig.ObjectValue.PartialUrl
            assertThat(obj.resiliencyTests?.enable).isEqualTo(ResiliencyTestSuite.all)
        }

        @Test
        fun `should parse provides with resiliencyTests enable none`() {
            val providesString = """
            provides:
              - baseUrl: "http://localhost:9100"
                specs:
                  - "com/petstore/a.yaml"
                resiliencyTests:
                  enable: none
            """.trimIndent()

            val config = mapper.readValue<ContractConfig>(providesString)
            val provides = requireNotNull(config.provides)
            val obj = provides.single() as SpecExecutionConfig.ObjectValue.FullUrl
            assertThat(obj.resiliencyTests?.enable).isEqualTo(ResiliencyTestSuite.none)
        }

        @Test
        fun `should set resiliencyTests to null when omitted`() {
            val providesString = """
            provides:
              - baseUrl: "http://localhost:9100"
                specs:
                  - "com/petstore/a.yaml"
            """.trimIndent()

            val config = mapper.readValue<ContractConfig>(providesString)
            val provides = requireNotNull(config.provides)
            val obj = provides.single() as SpecExecutionConfig.ObjectValue.FullUrl
            assertThat(obj.resiliencyTests).isNull()
        }

        @Test
        fun `should complain when resiliencyTests enable has an invalid value in provides`() {
            val providesString = """
            provides:
              - baseUrl: "http://localhost:9100"
                specs:
                  - "com/petstore/a.yaml"
                resiliencyTests:
                  enable: invalid
            """.trimIndent()

            val exception = assertThrows<JsonMappingException> {
                mapper.readValue<ContractConfig>(providesString)
            }

            assertThat(exception.originalMessage).isEqualToNormalizingWhitespace("""
            Unknown value 'invalid' for 'resiliencyTests.enable'. Allowed: positiveOnly, all, none
            """.trimIndent())
        }
    }

    @Nested
    inner class StubSerializeAndDeSerializeTests {

        private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

        @ParameterizedTest
        @CsvSource(
            "./src/test/resources/specmaticConfigFiles/v2/specmatic_config_v2_stub.json",
            "./src/test/resources/specmaticConfigFiles/v2/specmatic_config_v2_stub.yaml"
        )
        fun `should be able to deserialize stub consumes config with simple and object value`(specmaticConfigFilePath: String) {
            val specmaticConfig = loadSpecmaticConfig(specmaticConfigFilePath)
            val sources = SpecmaticConfig.getSources(specmaticConfig)

            assertThat(sources).hasSize(1)
            assertThat(sources.single().stub).containsExactly(
                SpecExecutionConfig.StringValue("com/order.yaml"),
                SpecExecutionConfig.ObjectValue.FullUrl(baseUrl = "http://127.0.0.1:8080/api/v2", specs = listOf("com/order.yaml")),
                SpecExecutionConfig.ObjectValue.PartialUrl(host = "127.0.0.1", specs = listOf("com/order.yaml")),
                SpecExecutionConfig.ObjectValue.PartialUrl(port = 8080, specs = listOf("com/order.yaml")),
                SpecExecutionConfig.ObjectValue.PartialUrl(basePath = "/api/v2", specs = listOf("com/order.yaml")),
                SpecExecutionConfig.ObjectValue.PartialUrl(host = "127.0.0.1", port = 8080, specs = listOf("com/order.yaml")),
                SpecExecutionConfig.ObjectValue.PartialUrl(host = "127.0.0.1", basePath = "/api/v2", specs = listOf("com/order.yaml")),
                SpecExecutionConfig.ObjectValue.PartialUrl(port = 8080, basePath = "/api/v2", specs = listOf("com/order.yaml")),
                SpecExecutionConfig.ObjectValue.PartialUrl(host = "127.0.0.1", port = 8080, basePath = "/api/v2", specs = listOf("com/order.yaml"))
            )
        }

        @Test
        fun `should complain unexpectedKey is passed in consumes object value`() {
            val consumesString = """
            consumes:
              - url: "http://127.0.0.1:8080/api/v2"
                scheme: http
                specs:
                - "com/order.yaml"
            """.trimIndent()
            val exception = assertThrows<JsonMappingException> {
                mapper.readValue<ContractConfig>(consumesString)
            }

            assertThat(exception.originalMessage).isEqualToNormalizingWhitespace("""   
            Unknown fields: url, scheme
            Allowed fields: baseUrl, host, port, basePath, specs
            """.trimIndent())
        }

        @Test
        fun `should complain when specs array is missing`() {
            val consumesString = """
            consumes:
              - baseUrl: "http://127.0.0.1:8080/api/v2"
            """.trimIndent()
            val exception = assertThrows<JsonMappingException> {
                mapper.readValue<ContractConfig>(consumesString)
            }

            assertThat(exception.originalMessage).isEqualToNormalizingWhitespace("""
            Missing required field 'specs'
            """.trimIndent())
        }

        @Test
        fun `should complain when specs array is empty`() {
            val consumesString = """
            consumes:
              - baseUrl: "http://127.0.0.1:8080/api/v2"
                specs: []
            """.trimIndent()
            val exception = assertThrows<JsonMappingException> {
                mapper.readValue<ContractConfig>(consumesString)
            }

            assertThat(exception.originalMessage).isEqualToNormalizingWhitespace("""
            'specs' array cannot be empty
            """.trimIndent())
        }

        @Test
        fun `should complain when specs array contains non-string values`() {
            val consumesString = """
            consumes:
              - baseUrl: "http://127.0.0.1:8080/api/v2"
                specs: [1, false, "api.yaml"]
            """.trimIndent()
            val exception = assertThrows<JsonMappingException> {
                mapper.readValue<ContractConfig>(consumesString)
            }

            assertThat(exception.originalMessage).isEqualToNormalizingWhitespace("""
            'specs' must contain only strings
            """.trimIndent())
        }

        @Test
        fun `should complain when other url fields are provided alongside baseUrl`() {
            val consumesString = """
            consumes:
              - baseUrl: "http://127.0.0.1:8080/api/v2"
                host: "localhost"
                port: 3000
                basePath: "/api/v1"
                specs:
                - "com/order.yaml"
            """.trimIndent()
            val exception = assertThrows<JsonMappingException> {
                mapper.readValue<ContractConfig>(consumesString)
            }

            assertThat(exception.originalMessage).isEqualToNormalizingWhitespace("""
            Cannot combine baseUrl with host, port, basePath
            """.trimIndent())
        }

        @Test
        fun `should complain when baseUrl and other url fields are missing`() {
            val consumesString = """
            consumes:
              - specs:
                - "com/order.yaml"
            """.trimIndent()
            val exception = assertThrows<JsonMappingException> {
                mapper.readValue<ContractConfig>(consumesString)
            }

            assertThat(exception.originalMessage).isEqualToNormalizingWhitespace("""
            Must provide baseUrl or one or combination of host, port, and basePath
            """.trimIndent())
        }

        @Test
        fun `should not complain when fields are missing but consumes has simple string-value`() {
            val consumesString = """
            consumes: 
            - "http://127.0.0.1:8080/api/v2"
            """.trimIndent()
            val config = assertDoesNotThrow { mapper.readValue<ContractConfig>(consumesString) }
            val consumes = config.consumes

            assertThat(consumes).hasSize(1).hasOnlyElementsOfTypes(SpecExecutionConfig.StringValue::class.java)
            assertThat(consumes?.single() as SpecExecutionConfig.StringValue).isEqualTo(SpecExecutionConfig.StringValue("http://127.0.0.1:8080/api/v2"))
        }

        @Test
        fun `should complain when resiliencyTests is provided in consumes`() {
            val consumesString = """
            consumes:
              - baseUrl: "http://127.0.0.1:8080/api/v2"
                specs:
                  - "com/order.yaml"
                resiliencyTests:
                  enable: all
            """.trimIndent()

            val exception = assertThrows<JsonMappingException> {
                mapper.readValue<ContractConfig>(consumesString)
            }

            assertThat(exception.originalMessage).isEqualToNormalizingWhitespace("""
            Unknown fields: resiliencyTests
            Allowed fields: baseUrl, host, port, basePath, specs
            """.trimIndent())
        }
    }

    @Nested
    inner class MinimalV2LoadingTests {
        private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

        @Test
        fun `v2 config with only consumes should load`() {
            val yaml = """
                version: 2
                contracts:
                  - consumes:
                    - abc.yaml
                    - def.yaml
            """.trimIndent()

            val v2 = mapper.readValue<SpecmaticConfigV2>(yaml)
            val config = v2.transform()
            val sources = SpecmaticConfig.getSources(config)
            assertThat(sources).hasSize(1)
            assertThat(sources.single().specsUsedAsStub()).containsExactly("abc.yaml", "def.yaml")
        }

        @Test
        fun `v2 config with vanilla provides should load`() {
            val yaml = """
                version: 2
                contracts:
                  - provides:
                    - abc.yaml
                    - def.yaml
            """.trimIndent()

            val v2 = mapper.readValue<SpecmaticConfigV2>(yaml)
            val config = v2.transform()
            val sources = SpecmaticConfig.getSources(config)
            assertThat(sources).hasSize(1)
            assertThat(sources.single().specsUsedAsTest()).containsExactly("abc.yaml", "def.yaml")
        }

        @Test
        fun `v2 config with provides with base url should load`() {
            val yaml = """
                version: 2
                contracts:
                  - provides:
                    - baseUrl: "http://localhost:9010"
                      specs:
                        - abc.yaml
                        - def.yaml
            """.trimIndent()

            val v2 = mapper.readValue<SpecmaticConfigV2>(yaml)
            val config = v2.transform()
            val sources = SpecmaticConfig.getSources(config)
            val baseUrlMap = sources.single().specToTestBaseUrlMap()
            assertThat(baseUrlMap["abc.yaml"]).isEqualTo("http://localhost:9010")
            assertThat(baseUrlMap["def.yaml"]).isEqualTo("http://localhost:9010")
        }
    }

    companion object {

        @JvmStatic
        fun consumesProvider(): Stream<Arguments> {
            val withDefaultBaseUrls = listOf(
                SpecExecutionConfig.ObjectValue.FullUrl("http://localhost:3000", specs = emptyList()) to "http://localhost:3000",
                SpecExecutionConfig.ObjectValue.PartialUrl(host = "127.0.0.1", specs = emptyList()) to "http://127.0.0.1:9000",
                SpecExecutionConfig.ObjectValue.PartialUrl(port = 3000, specs = emptyList()) to "http://0.0.0.0:3000",
                SpecExecutionConfig.ObjectValue.PartialUrl(basePath = "/api/v2", specs = emptyList()) to "http://0.0.0.0:9000/api/v2"
            ).map { Arguments.of(it.first, null, it.second) }

            val withCustomBaseUrlCases = listOf(
                SpecExecutionConfig.ObjectValue.FullUrl("http://localhost:3000", specs = emptyList()) to "http://localhost:3000",
                SpecExecutionConfig.ObjectValue.PartialUrl(host = "127.0.0.1", specs = emptyList()) to "https://127.0.0.1:5000",
                SpecExecutionConfig.ObjectValue.PartialUrl(port = 3000, specs = emptyList()) to "https://localhost:3000",
                SpecExecutionConfig.ObjectValue.PartialUrl(basePath = "/api/v2", specs = emptyList()) to "https://localhost:5000/api/v2"
            ).map { Arguments.of(it.first, "https://localhost:5000", it.second) }

            val baseUrlWithBasePath = listOf(
                SpecExecutionConfig.ObjectValue.FullUrl("http://localhost:3000", specs = emptyList()) to "http://localhost:3000",
                SpecExecutionConfig.ObjectValue.PartialUrl(host = "127.0.0.1", specs = emptyList()) to "http://127.0.0.1:8080/api",
                SpecExecutionConfig.ObjectValue.PartialUrl(port = 3000, specs = emptyList()) to "http://localhost:3000/api",
                SpecExecutionConfig.ObjectValue.PartialUrl(basePath = "/api/v2", specs = emptyList()) to "http://localhost:8080/api/v2"
            ).map { Arguments.of(it.first, "http://localhost:8080/api", it.second) }

            val partialUrlWithMultipleValues = listOf(
                SpecExecutionConfig.ObjectValue.PartialUrl(host = "127.0.0.1", port = 8080, specs = emptyList()) to "http://127.0.0.1:8080",
                SpecExecutionConfig.ObjectValue.PartialUrl(host = "127.0.0.1", basePath = "/api/v2", specs = emptyList()) to "http://127.0.0.1:5000/api/v2",
                SpecExecutionConfig.ObjectValue.PartialUrl(port = 8080, basePath = "/api/v2", specs = emptyList()) to "http://0.0.0.0:8080/api/v2",
                SpecExecutionConfig.ObjectValue.PartialUrl(host = "127.0.0.1", port = 8080, basePath = "/api/v2", specs = emptyList()) to "http://127.0.0.1:8080/api/v2"
            ).map { Arguments.of(it.first, "http://0.0.0.0:5000", it.second) }

            return withDefaultBaseUrls.plus(withCustomBaseUrlCases).plus(baseUrlWithBasePath).plus(partialUrlWithMultipleValues).stream()
        }
    }
}
