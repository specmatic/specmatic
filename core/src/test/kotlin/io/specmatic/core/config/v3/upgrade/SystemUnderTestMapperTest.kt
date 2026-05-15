package io.specmatic.core.config.v3.upgrade

import io.specmatic.core.APIKeySecuritySchemeConfiguration
import io.specmatic.core.OpenAPISecurityConfiguration
import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.ResiliencyTestsConfig
import io.specmatic.core.SecurityConfiguration
import io.specmatic.core.Source
import io.specmatic.core.SourceProvider
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.TestConfiguration
import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.components.runOptions.TestRunOptions
import io.specmatic.core.config.v3.components.services.CommonServiceConfig
import io.specmatic.core.config.v3.components.services.Definition
import io.specmatic.core.config.v3.components.services.SpecificationDefinition
import io.specmatic.core.config.v3.components.settings.TestSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SystemUnderTestMapperTest {
    @Test
    fun `maps openapi run options with global overlay and security`() {
        val source = Source(
            provider = SourceProvider.filesystem,
            directory = "./specs",
            test = listOf(
                SpecExecutionConfig.ObjectValue.PartialUrl(
                    host = "localhost",
                    port = 8080,
                    specs = listOf("users.yaml"),
                    resiliencyTests = ResiliencyTestsConfig(ResiliencyTestSuite.all)
                )
            ),
        )

        val config = SpecmaticConfigV1V2Common(
            sources = listOf(source),
            test = TestConfiguration(baseUrl = "http://localhost:8080", overlayFilePath = "overlay.yaml", filter = "PATH='/users'"),
            security = SecurityConfiguration(OpenAPI = OpenAPISecurityConfiguration(securitySchemes = mapOf("api_key" to APIKeySecuritySchemeConfiguration(value = "secret")))),
            examples = listOf("examples/global"),
        )

        val serviceConfig = SystemUnderTestMapper().mapFrom(LegacyConfigView.from(config), SourceMigrationBuilder(null))!!
        val service = (serviceConfig.service as RefOrValue.Value<CommonServiceConfig<TestRunOptions, *>>).value
        val runOptions = (service.runOptions as RefOrValue.Value<TestRunOptions>).value

        assertThat(service.definitions).hasSize(1)
        assertThat(runOptions.openapi?.filter).isEqualTo("PATH='/users'")
        assertThat(runOptions.openapi?.baseUrl).isEqualTo("http://localhost:8080")
        assertThat(runOptions.openapi?.specs?.single()?.spec?.overlayFilePath).isEqualTo("overlay.yaml")
        assertThat(runOptions.openapi?.specs?.single()?.spec?.securitySchemes).containsKey("api_key")

        val settings = (service.settings as RefOrValue.Value<TestSettings>).value
        assertThat(settings.schemaResiliencyTests).isEqualTo(ResiliencyTestSuite.all)
    }

    @Test
    fun `drops specification id when no override but keeps id when overridden`() {
        val definition = Definition(
            definition = Definition.Value(
                source = RefOrValue.Value(io.specmatic.core.config.v3.components.sources.SourceV3(fileSystem = io.specmatic.core.config.v3.components.sources.SourceV3.FileSystem(), git = null, web = null)),
                specs = listOf(
                    SpecificationDefinition.ObjectValue(SpecificationDefinition.Specification(id = "a.yaml", path = "a.yaml")),
                    SpecificationDefinition.ObjectValue(SpecificationDefinition.Specification(id = "b.yaml", path = "b.yaml")),
                )
            )
        )

        val transformed = definition.dropSpecificationIdsWithoutOverrides { it == "b.yaml" }
        val specs = transformed.definition.specs

        val firstSpec = specs[0] as SpecificationDefinition.StringValue
        val secondSpec = specs[1] as SpecificationDefinition.ObjectValue
        assertThat(firstSpec.specification).isEqualTo("a.yaml")
        assertThat(secondSpec.spec.id).isEqualTo("b.yaml")
    }
}
