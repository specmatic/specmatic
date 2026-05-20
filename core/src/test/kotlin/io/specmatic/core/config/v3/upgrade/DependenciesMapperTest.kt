package io.specmatic.core.config.v3.upgrade

import io.specmatic.core.Source
import io.specmatic.core.SourceProvider
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.StubConfiguration
import io.specmatic.core.TemplatableValue
import io.specmatic.core.config.Switch
import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.components.runOptions.MockRunOptions
import io.specmatic.core.config.v3.components.settings.MockSettings
import io.specmatic.core.config.v3.components.services.CommonServiceConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DependenciesMapperTest {
    @Test
    fun `returns null when no stub specs are found`() {
        val view = LegacyConfigView.from(SpecmaticConfigV1V2Common())
        val result = DependenciesMapper().mapFrom(view, SourceMigrationBuilder(null))
        assertThat(result).isNull()
    }

    @Test
    fun `maps mock service settings data and openapi overrides`() {
        val source = Source(
            provider = SourceProvider.filesystem,
            directory = "./specs",
            stub = listOf(
                SpecExecutionConfig.ConfigValue(
                    specs = listOf("orders.yaml"),
                    specType = "OPENAPI",
                    config = mapOf("baseUrl" to "http://localhost:9000", "examples" to listOf("examples/orders"))
                )
            )
        )

        val view = LegacyConfigView.from(
            SpecmaticConfigV1V2Common(
                sources = listOf(source),
                hooks = mapOf("request-body" to "hooks/req.js"),
                stub = StubConfiguration(
                    hotReload = TemplatableValue(Switch.enabled),
                    dictionary = TemplatableValue("dict.json"),
                    filter = TemplatableValue("PATH='/orders'"),
                    baseUrl = TemplatableValue("http://global-mock:8080"),
                ),
            )
        )

        val result = DependenciesMapper().mapFrom(view, SourceMigrationBuilder(null))!!
        assertThat(result.services).hasSize(1)

        val dictionary = ((result.data?.dictionary) as RefOrValue.Value).value
        assertThat(dictionary.path).isEqualTo("dict.json")

        assertThat(result.settings).isNull()

        val service = ((result.services.single().service) as RefOrValue.Value<CommonServiceConfig<MockRunOptions, MockSettings>>).value
        val runOptions = (service.runOptions as RefOrValue.Value<MockRunOptions>).value
        assertThat(runOptions.openapi?.filter).isEqualTo(TemplatableValue("PATH='/orders'"))
        assertThat(runOptions.openapi?.baseUrl).isEqualTo(TemplatableValue("http://global-mock:8080"))
        assertThat(runOptions.openapi?.specs?.map { it.spec.id }).containsExactly("orders")
    }
}
