package io.specmatic.test

import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.TestConfiguration
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.SpecmaticConfigVersion.VERSION_2
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.SpecmaticConfigV3
import io.specmatic.core.config.v3.components.runOptions.OpenApiTestConfig
import io.specmatic.core.config.v3.components.runOptions.TestRunOptions
import io.specmatic.core.config.v3.components.services.CommonServiceConfig
import io.specmatic.core.config.v3.components.services.TestServiceConfig
import io.specmatic.core.config.v3.components.settings.TestSettings
import io.specmatic.core.utilities.yamlMapper
import io.specmatic.reporter.model.SpecType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ContractTestSettingsTest {
    private val filterPropertyKey = "filter"
    private val originalFilterValue = System.getProperty(filterPropertyKey)

    @AfterEach
    fun restoreFilterProperty() {
        if (originalFilterValue != null) {
            System.setProperty(filterPropertyKey, originalFilterValue)
        } else {
            System.clearProperty(filterPropertyKey)
        }
    }

    @Test
    fun `getReportFilter returns config filter when override matches`() {
        System.setProperty(filterPropertyKey, "PATH='/config'")
        val settings = ContractTestSettings(filter = "PATH='/config'")
        assertThat(settings.getReportFilter()).isEqualTo("PATH='/config'")
    }

    @Test
    fun `getReportFilter combines config and override filters when they differ`() {
        System.setProperty(filterPropertyKey, "PATH='/config'")
        val settings = ContractTestSettings(filter = "STATUS='200'")
        assertThat(settings.getReportFilter()).isEqualTo("( PATH='/config' ) && ( STATUS='200' )")
    }

    @Test
    fun `getReportFilter uses override when config filter is missing`() {
        System.clearProperty(filterPropertyKey)
        val settings = ContractTestSettings(filter = "STATUS='200'")
        assertThat(settings.getReportFilter()).isEqualTo("STATUS='200'")
    }

    @Test
    fun `getReportFilter uses config filter when override is missing`() {
        System.setProperty(filterPropertyKey, "PATH='/config'")
        val settings = ContractTestSettings(filter = null)
        assertThat(settings.getReportFilter()).isEqualTo("PATH='/config'")
    }

    @Test
    fun `getSpecmaticConfig should override config test base url when explicit base url is provided`(@TempDir tempDir: File) {
        val configFile = writeSpecmaticConfig(tempDir, baseUrl = "http://config.example:9000")
        val settings = ContractTestSettings(configFile = configFile.absolutePath, testBaseURL = "http://override.example:8080")
        assertThat(settings.baseUrlFromConfig()).isEqualTo("http://config.example:9000")
        assertThat(settings.getSpecmaticConfig().getTestBaseUrl(SpecType.OPENAPI)).isEqualTo("http://override.example:8080")
    }

    @Test
    fun `getSpecmaticConfig should use config test base url when explicit base url is not provided`(@TempDir tempDir: File) {
        val configFile = writeSpecmaticConfig(tempDir, baseUrl = "http://config.example:9000")
        val settings = ContractTestSettings(configFile = configFile.absolutePath)
        assertThat(settings.baseUrlFromConfig()).isEqualTo("http://config.example:9000")
        assertThat(settings.getSpecmaticConfig().getTestBaseUrl(SpecType.OPENAPI)).isEqualTo("http://config.example:9000")
    }

    @Test
    fun `getSpecmaticConfig should override resiliency config to all when generative is true`(@TempDir tempDir: File) {
        val configFile = writeSpecmaticConfig(tempDir, resiliency = ResiliencyTestSuite.positiveOnly)
        val settings = ContractTestSettings(configFile = configFile.absolutePath, generative = true)
        assertThat(settings.getSpecmaticConfig().getResiliencyTestsEnabled()).isEqualTo(ResiliencyTestSuite.all)
    }

    @Test
    fun `getSpecmaticConfig should override resiliency config to none when generative is false`(@TempDir tempDir: File) {
        val configFile = writeSpecmaticConfig(tempDir, resiliency = ResiliencyTestSuite.all)
        val settings = ContractTestSettings(configFile = configFile.absolutePath, generative = false)
        assertThat(settings.getSpecmaticConfig().getResiliencyTestsEnabled()).isEqualTo(ResiliencyTestSuite.none)
    }

    @Test
    fun `getSpecmaticConfig should retain config resiliency config when generative is not provided`(@TempDir tempDir: File) {
        val configFile = writeSpecmaticConfig(tempDir, resiliency = ResiliencyTestSuite.all)
        val settings = ContractTestSettings(configFile = configFile.absolutePath, generative = null)
        assertThat(settings.getSpecmaticConfig().getResiliencyTestsEnabled()).isEqualTo(ResiliencyTestSuite.all)
    }

    @Test
    fun `copy constructor should not materialize config base url or test modes into fields`() {
        val config = SpecmaticConfigV1V2Common(version = VERSION_2, test = TestConfiguration(baseUrl = "http://config.example:9000", strictMode = true, lenientMode = true))
        val copied = ContractTestSettings(contractTestSettings = null, specmaticConfig = config)
        assertThat(copied.testBaseURL).isNull()
        assertThat(copied.strictMode).isNull()
        assertThat(copied.lenientMode).isNull()
    }

    private fun writeSpecmaticConfig(tempDir: File, baseUrl: String? = null, resiliency: ResiliencyTestSuite? = null): File {
        val configFile = tempDir.resolve("specmatic.yaml")
        val config = SpecmaticConfigV3(
            version = SpecmaticConfigVersion.VERSION_3,
            systemUnderTest = TestServiceConfig(
                service = RefOrValue.Value(
                    CommonServiceConfig(
                        definitions = emptyList(),
                        runOptions = RefOrValue.Value(TestRunOptions(openapi = OpenApiTestConfig(baseUrl = baseUrl))),
                        settings = RefOrValue.Value(TestSettings(schemaResiliencyTests = resiliency))
                    )
                )
            )
        )

        configFile.writeText(yamlMapper.writeValueAsString(config))
        return configFile
    }
}
