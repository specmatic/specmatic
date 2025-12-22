package io.specmatic.core.config.v3

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SpecExecutionConfigTest {

    @Nested
    inner class ContainsTests {

        @Nested
        inner class StringValueContainsTests {
            @Test
            fun `should return true when absolute spec path contains string value`() {
                val config = SpecExecutionConfig.StringValue("order.yaml")

                assertThat(config.contains("/path/to/order.yaml")).isTrue()
            }

            @Test
            fun `should return true when absolute spec path exactly matches string value`() {
                val config = SpecExecutionConfig.StringValue("io/specmatic/order.yaml")

                assertThat(config.contains("io/specmatic/order.yaml")).isTrue()
            }

            @Test
            fun `should return false when absolute spec path does not contain string value`() {
                val config = SpecExecutionConfig.StringValue("order.yaml")

                assertThat(config.contains("/path/to/payment.yaml")).isFalse()
            }

            @Test
            fun `should return true for partial matches in path`() {
                val config = SpecExecutionConfig.StringValue("order")

                assertThat(config.contains("/path/to/order.yaml")).isTrue()
            }
        }

        @Nested
        inner class ObjectValueContainsTests {
            @Test
            fun `FullUrl should return true when absolute spec path contains any spec`() {
                val config = SpecExecutionConfig.ObjectValue.FullUrl(
                    baseUrl = "http://localhost:8080",
                    specs = listOf("order.yaml", "payment.yaml")
                )

                assertThat(config.contains("/path/to/order.yaml")).isTrue()
                assertThat(config.contains("/path/to/payment.yaml")).isTrue()
            }

            @Test
            fun `FullUrl should return false when absolute spec path does not contain any spec`() {
                val config = SpecExecutionConfig.ObjectValue.FullUrl(
                    baseUrl = "http://localhost:8080",
                    specs = listOf("order.yaml", "payment.yaml")
                )

                assertThat(config.contains("/path/to/invoice.yaml")).isFalse()
            }

            @Test
            fun `PartialUrl should return true when absolute spec path contains any spec`() {
                val config = SpecExecutionConfig.ObjectValue.PartialUrl(
                    host = "localhost",
                    port = 8080,
                    specs = listOf("order.yaml", "payment.yaml")
                )

                assertThat(config.contains("/path/to/order.yaml")).isTrue()
            }

            @Test
            fun `PartialUrl should return false when absolute spec path does not contain any spec`() {
                val config = SpecExecutionConfig.ObjectValue.PartialUrl(
                    host = "localhost",
                    specs = listOf("order.yaml")
                )

                assertThat(config.contains("/path/to/invoice.yaml")).isFalse()
            }
        }

        @Nested
        inner class ConfigValueContainsTests {
            @Test
            fun `should return true when absolute spec path contains any spec`() {
                val config = SpecExecutionConfig.ConfigValue(
                    specs = listOf("order.yaml", "payment.yaml"),
                    specType = "ASYNCAPI",
                    config = mapOf("timeout" to 30)
                )

                assertThat(config.contains("/path/to/order.yaml")).isTrue()
                assertThat(config.contains("/path/to/payment.yaml")).isTrue()
            }

            @Test
            fun `should return false when absolute spec path does not contain any spec`() {
                val config = SpecExecutionConfig.ConfigValue(
                    specs = listOf("order.yaml"),
                    specType = "ASYNCAPI",
                    config = mapOf("timeout" to 30)
                )

                assertThat(config.contains("/path/to/invoice.yaml")).isFalse()
            }

            @Test
            fun `contains method with specType should return true for matching spec and type`() {
                val config = SpecExecutionConfig.ConfigValue(
                    specs = listOf("order.yaml", "payment.yaml"),
                    specType = "ASYNCAPI",
                    config = mapOf("timeout" to 30)
                )

                assertThat(config.contains("order.yaml", "ASYNCAPI")).isTrue()
                assertThat(config.contains("payment.yaml", "ASYNCAPI")).isTrue()
            }

            @Test
            fun `contains method with specType should return false for non-matching spec`() {
                val config = SpecExecutionConfig.ConfigValue(
                    specs = listOf("order.yaml"),
                    specType = "ASYNCAPI",
                    config = mapOf("timeout" to 30)
                )

                assertThat(config.contains("invoice.yaml", "ASYNCAPI")).isFalse()
            }

            @Test
            fun `contains method with specType should return false for non-matching type`() {
                val config = SpecExecutionConfig.ConfigValue(
                    specs = listOf("order.yaml"),
                    specType = "ASYNCAPI",
                    config = mapOf("timeout" to 30)
                )

                assertThat(config.contains("order.yaml", "OPENAPI")).isFalse()
            }

            @Test
            fun `contains method with specType should return false when both spec and type do not match`() {
                val config = SpecExecutionConfig.ConfigValue(
                    specs = listOf("order.yaml"),
                    specType = "ASYNCAPI",
                    config = mapOf("timeout" to 30)
                )

                assertThat(config.contains("invoice.yaml", "OPENAPI")).isFalse()
            }
        }
    }

    @Nested
    inner class SpecsTests {

        @Test
        fun `StringValue should return single spec in list`() {
            val config = SpecExecutionConfig.StringValue("order.yaml")

            assertThat(config.specs()).containsExactly("order.yaml")
        }

        @Test
        fun `FullUrl should return all specs`() {
            val config = SpecExecutionConfig.ObjectValue.FullUrl(
                baseUrl = "http://localhost:8080",
                specs = listOf("order.yaml", "payment.yaml", "invoice.yaml")
            )

            assertThat(config.specs()).containsExactly("order.yaml", "payment.yaml", "invoice.yaml")
        }

        @Test
        fun `FullUrl should return empty list when no specs`() {
            val config = SpecExecutionConfig.ObjectValue.FullUrl(
                baseUrl = "http://localhost:8080",
                specs = emptyList()
            )

            assertThat(config.specs()).isEmpty()
        }

        @Test
        fun `PartialUrl should return all specs`() {
            val config = SpecExecutionConfig.ObjectValue.PartialUrl(
                host = "localhost",
                port = 8080,
                specs = listOf("order.yaml", "payment.yaml")
            )

            assertThat(config.specs()).containsExactly("order.yaml", "payment.yaml")
        }

        @Test
        fun `PartialUrl with only host should return all specs`() {
            val config = SpecExecutionConfig.ObjectValue.PartialUrl(
                host = "localhost",
                specs = listOf("order.yaml")
            )

            assertThat(config.specs()).containsExactly("order.yaml")
        }

        @Test
        fun `PartialUrl with only port should return all specs`() {
            val config = SpecExecutionConfig.ObjectValue.PartialUrl(
                port = 8080,
                specs = listOf("order.yaml", "payment.yaml")
            )

            assertThat(config.specs()).containsExactly("order.yaml", "payment.yaml")
        }

        @Test
        fun `ConfigValue should return all specs`() {
            val config = SpecExecutionConfig.ConfigValue(
                specs = listOf("order.yaml", "payment.yaml", "invoice.yaml"),
                specType = "ASYNCAPI",
                config = mapOf("timeout" to 30)
            )

            assertThat(config.specs()).containsExactly("order.yaml", "payment.yaml", "invoice.yaml")
        }

        @Test
        fun `ConfigValue should return empty list when no specs`() {
            val config = SpecExecutionConfig.ConfigValue(
                specs = emptyList(),
                specType = "ASYNCAPI",
                config = mapOf("timeout" to 30)
            )

            assertThat(config.specs()).isEmpty()
        }
    }

    @Nested
    inner class SpecToBaseUrlPairListTests {

        @Nested
        inner class StringValueSpecToBaseUrlPairListTests {
            @Test
            fun `should return single pair with null base url`() {
                val config = SpecExecutionConfig.StringValue("order.yaml")

                val result = config.specToBaseUrlPairList(null)

                assertThat(result).containsExactly("order.yaml" to null)
            }

            @Test
            fun `should return single pair with null base url when default base url is provided`() {
                val config = SpecExecutionConfig.StringValue("order.yaml")

                val result = config.specToBaseUrlPairList("http://localhost:9000")

                assertThat(result).containsExactly("order.yaml" to null)
            }
        }

        @Nested
        inner class ObjectValueSpecToBaseUrlPairListTests {
            @Test
            fun `FullUrl should return pairs with full base url for each spec`() {
                val config = SpecExecutionConfig.ObjectValue.FullUrl(
                    baseUrl = "http://localhost:8080",
                    specs = listOf("order.yaml", "payment.yaml")
                )

                val result = config.specToBaseUrlPairList(null)

                assertThat(result).containsExactly(
                    "order.yaml" to "http://localhost:8080",
                    "payment.yaml" to "http://localhost:8080"
                )
            }

            @Test
            fun `FullUrl should ignore default base url and use its own base url`() {
                val config = SpecExecutionConfig.ObjectValue.FullUrl(
                    baseUrl = "http://localhost:8080",
                    specs = listOf("order.yaml")
                )

                val result = config.specToBaseUrlPairList("http://localhost:9000")

                assertThat(result).containsExactly("order.yaml" to "http://localhost:8080")
            }

            @Test
            fun `PartialUrl with host should use default base url scheme and port`() {
                val config = SpecExecutionConfig.ObjectValue.PartialUrl(
                    host = "127.0.0.1",
                    specs = listOf("order.yaml")
                )

                val result = config.specToBaseUrlPairList("http://localhost:9000")

                assertThat(result).containsExactly("order.yaml" to "http://127.0.0.1:9000")
            }

            @Test
            fun `PartialUrl with port should use default base url scheme and host`() {
                val config = SpecExecutionConfig.ObjectValue.PartialUrl(
                    port = 8080,
                    specs = listOf("order.yaml")
                )

                val result = config.specToBaseUrlPairList("http://localhost:9000")

                assertThat(result).containsExactly("order.yaml" to "http://localhost:8080")
            }

            @Test
            fun `PartialUrl with basePath should use default base url with new path`() {
                val config = SpecExecutionConfig.ObjectValue.PartialUrl(
                    basePath = "/api/v2",
                    specs = listOf("order.yaml")
                )

                val result = config.specToBaseUrlPairList("http://localhost:9000")

                assertThat(result).containsExactly("order.yaml" to "http://localhost:9000/api/v2")
            }

            @Test
            fun `PartialUrl with host and port should combine with default scheme`() {
                val config = SpecExecutionConfig.ObjectValue.PartialUrl(
                    host = "127.0.0.1",
                    port = 8080,
                    specs = listOf("order.yaml", "payment.yaml")
                )

                val result = config.specToBaseUrlPairList("http://localhost:9000")

                assertThat(result).containsExactly(
                    "order.yaml" to "http://127.0.0.1:8080",
                    "payment.yaml" to "http://127.0.0.1:8080"
                )
            }

            @Test
            fun `PartialUrl with all fields should override all default values`() {
                val config = SpecExecutionConfig.ObjectValue.PartialUrl(
                    host = "127.0.0.1",
                    port = 8080,
                    basePath = "/api/v2",
                    specs = listOf("order.yaml")
                )

                val result = config.specToBaseUrlPairList("http://localhost:9000/api/v1")

                assertThat(result).containsExactly("order.yaml" to "http://127.0.0.1:8080/api/v2")
            }

            @Test
            fun `FullUrl should return multiple pairs for multiple specs`() {
                val config = SpecExecutionConfig.ObjectValue.FullUrl(
                    baseUrl = "http://localhost:8080",
                    specs = listOf("order.yaml", "payment.yaml", "invoice.yaml")
                )

                val result = config.specToBaseUrlPairList(null)

                assertThat(result).hasSize(3)
                assertThat(result).containsExactly(
                    "order.yaml" to "http://localhost:8080",
                    "payment.yaml" to "http://localhost:8080",
                    "invoice.yaml" to "http://localhost:8080"
                )
            }

            @Test
            fun `PartialUrl should return empty list when no specs`() {
                val config = SpecExecutionConfig.ObjectValue.PartialUrl(
                    host = "localhost",
                    specs = emptyList()
                )

                val result = config.specToBaseUrlPairList("http://localhost:9000")

                assertThat(result).isEmpty()
            }

            @Test
            fun `PartialUrl with basePath should handle base url with existing path`() {
                val config = SpecExecutionConfig.ObjectValue.PartialUrl(
                    basePath = "/api/v2",
                    specs = listOf("order.yaml")
                )

                val result = config.specToBaseUrlPairList("http://localhost:8080/api")

                assertThat(result).containsExactly("order.yaml" to "http://localhost:8080/api/v2")
            }
        }

        @Nested
        inner class ConfigValueSpecToBaseUrlPairListTests {
            @Test
            fun `should return pairs with null base url for each spec`() {
                val config = SpecExecutionConfig.ConfigValue(
                    specs = listOf("order.yaml", "payment.yaml"),
                    specType = "ASYNCAPI",
                    config = mapOf("timeout" to 30)
                )

                val result = config.specToBaseUrlPairList(null)

                assertThat(result).containsExactly(
                    "order.yaml" to null,
                    "payment.yaml" to null
                )
            }

            @Test
            fun `should return pairs with null base url even when default base url is provided`() {
                val config = SpecExecutionConfig.ConfigValue(
                    specs = listOf("order.yaml", "payment.yaml"),
                    specType = "ASYNCAPI",
                    config = mapOf("timeout" to 30)
                )

                val result = config.specToBaseUrlPairList("http://localhost:9000")

                assertThat(result).containsExactly(
                    "order.yaml" to null,
                    "payment.yaml" to null
                )
            }

            @Test
            fun `should return empty list when no specs`() {
                val config = SpecExecutionConfig.ConfigValue(
                    specs = emptyList(),
                    specType = "ASYNCAPI",
                    config = mapOf("timeout" to 30)
                )

                val result = config.specToBaseUrlPairList(null)

                assertThat(result).isEmpty()
            }

            @Test
            fun `should return multiple pairs for multiple specs with null base urls`() {
                val config = SpecExecutionConfig.ConfigValue(
                    specs = listOf("order.yaml", "payment.yaml", "invoice.yaml"),
                    specType = "ASYNCAPI",
                    config = mapOf("timeout" to 30, "retries" to 3)
                )

                val result = config.specToBaseUrlPairList("http://localhost:8080")

                assertThat(result).hasSize(3)
                assertThat(result).containsExactly(
                    "order.yaml" to null,
                    "payment.yaml" to null,
                    "invoice.yaml" to null
                )
            }
        }
    }
}