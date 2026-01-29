package io.specmatic.core

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import io.specmatic.core.config.v3.SpecExecutionConfig
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.specmatic.core.config.v2.ContractConfig
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.utilities.ContractSourceEntry
import io.specmatic.core.utilities.Flags.Companion.EXAMPLE_DIRECTORIES
import io.specmatic.core.config.v2.SpecmaticConfigV2
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
        assertThat(sources.first().test).isEqualTo(listOf(SpecExecutionConfig.StringValue("com/petstore/1.spec")))
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
        assertThat(sources.first().test).isEqualTo(listOf(SpecExecutionConfig.StringValue("path/to/contract.spec")))
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
        assertThat(sources.first().test).isEqualTo(listOf(SpecExecutionConfig.StringValue("com/petstore/1.yaml")))
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
    fun `parse specmatic config v2 with proxy configuration`() {
        val configYaml = """
            version: 2
            contracts: []
            proxy:
              port: 9000
              targetUrl: http://example.com/api
              consumes:
              - openapi_spec1.yaml
              - openapi_spec2.yaml
        """.trimIndent()

        val config = ObjectMapper(YAMLFactory()).registerKotlinModule().readValue(
            configYaml,
            SpecmaticConfigV2::class.java
        ).transform()

        assertThat(config.getProxyConfig()).isEqualTo(
            ProxyConfig(
                port = 9000, targetUrl = "http://example.com/api",
                consumes = listOf("openapi_spec1.yaml", "openapi_spec2.yaml")
            )
        )
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
            assertThat(config.getSchemaExampleDefault()).isTrue()
            assertThat(config.getMaxTestRequestCombinations()).isEqualTo(50)
            assertThat(config.getExamples()).isEqualTo(listOf("folder1/examples", "folder2/examples"))
            assertThat(config.getTestTimeoutInMilliseconds()).isEqualTo(5000)
            assertThat(config.getStubDelayInMilliseconds()).isEqualTo(1000L)
        } finally {
            properties.forEach { System.clearProperty(it.key) }
        }
    }

    @Test
    fun `should read test configuration from system properties when config is absent`() {
        val properties = mapOf(
            io.specmatic.core.utilities.Flags.TEST_STRICT_MODE to "true",
            io.specmatic.core.utilities.Flags.TEST_LENIENT_MODE to "false",
            io.specmatic.core.utilities.Flags.SPECMATIC_TEST_PARALLELISM to "dynamic",
            io.specmatic.core.utilities.Flags.MAX_TEST_COUNT to "100",
            MAX_TEST_REQUEST_COMBINATIONS to "50"
        )
        try {
            properties.forEach { System.setProperty(it.key, it.value) }
            val config = SpecmaticConfig()

            assertThat(config.getTestStrictMode()).isTrue()
            assertThat(config.getTestLenientMode()).isFalse()
            assertThat(config.getTestParallelism()).isEqualTo("dynamic")
            assertThat(config.getMaxTestCount()).isEqualTo(100)
            assertThat(config.getMaxTestRequestCombinations()).isEqualTo(50)
        } finally {
            properties.forEach { System.clearProperty(it.key) }
        }
    }

    @Test
    fun `should read stub configuration from system properties when config is absent`() {
        val properties = mapOf(
            io.specmatic.core.utilities.Flags.SPECMATIC_BASE_URL to "http://localhost:8080"
        )
        try {
            properties.forEach { System.setProperty(it.key, it.value) }
            val config = SpecmaticConfig()

            assertThat(config.getDefaultBaseUrl()).isEqualTo("http://localhost:8080")
        } finally {
            properties.forEach { System.clearProperty(it.key) }
        }
    }

    @Test
    fun `should read v2-only properties from system properties when config is v1 or absent`() {
        val properties = mapOf(
            io.specmatic.core.utilities.Flags.SPECMATIC_FUZZY to "true",
            io.specmatic.core.utilities.Flags.SPECMATIC_ESCAPE_SOAP_ACTION to "true",
            io.specmatic.core.utilities.Flags.SPECMATIC_PRETTY_PRINT to "false",
            io.specmatic.core.utilities.Flags.IGNORE_INLINE_EXAMPLE_WARNINGS to "true"
        )
        try {
            properties.forEach { System.setProperty(it.key, it.value) }

            // Test with v1 config (should fall back to system properties)
            val configV1 = SpecmaticConfig(version = SpecmaticConfigVersion.VERSION_1)
            assertThat(configV1.getFuzzyMatchingEnabled()).isTrue()
            assertThat(configV1.getEscapeSoapAction()).isTrue()
            assertThat(configV1.getPrettyPrint()).isFalse()
            assertThat(configV1.getIgnoreInlineExampleWarnings()).isTrue()

            // Test with no config (should also use system properties)
            val configEmpty = SpecmaticConfig()
            assertThat(configEmpty.getFuzzyMatchingEnabled()).isTrue()
            assertThat(configEmpty.getEscapeSoapAction()).isTrue()
            assertThat(configEmpty.getPrettyPrint()).isFalse()
            assertThat(configEmpty.getIgnoreInlineExampleWarnings()).isTrue()
        } finally {
            properties.forEach { System.clearProperty(it.key) }
        }
    }

    @Test
    fun `should use default value for prettyPrint when neither config nor system property is set`() {
        val config = SpecmaticConfig()

        // prettyPrint defaults to true
        assertThat(config.getPrettyPrint()).isTrue()
    }

    @Test
    fun `should prefer v2 test base url from config over system properties`() {
        val config = SpecmaticConfig(
            version = SpecmaticConfigVersion.VERSION_2,
            test = TestConfiguration(baseUrl = "http://config-base-url")
        )
        try {
            System.setProperty("testBaseURL", "http://property-base-url")
            System.setProperty("host", "property-host")
            System.setProperty("port", "1234")

            assertThat(config.getTestBaseUrl()).isEqualTo("http://config-base-url")
        } finally {
            System.clearProperty("testBaseURL")
            System.clearProperty("host")
            System.clearProperty("port")
        }
    }

    @Test
    fun `should use test base url from system properties when config is missing`() {
        val config = SpecmaticConfig(version = SpecmaticConfigVersion.VERSION_2, test = TestConfiguration())
        try {
            System.setProperty("testBaseURL", "http://property-base-url")

            assertThat(config.getTestBaseUrl()).isEqualTo("http://property-base-url")
        } finally {
            System.clearProperty("testBaseURL")
        }
    }

    @Test
    fun `should construct test base url from host and port when properties are set`() {
        val config = SpecmaticConfig(version = SpecmaticConfigVersion.VERSION_2, test = TestConfiguration())
        try {
            System.setProperty("host", "property-host")
            System.setProperty("port", "1234")

            assertThat(config.getTestBaseUrl()).isEqualTo("http://property-host:1234")
        } finally {
            System.clearProperty("host")
            System.clearProperty("port")
        }
    }

    @Test
    fun `should use protocol when constructing test base url from properties`() {
        val config = SpecmaticConfig(version = SpecmaticConfigVersion.VERSION_2, test = TestConfiguration())
        try {
            System.setProperty("host", "property-host")
            System.setProperty("port", "1234")
            System.setProperty("protocol", "https")

            assertThat(config.getTestBaseUrl()).isEqualTo("https://property-host:1234")
        } finally {
            System.clearProperty("host")
            System.clearProperty("port")
            System.clearProperty("protocol")
        }
    }

    @Test
    fun `should prefer v2 swagger UI base url from config over system properties`() {
        val config = SpecmaticConfig(
            version = SpecmaticConfigVersion.VERSION_2,
            test = TestConfiguration(swaggerUIBaseURL = "http://config-swagger-ui")
        )
        try {
            System.setProperty("swaggerUIBaseURL", "http://property-swagger-ui")

            assertThat(config.getTestSwaggerUIBaseUrl()).isEqualTo("http://config-swagger-ui")
        } finally {
            System.clearProperty("swaggerUIBaseURL")
        }
    }

    @ParameterizedTest
    @MethodSource("testFilterPropertyCases")
    fun `should prefer v2 test config over system properties for test filter fields`(case: TestFilterPropertyCase) {
        val config = SpecmaticConfig(
            version = SpecmaticConfigVersion.VERSION_2,
            test = TestConfiguration(
                filterName = "config-filter-name",
                filterNotName = "config-filter-not-name",
                overlayFilePath = "config-overlay-file-path",
            )
        )
        try {
            System.setProperty(case.propertyName, case.systemPropertyValue)

            assertThat(getTestFilterField(config, case.propertyName)).isEqualTo(case.configValue)
        } finally {
            System.clearProperty(case.propertyName)
        }
    }

    @ParameterizedTest
    @MethodSource("testFilterPropertyCases")
    fun `should fall back to system properties for test filter fields when v2 config is missing`(case: TestFilterPropertyCase) {
        val config = SpecmaticConfig(
            version = SpecmaticConfigVersion.VERSION_2,
            test = TestConfiguration()
        )
        try {
            System.setProperty(case.propertyName, case.systemPropertyValue)

            assertThat(getTestFilterField(config, case.propertyName)).isEqualTo(case.systemPropertyValue)
        } finally {
            System.clearProperty(case.propertyName)
        }
    }

    @ParameterizedTest
    @MethodSource("testFilterPropertyCases")
    fun `should fall back to system properties for test filter fields when config is v1`(case: TestFilterPropertyCase) {
        val config = SpecmaticConfig(
            version = SpecmaticConfigVersion.VERSION_1,
            test = TestConfiguration(
                filterName = "config-filter-name",
                filterNotName = "config-filter-not-name",
                overlayFilePath = "config-overlay-file-path",
            )
        )
        try {
            System.setProperty(case.propertyName, case.systemPropertyValue)

            assertThat(getTestFilterField(config, case.propertyName)).isEqualTo(case.systemPropertyValue)
        } finally {
            System.clearProperty(case.propertyName)
        }
    }

    @Test
    fun `should handle null values gracefully`() {
        val config = SpecmaticConfig(
            test = TestConfiguration(
                strictMode = null,
                lenientMode = null,
                parallelism = null
            ),
            stub = StubConfiguration()
        )

        // Should return null or fallback values, not throw
        assertThat(config.getTestStrictMode()).isNull()
        assertThat(config.getTestLenientMode()).isNull()
        assertThat(config.getTestParallelism()).isNull()
    }

    @Test
    fun `should resolve template defaults for scalar values in config`() {
        val propertyKey = "SPECMATIC_TEST_TEMPLATE_GENERATIVE"
        val configYaml = """
            version: 2
            contracts: []
            stub:
              generative: "{$propertyKey:true}"
        """.trimIndent()
        val configFile = File.createTempFile("specmatic-template", ".yaml")
        try {
            System.clearProperty(propertyKey)
            configFile.writeText(configYaml)
            val config = loadSpecmaticConfig(configFile.path)
            assertThat(config.getStubGenerative()).isTrue()
        } finally {
            System.clearProperty(propertyKey)
            configFile.delete()
        }
    }

    @Test
    fun `should resolve template values from system properties`() {
        val propertyKey = "SPECMATIC_TEST_TEMPLATE_DELAY"
        val configYaml = """
            version: 2
            contracts: []
            stub:
              delayInMilliseconds: "{$propertyKey:100}"
        """.trimIndent()
        val configFile = File.createTempFile("specmatic-template", ".yaml")
        try {
            System.setProperty(propertyKey, "250")
            configFile.writeText(configYaml)
            val config = loadSpecmaticConfig(configFile.path)
            assertThat(config.getStubDelayInMilliseconds()).isEqualTo(250L)
        } finally {
            System.clearProperty(propertyKey)
            configFile.delete()
        }
    }

    @Test
    fun `should error when resolved template value cannot be parsed`() {
        val propertyKey = "SPECMATIC_TEST_TEMPLATE_INVALID"
        val configYaml = """
            version: 2
            contracts: []
            stub:
              generative: "{$propertyKey:false}"
        """.trimIndent()
        val configFile = File.createTempFile("specmatic-template", ".yaml")
        try {
            System.setProperty(propertyKey, "abc")
            configFile.writeText(configYaml)
            assertThrows<Exception> { loadSpecmaticConfig(configFile.path) }
        } finally {
            System.clearProperty(propertyKey)
            configFile.delete()
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
                    SpecExecutionConfig.StringValue("9000_first.yaml"),
                    SpecExecutionConfig.StringValue("9000_second.yaml"),
                    SpecExecutionConfig.StringValue("9001_first.yaml"),
                    SpecExecutionConfig.StringValue("9001_second.yaml"),
                    SpecExecutionConfig.StringValue("9002_first.yaml"),
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
                ),
            )

            val source2 = Source(
                test = listOf(
                    SpecExecutionConfig.StringValue("9000_third.yaml"),
                    SpecExecutionConfig.StringValue("9001_third.yaml"),
                    SpecExecutionConfig.StringValue("9001_fourth.yaml"),
                    SpecExecutionConfig.StringValue("9002_second.yaml"),
                    SpecExecutionConfig.StringValue("9000_third.yaml"),
                    SpecExecutionConfig.ObjectValue.FullUrl(
                        specs = listOf("9001_third.yaml", "9001_fourth.yaml"),
                        baseUrl = "http://localhost:9001"
                    ),
                    SpecExecutionConfig.ObjectValue.FullUrl(
                        specs = listOf("9002_second.yaml"),
                        baseUrl = "http://localhost:9002"
                    ),
                ),
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
            Missing required field 'specs' in 'consumes' field in Specmatic configuration
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
            'specs' array cannot be empty in 'consumes' field in Specmatic configuration
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
            'specs' must contain only strings in 'consumes' field in Specmatic configuration
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

        @Test
        fun `should be able to deserialize ConfigValue with specType and config`() {
            val consumesString = """
            consumes:
              - specs:
                  - "io/specmatic/order.yaml"
                  - "io/specmatic/payment.yaml"
                specType: "ASYNCAPI"
                config:
                  servers:
                    - protocol: "sqs"
                      host: "localhost:8080"
                    - protocol: "kafka"
                      host: "localhost:9092"
                  timeout: 30
                  retries: 3
            """.trimIndent()

            val config = assertDoesNotThrow { mapper.readValue<ContractConfig>(consumesString) }
            val consumes = config.consumes

            assertThat(consumes).hasSize(1)
            assertThat(consumes?.single()).isInstanceOf(SpecExecutionConfig.ConfigValue::class.java)

            val configValue = consumes?.single() as SpecExecutionConfig.ConfigValue
            assertThat(configValue.specs).containsExactly("io/specmatic/order.yaml", "io/specmatic/payment.yaml")
            assertThat(configValue.specType).isEqualTo("ASYNCAPI")
            assertThat(configValue.config).isNotEmpty
            assertThat(configValue.config["servers"]).isInstanceOf(List::class.java)
            assertThat(configValue.config["timeout"]).isEqualTo(30)
            assertThat(configValue.config["retries"]).isEqualTo(3)

            @Suppress("UNCHECKED_CAST")
            val servers = configValue.config["servers"] as List<Map<String, Any>>
            assertThat(servers).hasSize(2)
            assertThat(servers[0]["protocol"]).isEqualTo("sqs")
            assertThat(servers[0]["host"]).isEqualTo("localhost:8080")
            assertThat(servers[1]["protocol"]).isEqualTo("kafka")
            assertThat(servers[1]["host"]).isEqualTo("localhost:9092")
        }

        @Test
        fun `should complain when specType is missing in ConfigValue`() {
            val consumesString = """
            consumes:
              - specs:
                  - "io/specmatic/order.yaml"
                config:
                  timeout: 30
            """.trimIndent()

            val exception = assertThrows<JsonMappingException> {
                mapper.readValue<ContractConfig>(consumesString)
            }

            assertThat(exception.originalMessage).contains("Missing or invalid required field 'specType'")
        }

        @Test
        fun `should complain when config is missing in ConfigValue`() {
            val consumesString = """
            consumes:
              - specs:
                  - "io/specmatic/order.yaml"
                specType: "ASYNCAPI"
            """.trimIndent()

            val exception = assertThrows<JsonMappingException> {
                mapper.readValue<ContractConfig>(consumesString)
            }

            assertThat(exception.originalMessage).contains("Missing or invalid required field 'config'")
        }

        @Test
        fun `should complain when specs is missing in ConfigValue`() {
            val consumesString = """
            consumes:
              - specType: "ASYNCAPI"
                config:
                  timeout: 30
            """.trimIndent()

            val exception = assertThrows<JsonMappingException> {
                mapper.readValue<ContractConfig>(consumesString)
            }

            assertThat(exception.originalMessage).contains("Missing required field 'specs'")
        }
    }

    @Nested
    inner class TestSerializeAndDeSerializeTests {
        private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

        @Test
        fun `should be able to deserialize ConfigValue with specType and config`() {
            val providesString = """
            provides:
              - specs:
                  - "io/specmatic/order.yaml"
                  - "io/specmatic/payment.yaml"
                specType: "ASYNCAPI"
                config:
                  servers:
                    - protocol: "sqs"
                      host: "localhost:8080"
                    - protocol: "kafka"
                      host: "localhost:9092"
                  timeout: 30
                  retries: 3
            """.trimIndent()

            val config = assertDoesNotThrow { mapper.readValue<ContractConfig>(providesString) }
            val provides = config.provides

            assertThat(provides).hasSize(1)
            assertThat(provides?.single()).isInstanceOf(SpecExecutionConfig.ConfigValue::class.java)

            val configValue = provides?.single() as SpecExecutionConfig.ConfigValue
            assertThat(configValue.specs).containsExactly("io/specmatic/order.yaml", "io/specmatic/payment.yaml")
            assertThat(configValue.specType).isEqualTo("ASYNCAPI")
            assertThat(configValue.config).isNotEmpty
            assertThat(configValue.config["servers"]).isInstanceOf(List::class.java)
            assertThat(configValue.config["timeout"]).isEqualTo(30)
            assertThat(configValue.config["retries"]).isEqualTo(3)

            @Suppress("UNCHECKED_CAST")
            val servers = configValue.config["servers"] as List<Map<String, Any>>
            assertThat(servers).hasSize(2)
            assertThat(servers[0]["protocol"]).isEqualTo("sqs")
            assertThat(servers[0]["host"]).isEqualTo("localhost:8080")
            assertThat(servers[1]["protocol"]).isEqualTo("kafka")
            assertThat(servers[1]["host"]).isEqualTo("localhost:9092")
        }

        @Test
        fun `should complain when specType is missing in ConfigValue`() {
            val providesString = """
            provides:
              - specs:
                  - "io/specmatic/order.yaml"
                config:
                  timeout: 30
            """.trimIndent()

            val exception = assertThrows<JsonMappingException> {
                mapper.readValue<ContractConfig>(providesString)
            }

            assertThat(exception.originalMessage).isEqualTo("Missing or invalid required field 'specType' key in 'provides' field in Specmatic configuration")
        }

        @Test
        fun `should complain when config is missing in ConfigValue`() {
            val providesString = """
            provides:
              - specs:
                  - "io/specmatic/order.yaml"
                specType: "ASYNCAPI"
            """.trimIndent()

            val exception = assertThrows<JsonMappingException> {
                mapper.readValue<ContractConfig>(providesString)
            }

            assertThat(exception.originalMessage).isEqualTo("Missing or invalid required field 'config' key in 'provides' field in Specmatic configuration")
        }

        @Test
        fun `should complain when specs is missing in ConfigValue`() {
            val providesString = """
            provides:
              - specType: "ASYNCAPI"
                config:
                  timeout: 30
            """.trimIndent()

            val exception = assertThrows<JsonMappingException> {
                mapper.readValue<ContractConfig>(providesString)
            }

            assertThat(exception.originalMessage).isEqualTo("Missing required field 'specs' in 'provides' field in Specmatic configuration")
        }
    }

    @Nested
    inner class TestConfigForTests {
        @Test
        fun `testConfigFor should return config for matching spec path and type`() {
            val config = SpecmaticConfig(
                sources = listOf(
                    Source(
                        test = listOf(
                            SpecExecutionConfig.ConfigValue(
                                specs = listOf("io/specmatic/order.yaml", "io/specmatic/payment.yaml"),
                                specType = "ASYNCAPI",
                                config = mapOf(
                                    "timeout" to 30,
                                    "retries" to 3,
                                    "servers" to listOf(
                                        mapOf("protocol" to "kafka", "host" to "localhost:9092")
                                    )
                                )
                            )
                        )
                    )
                )
            )

            val result = config.testConfigFor("io/specmatic/order.yaml", "ASYNCAPI")

            assertThat(result).isNotEmpty
            assertThat(result["timeout"]).isEqualTo(30)
            assertThat(result["retries"]).isEqualTo(3)
            assertThat(result["servers"]).isInstanceOf(List::class.java)
        }

        @Test
        fun `testConfigFor should return empty map when spec path does not match`() {
            val config = SpecmaticConfig(
                sources = listOf(
                    Source(
                        test = listOf(
                            SpecExecutionConfig.ConfigValue(
                                specs = listOf("io/specmatic/order.yaml"),
                                specType = "ASYNCAPI",
                                config = mapOf("timeout" to 30)
                            )
                        )
                    )
                )
            )

            val result = config.testConfigFor("io/specmatic/unknown.yaml", "ASYNCAPI")

            assertThat(result).isEmpty()
        }

        @Test
        fun `testConfigFor should return empty map when spec type does not match`() {
            val config = SpecmaticConfig(
                sources = listOf(
                    Source(
                        test = listOf(
                            SpecExecutionConfig.ConfigValue(
                                specs = listOf("io/specmatic/order.yaml"),
                                specType = "ASYNCAPI",
                                config = mapOf("timeout" to 30)
                            )
                        )
                    )
                )
            )

            val result = config.testConfigFor("io/specmatic/order.yaml", "OPENAPI")

            assertThat(result).isEmpty()
        }

        @Test
        fun `testConfigFor should return empty map when no ConfigValue exists`() {
            val config = SpecmaticConfig(
                sources = listOf(
                    Source(
                        test = listOf(
                            SpecExecutionConfig.StringValue("io/specmatic/order.yaml")
                        )
                    )
                )
            )

            val result = config.testConfigFor("io/specmatic/order.yaml", "ASYNCAPI")

            assertThat(result).isEmpty()
        }

        @Test
        fun `testConfigFor should return config from first matching source`() {
            val config = SpecmaticConfig(
                sources = listOf(
                    Source(
                        test = listOf(
                            SpecExecutionConfig.ConfigValue(
                                specs = listOf("io/specmatic/order.yaml"),
                                specType = "ASYNCAPI",
                                config = mapOf("timeout" to 30)
                            )
                        )
                    ),
                    Source(
                        test = listOf(
                            SpecExecutionConfig.ConfigValue(
                                specs = listOf("io/specmatic/order.yaml"),
                                specType = "ASYNCAPI",
                                config = mapOf("timeout" to 60)
                            )
                        )
                    )
                )
            )

            val result = config.testConfigFor("io/specmatic/order.yaml", "ASYNCAPI")

            assertThat(result["timeout"]).isEqualTo(30)
        }

        @Test
        fun `testConfigFor should handle multiple ConfigValues and return first match`() {
            val config = SpecmaticConfig(
                sources = listOf(
                    Source(
                        test = listOf(
                            SpecExecutionConfig.ConfigValue(
                                specs = listOf("io/specmatic/payment.yaml"),
                                specType = "ASYNCAPI",
                                config = mapOf("timeout" to 20)
                            ),
                            SpecExecutionConfig.ConfigValue(
                                specs = listOf("io/specmatic/order.yaml"),
                                specType = "ASYNCAPI",
                                config = mapOf("timeout" to 30)
                            )
                        )
                    )
                )
            )

            val result = config.testConfigFor("io/specmatic/order.yaml", "ASYNCAPI")

            assertThat(result["timeout"]).isEqualTo(30)
        }

        @Test
        fun `testConfigFor should return empty map when sources are empty`() {
            val config = SpecmaticConfig(sources = emptyList())

            val result = config.testConfigFor("io/specmatic/order.yaml", "ASYNCAPI")

            assertThat(result).isEmpty()
        }

        @Test
        fun `testConfigFor should handle complex config with nested structures`() {
            val config = SpecmaticConfig(
                sources = listOf(
                    Source(
                        test = listOf(
                            SpecExecutionConfig.ConfigValue(
                                specs = listOf("io/specmatic/order.yaml"),
                                specType = "ASYNCAPI",
                                config = mapOf(
                                    "servers" to listOf(
                                        mapOf("protocol" to "kafka", "host" to "localhost:9092"),
                                        mapOf("protocol" to "sqs", "host" to "localhost:8080")
                                    ),
                                    "timeout" to 30,
                                    "retries" to 3,
                                    "advanced" to mapOf(
                                        "compression" to true,
                                        "maxMessageSize" to 1024
                                    )
                                )
                            )
                        )
                    )
                )
            )

            val result = config.testConfigFor("io/specmatic/order.yaml", "ASYNCAPI")

            assertThat(result).isNotEmpty
            assertThat(result["timeout"]).isEqualTo(30)
            assertThat(result["retries"]).isEqualTo(3)
            assertThat(result["servers"]).isInstanceOf(List::class.java)
            assertThat(result["advanced"]).isInstanceOf(Map::class.java)

            @Suppress("UNCHECKED_CAST")
            val servers = result["servers"] as List<Map<String, Any>>
            assertThat(servers).hasSize(2)
            assertThat(servers[0]["protocol"]).isEqualTo("kafka")

            @Suppress("UNCHECKED_CAST")
            val advanced = result["advanced"] as Map<String, Any>
            assertThat(advanced["compression"]).isEqualTo(true)
            assertThat(advanced["maxMessageSize"]).isEqualTo(1024)
        }
    }

    @Nested
    inner class StubConfigForTests {
        @Test
        fun `stubConfigFor should return config for matching spec path and type`() {
            val config = SpecmaticConfig(
                sources = listOf(
                    Source(
                        stub = listOf(
                            SpecExecutionConfig.ConfigValue(
                                specs = listOf("io/specmatic/order.yaml", "io/specmatic/payment.yaml"),
                                specType = "ASYNCAPI",
                                config = mapOf(
                                    "timeout" to 30,
                                    "retries" to 3,
                                    "servers" to listOf(
                                        mapOf("protocol" to "sqs", "host" to "localhost:8080")
                                    )
                                )
                            )
                        )
                    )
                )
            )

            val result = config.stubConfigFor("io/specmatic/order.yaml", "ASYNCAPI")

            assertThat(result).isNotEmpty
            assertThat(result["timeout"]).isEqualTo(30)
            assertThat(result["retries"]).isEqualTo(3)
            assertThat(result["servers"]).isInstanceOf(List::class.java)
        }

        @Test
        fun `stubConfigFor should return empty map when spec path does not match`() {
            val config = SpecmaticConfig(
                sources = listOf(
                    Source(
                        stub = listOf(
                            SpecExecutionConfig.ConfigValue(
                                specs = listOf("io/specmatic/order.yaml"),
                                specType = "ASYNCAPI",
                                config = mapOf("timeout" to 30)
                            )
                        )
                    )
                )
            )

            val result = config.stubConfigFor("io/specmatic/unknown.yaml", "ASYNCAPI")

            assertThat(result).isEmpty()
        }

        @Test
        fun `stubConfigFor should return empty map when spec type does not match`() {
            val config = SpecmaticConfig(
                sources = listOf(
                    Source(
                        stub = listOf(
                            SpecExecutionConfig.ConfigValue(
                                specs = listOf("io/specmatic/order.yaml"),
                                specType = "ASYNCAPI",
                                config = mapOf("timeout" to 30)
                            )
                        )
                    )
                )
            )

            val result = config.stubConfigFor("io/specmatic/order.yaml", "OPENAPI")

            assertThat(result).isEmpty()
        }

        @Test
        fun `stubConfigFor should return empty map when no ConfigValue exists`() {
            val config = SpecmaticConfig(
                sources = listOf(
                    Source(
                        stub = listOf(
                            SpecExecutionConfig.StringValue("io/specmatic/order.yaml")
                        )
                    )
                )
            )

            val result = config.stubConfigFor("io/specmatic/order.yaml", "ASYNCAPI")

            assertThat(result).isEmpty()
        }

        @Test
        fun `stubConfigFor should return config from first matching source`() {
            val config = SpecmaticConfig(
                sources = listOf(
                    Source(
                        stub = listOf(
                            SpecExecutionConfig.ConfigValue(
                                specs = listOf("io/specmatic/order.yaml"),
                                specType = "ASYNCAPI",
                                config = mapOf("timeout" to 30)
                            )
                        )
                    ),
                    Source(
                        stub = listOf(
                            SpecExecutionConfig.ConfigValue(
                                specs = listOf("io/specmatic/order.yaml"),
                                specType = "ASYNCAPI",
                                config = mapOf("timeout" to 60)
                            )
                        )
                    )
                )
            )

            val result = config.stubConfigFor("io/specmatic/order.yaml", "ASYNCAPI")

            assertThat(result["timeout"]).isEqualTo(30)
        }

        @Test
        fun `stubConfigFor should handle multiple ConfigValues and return first match`() {
            val config = SpecmaticConfig(
                sources = listOf(
                    Source(
                        stub = listOf(
                            SpecExecutionConfig.ConfigValue(
                                specs = listOf("io/specmatic/payment.yaml"),
                                specType = "ASYNCAPI",
                                config = mapOf("timeout" to 20)
                            ),
                            SpecExecutionConfig.ConfigValue(
                                specs = listOf("io/specmatic/order.yaml"),
                                specType = "ASYNCAPI",
                                config = mapOf("timeout" to 30)
                            )
                        )
                    )
                )
            )

            val result = config.stubConfigFor("io/specmatic/order.yaml", "ASYNCAPI")

            assertThat(result["timeout"]).isEqualTo(30)
        }

        @Test
        fun `stubConfigFor should return empty map when sources are empty`() {
            val config = SpecmaticConfig(sources = emptyList())

            val result = config.stubConfigFor("io/specmatic/order.yaml", "ASYNCAPI")

            assertThat(result).isEmpty()
        }

        @Test
        fun `stubConfigFor should handle complex config with nested structures`() {
            val config = SpecmaticConfig(
                sources = listOf(
                    Source(
                        stub = listOf(
                            SpecExecutionConfig.ConfigValue(
                                specs = listOf("io/specmatic/order.yaml"),
                                specType = "ASYNCAPI",
                                config = mapOf(
                                    "servers" to listOf(
                                        mapOf("protocol" to "sqs", "host" to "localhost:8080"),
                                        mapOf("protocol" to "kafka", "host" to "localhost:9092")
                                    ),
                                    "timeout" to 30,
                                    "retries" to 3,
                                    "advanced" to mapOf(
                                        "compression" to true,
                                        "maxMessageSize" to 2048
                                    )
                                )
                            )
                        )
                    )
                )
            )

            val result = config.stubConfigFor("io/specmatic/order.yaml", "ASYNCAPI")

            assertThat(result).isNotEmpty
            assertThat(result["timeout"]).isEqualTo(30)
            assertThat(result["retries"]).isEqualTo(3)
            assertThat(result["servers"]).isInstanceOf(List::class.java)
            assertThat(result["advanced"]).isInstanceOf(Map::class.java)

            @Suppress("UNCHECKED_CAST")
            val servers = result["servers"] as List<Map<String, Any>>
            assertThat(servers).hasSize(2)
            assertThat(servers[0]["protocol"]).isEqualTo("sqs")

            @Suppress("UNCHECKED_CAST")
            val advanced = result["advanced"] as Map<String, Any>
            assertThat(advanced["compression"]).isEqualTo(true)
            assertThat(advanced["maxMessageSize"]).isEqualTo(2048)
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

        @JvmStatic
        fun testFilterPropertyCases(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(
                    TestFilterPropertyCase(
                        propertyName = "filterName",
                        configValue = "config-filter-name",
                        systemPropertyValue = "property-filter-name"
                    )
                ),
                Arguments.of(
                    TestFilterPropertyCase(
                        propertyName = "filterNotName",
                        configValue = "config-filter-not-name",
                        systemPropertyValue = "property-filter-not-name"
                    )
                ),
                Arguments.of(
                    TestFilterPropertyCase(
                        propertyName = "overlayFilePath",
                        configValue = "config-overlay-file-path",
                        systemPropertyValue = "property-overlay-file-path"
                    )
                )
            )
        }

    }

    private fun getTestFilterField(config: SpecmaticConfig, propertyName: String): String? =
        when (propertyName) {
            "filterName" -> config.getTestFilterName()
            "filterNotName" -> config.getTestFilterNotName()
            "overlayFilePath" -> config.getTestOverlayFilePath()
            else -> error("Unknown test filter field: $propertyName")
        }

    data class TestFilterPropertyCase(
        val propertyName: String,
        val configValue: String,
        val systemPropertyValue: String
    )
}
