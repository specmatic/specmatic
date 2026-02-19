package io.specmatic.core.config

import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.ResiliencyTestsConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenAPIConfigUpdateTest {
    @Test
    fun `updateWithBaseUrl should set base url in openapi test config`() {
        val updated = OpenAPITestConfig.updateWithBaseUrl(
            data = emptyMap(),
            baseUrl = "http://localhost:9000",
            resiliencyTestsConfig = ResiliencyTestsConfig(enable = ResiliencyTestSuite.all)
        )

        val config = OpenAPITestConfig.from(updated)
        assertThat(config.baseUrl).isEqualTo("http://localhost:9000")
        assertThat(config.resiliencyTests).isEqualTo(ResiliencyTestsConfig(enable = ResiliencyTestSuite.all))
    }

    @Test
    fun `updateWithPort should set base url in openapi mock config`() {
        val updated = OpenAPIMockConfig.updateWithPort(data = emptyMap(), port = 9000)
        val config = OpenAPIMockConfig.from(updated)
        assertThat(config.baseUrl).isEqualTo("http//0.0.0.0:9000")
    }
}
