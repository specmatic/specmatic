package io.specmatic.core.config.v3.upgrade

import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.config.v3.components.SecuritySchemeConfigurationV3
import io.specmatic.core.config.v3.components.SecuritySchemeType
import io.specmatic.reporter.model.SpecType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RunOptionsMapperTest {
    @Test
    fun `openapi baseUrl overrides host port and keeps global overlay security`() {
        val specTypes = linkedMapOf("petstore.yaml" to SpecType.OPENAPI)
        val mapper = RunOptionsMapper(defaultHostForPortOnly = "localhost")
            .mergeConfig(
                specTypesByPath = specTypes,
                config = SpecExecutionConfig.ConfigValue(
                    specs = listOf("petstore.yaml"),
                    specType = "OPENAPI",
                    config = mapOf("baseUrl" to "http://example.com:9001")
                ),
            )
            .mergeGlobalOpenApi(
                overlayFilePath = "overlay.yaml",
                securitySchemes = mapOf("oauth" to SecuritySchemeConfigurationV3(SecuritySchemeType.OAUTH2, "token")),
                specTypesByPath = specTypes,
            )

        val openApi = mapper.openApi["petstore.yaml"]!!
        assertThat(openApi.securitySchemes).containsKey("oauth")
        assertThat(openApi.overlayFilePath).isEqualTo("overlay.yaml")
        assertThat(openApi.baseUrl).isEqualTo("http://example.com:9001")
        assertThat(openApi.host).isNull()
        assertThat(openApi.port).isNull()
    }

    @Test
    fun `openapi host and port mapped when baseUrl is missing`() {
        val specTypes = linkedMapOf("inventory.yaml" to SpecType.OPENAPI)
        val mapper = RunOptionsMapper().mergeConfig(
            specTypesByPath = specTypes,
            config = SpecExecutionConfig.ConfigValue(
                specs = listOf("inventory.yaml"),
                specType = "openapi",
                config = mapOf("port" to 9090, "host" to "svc.internal")
            ),
        )

        val openApi = mapper.openApi["inventory.yaml"]!!
        assertThat(openApi.baseUrl).isNull()
        assertThat(openApi.port).isEqualTo(9090)
        assertThat(openApi.host).isEqualTo("svc.internal")
    }
}
