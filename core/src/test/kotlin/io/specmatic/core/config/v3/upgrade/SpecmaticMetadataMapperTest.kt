package io.specmatic.core.config.v3.upgrade

import io.specmatic.core.APICoverage
import io.specmatic.core.APICoverageConfiguration
import io.specmatic.core.ReportConfigurationDetails
import io.specmatic.core.ReportTypes
import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.ResiliencyTestsConfig
import io.specmatic.core.Source
import io.specmatic.core.SourceProvider
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.SuccessCriteria
import io.specmatic.core.TestConfiguration
import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.config.v3.ConcreteSettings
import io.specmatic.core.config.v3.RefOrValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SpecmaticMetadataMapperTest {
    @Test
    fun `maps test settings and picks resiliency from source when test config missing`() {
        val config = SpecmaticConfigV1V2Common(
            test = TestConfiguration(parallelism = "4", timeoutInMilliseconds = 9000, validateResponseValues = true),
            sources = listOf(
                Source(
                    provider = SourceProvider.filesystem,
                    test = listOf(
                        SpecExecutionConfig.ObjectValue.PartialUrl(
                            specs = listOf("payments.yaml"),
                            resiliencyTests = ResiliencyTestsConfig(ResiliencyTestSuite.positiveOnly)
                        )
                    )
                )
            ),
        )

        val metadata = SpecmaticMetadataMapper().mapFrom(config, LegacyConfigView.from(config))
        val settings = (metadata.settings as RefOrValue.Value<ConcreteSettings>).value

        assertThat(settings.test?.parallelism).isEqualTo("4")
        assertThat(settings.test?.validateResponseValues).isTrue()
        assertThat(settings.test?.timeoutInMilliseconds).isEqualTo(9000)
        assertThat(settings.test?.schemaResiliencyTests).isEqualTo(ResiliencyTestSuite.positiveOnly)
    }

    @Test
    fun `maps governance success criterion from report`() {
        val config = SpecmaticConfigV1V2Common(
            report = ReportConfigurationDetails(
                types = ReportTypes(
                    apiCoverage = APICoverage(
                        openAPI = APICoverageConfiguration(
                            successCriteria = SuccessCriteria(minThresholdPercentage = 70, maxMissedEndpointsInSpec = 2, enforce = true)
                        )
                    )
                )
            )
        )

        val metadata = SpecmaticMetadataMapper().mapFrom(config, LegacyConfigView.from(config))
        assertThat(metadata.governance?.successCriterion?.minCoveragePercentage).isEqualTo(70)
        assertThat(metadata.governance?.successCriterion?.maxMissedOperationsInSpec).isEqualTo(2)
        assertThat(metadata.governance?.successCriterion?.enforce).isTrue()
    }
}
