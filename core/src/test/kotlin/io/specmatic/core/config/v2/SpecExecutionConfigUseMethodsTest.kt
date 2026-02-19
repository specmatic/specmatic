package io.specmatic.core.config.v2

import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.ResiliencyTestsConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SpecExecutionConfigUseMethodsTest {
    private val resiliencyTestsConfig = ResiliencyTestsConfig(enable = ResiliencyTestSuite.all)

    @Test
    fun `StringValue use with baseUrl should return FullUrl`() {
        val config = SpecExecutionConfig.StringValue("order.yaml")
        val result = config.use("http://localhost:9000", resiliencyTestsConfig)
        assertThat(result).isEqualTo(
            SpecExecutionConfig.ObjectValue.FullUrl(
                baseUrl = "http://localhost:9000",
                specs = listOf("order.yaml"),
                resiliencyTests = resiliencyTestsConfig
            )
        )
    }

    @Test
    fun `StringValue use with port should return PartialUrl`() {
        val config = SpecExecutionConfig.StringValue("order.yaml")
        val result = config.use(9000)
        assertThat(result).isEqualTo(
            SpecExecutionConfig.ObjectValue.PartialUrl(
                port = 9000,
                specs = listOf("order.yaml")
            )
        )
    }

    @Test
    fun `FullUrl use with baseUrl should update baseUrl and resiliency config`() {
        val config = SpecExecutionConfig.ObjectValue.FullUrl(
            baseUrl = "http://localhost:8080",
            specs = listOf("order.yaml"),
            resiliencyTests = ResiliencyTestsConfig(enable = ResiliencyTestSuite.positiveOnly)
        )

        val result = config.use("http://localhost:9000", resiliencyTestsConfig)
        assertThat(result).isEqualTo(
            SpecExecutionConfig.ObjectValue.FullUrl(
                baseUrl = "http://localhost:9000",
                specs = listOf("order.yaml"),
                resiliencyTests = resiliencyTestsConfig
            )
        )
    }

    @Test
    fun `FullUrl use with port should return PartialUrl preserving resiliency config`() {
        val config = SpecExecutionConfig.ObjectValue.FullUrl(
            baseUrl = "http://localhost:8080",
            specs = listOf("order.yaml"),
            resiliencyTests = resiliencyTestsConfig
        )

        val result = config.use(9000)
        assertThat(result).isEqualTo(
            SpecExecutionConfig.ObjectValue.PartialUrl(
                port = 9000,
                specs = listOf("order.yaml"),
                resiliencyTests = resiliencyTestsConfig
            )
        )
    }

    @Test
    fun `PartialUrl use with baseUrl should return FullUrl`() {
        val config = SpecExecutionConfig.ObjectValue.PartialUrl(
            host = "localhost",
            port = 8080,
            specs = listOf("order.yaml")
        )

        val result = config.use("http://localhost:9000", resiliencyTestsConfig)
        assertThat(result).isEqualTo(
            SpecExecutionConfig.ObjectValue.FullUrl(
                baseUrl = "http://localhost:9000",
                specs = listOf("order.yaml"),
                resiliencyTests = resiliencyTestsConfig
            )
        )
    }

    @Test
    fun `PartialUrl use with port should update only port`() {
        val config = SpecExecutionConfig.ObjectValue.PartialUrl(
            host = "localhost",
            port = 8080,
            basePath = "/api",
            specs = listOf("order.yaml"),
            resiliencyTests = resiliencyTestsConfig
        )

        val result = config.use(9000)
        assertThat(result).isEqualTo(
            SpecExecutionConfig.ObjectValue.PartialUrl(
                host = "localhost",
                port = 9000,
                basePath = "/api",
                specs = listOf("order.yaml"),
                resiliencyTests = resiliencyTestsConfig
            )
        )
    }

    @Test
    fun `ConfigValue use with baseUrl should return same config`() {
        val config = SpecExecutionConfig.ConfigValue(specs = listOf("order.yaml"), specType = "ASYNCAPI", config = mapOf("timeout" to 30))
        val result = config.use("http://localhost:9000", resiliencyTestsConfig)
        assertThat(result).isSameAs(config)
    }

    @Test
    fun `ConfigValue use with port should return same config`() {
        val config = SpecExecutionConfig.ConfigValue(specs = listOf("order.yaml"), specType = "ASYNCAPI", config = mapOf("timeout" to 30))
        val result = config.use(9000)
        assertThat(result).isSameAs(config)
    }
}
