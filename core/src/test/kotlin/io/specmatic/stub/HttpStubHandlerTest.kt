package io.specmatic.stub

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.config.MockMode
import io.specmatic.core.config.toSpecmaticConfig
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedJSONArray
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.value.StringValue
import io.specmatic.mock.ScenarioStub
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.net.ServerSocket
import java.net.URL
import java.util.Collections
import java.util.ServiceLoader
import java.util.stream.Stream

class HttpStubHandlerTest {
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class MockModeResolution {
        @TempDir
        lateinit var tempDir: File

        @ParameterizedTest(name = "type={0} resolves to {1}")
        @MethodSource("mockModes")
        fun `should resolve the mock mode from the OpenAPI mock type`(type: String?, expectedMode: MockMode) {
            val specFile = resourceFile("config/mock_mode_api.yaml")
            val openApiRunOptions = type?.let { "type: $it" } ?: "{}"

            val configFile = tempDir.resolve("specmatic.yaml").apply {
                writeText("""
                version: 3
                dependencies:
                  services:
                    - service:
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ${specFile.parentFile.canonicalPath}
                              specs:
                                - ${specFile.name}
                        runOptions:
                          openapi:
                            $openApiRunOptions
                """.trimIndent())
            }

            assertThat(configFile.toSpecmaticConfig().getMockMode(specFile)).isEqualTo(expectedMode)
        }

        @Test
        fun `should default non-v3 configurations to ordinary mocking`() {
            assertThat(SpecmaticConfigV1V2Common().getMockMode(File("api.yaml")))
                .isEqualTo(MockMode.MOCK)
        }

        fun mockModes(): Stream<Arguments> = Stream.of(
            Arguments.of(null, MockMode.MOCK),
            Arguments.of("mock", MockMode.MOCK),
            Arguments.of("stateful-mock", MockMode.STATEFUL_MOCK),
        )
    }

    @Nested
    inner class ServiceLoaderIntegration {
        @Test
        fun `should discover the core test handler factory through ServiceLoader`() {
            val factories = ServiceLoader.load(HttpStubHandlerFactory::class.java).map { it::class.java }.toList()
            assertThat(factories).containsExactly(HttpStubTestHandlerFactory::class.java)
        }

        @Test
        fun `should create a stateful handler`() {
            val factory = ServiceLoader.load(HttpStubHandlerFactory::class.java).single()
            assertThat(factory.stateful(emptyContext())).isNotNull()
        }

        @Test
        fun `should delegate a stateful request to the ServiceLoader handler`() {
            val feature = handlerFeature()
            HttpStub(
                port = availablePort(),
                features = listOf(feature),
                specmaticConfigSource = SpecmaticConfigSource.fromConfigObject(configWithMode { MockMode.STATEFUL_MOCK }),
            ).use { stub ->
                val response = stub.client.execute(HttpRequest(method = "GET", path = "/spi"))
                assertThat(response.status).isEqualTo(200)
                assertThat(response.body).isEqualTo(StringValue("served by SPI"))
                assertThat(response.getHeader("X-Test-SPI")).isEqualTo("true")
                assertThat(response.getHeader("X-Test-SPI-Features")).isEqualTo("1")
                assertThat(response.getHeader("X-Test-SPI-Raw-Stubs")).isEqualTo("0")
                assertThat(stub.stubCount).isZero()
                assertThat(stub.transientStubCount).isZero()
            }
        }
    }

    @Nested
    inner class Fallback {
        @Test
        fun `should use the default handler when the provider declines ordinary mock mode`() {
            val feature = handlerFeature()
            val expectedResponse = HttpResponse(status = 200, body = StringValue("hello"))
            val scenarioStub = ScenarioStub(request = HttpRequest(method = "GET", path = "/hello"), response = expectedResponse)

            HttpStub(
                port = availablePort(),
                features = listOf(feature),
                rawHttpStubs = contractInfoToHttpExpectations(listOf(feature to listOf(scenarioStub))),
                specmaticConfigSource = SpecmaticConfigSource.fromConfigObject(configWithMode { MockMode.MOCK }),
            ).use { stub ->
                val response = stub.client.execute(scenarioStub.request)
                assertThat(response.status).isEqualTo(expectedResponse.status)
                assertThat(response.body).isEqualTo(expectedResponse.body)
                assertThat(stub.stubCount).isEqualTo(2)
                assertThat(stub.transientStubCount).isZero()
            }
        }

        @Test
        fun `should fail when no stateful mock provider is available`() {
            val feature = handlerFeature()
            val expectedResponse = HttpResponse(status = 200, body = StringValue("hello"))
            val scenarioStub = ScenarioStub(request = HttpRequest(method = "GET", path = "/hello"), response = expectedResponse)

            assertThatThrownBy {
                withoutHttpStubHandlerServices {
                    HttpStub(
                        port = availablePort(),
                        features = listOf(feature),
                        rawHttpStubs = contractInfoToHttpExpectations(listOf(feature to listOf(scenarioStub))),
                        specmaticConfigSource = SpecmaticConfigSource.fromConfigObject(configWithMode { MockMode.STATEFUL_MOCK }),
                    ).use { }
                }
            }.isInstanceOf(ContractException::class.java)
                .hasMessage("Stateful mocking is not supported in Specmatic Open Source")
        }
    }

    @Nested
    inner class DefaultHandlerBehavior {
        @Test
        fun `should preserve inline example matching after extracting the default handler`() {
            HttpStub(features = listOf(handlerFeature()), port = availablePort()).use { stub ->
                val response = stub.client.execute(
                    request = HttpRequest(
                        method = "POST",
                        path = "/example",
                        headers = mapOf("Content-Type" to "application/json"),
                        body = parsedJSONObject("""{"id":1}"""),
                    )
                )

                assertThat(response.status).isEqualTo(200)
                assertThat(response.body).isEqualTo(StringValue("served from OpenAPI example"))
            }
        }

        @Test
        fun `should preserve transient precedence and flushing after extracting the default handler`() {
            val feature = handlerFeature()
            val staticStub = ScenarioStub(
                request = HttpRequest(method = "GET", path = "/hello"),
                response = HttpResponse(status = 200, body = StringValue("static")),
            )

            val transientStub = staticStub.copy(
                stubToken = "transient-token",
                response = HttpResponse(status = 200, body = StringValue("transient")),
            )

            HttpStub(
                port = availablePort(),
                features = listOf(feature),
                rawHttpStubs = contractInfoToHttpExpectations(listOf(feature to listOf(staticStub))),
            ).use { stub ->
                stub.setExpectation(transientStub)
                assertThat(stub.stubCount).isEqualTo(2)
                assertThat(stub.transientStubCount).isEqualTo(1)
                assertThat(stub.client.execute(transientStub.request).body)
                    .isEqualTo(StringValue("transient"))

                val flushResponse = stub.client.execute(HttpRequest(method = "DELETE", path = "/_specmatic/http-stub/transient-token"))
                assertThat(flushResponse.status).isEqualTo(200)
                assertThat(stub.transientStubCount).isZero()
                assertThat(stub.client.execute(staticStub.request).body)
                    .isEqualTo(StringValue("static"))
            }
        }

        @Test
        fun `should preserve persistent dynamic precedence after extracting the default handler`() {
            val feature = handlerFeature()
            val staticStub = ScenarioStub(
                request = HttpRequest(method = "GET", path = "/hello"),
                response = HttpResponse(status = 200, body = StringValue("static")),
            )

            HttpStub(
                port = availablePort(),
                features = listOf(feature),
                rawHttpStubs = contractInfoToHttpExpectations(listOf(feature to listOf(staticStub))),
            ).use { stub ->
                val dynamicStub = staticStub.copy(response = HttpResponse(status = 200, body = StringValue("dynamic")))
                stub.setExpectation(dynamicStub)

                assertThat(stub.stubCount).isEqualTo(2)
                assertThat(stub.transientStubCount).isZero()
                assertThat(stub.client.execute(dynamicStub.request).body)
                    .isEqualTo(StringValue("dynamic"))

                stub.client.execute(HttpRequest(method = "DELETE", path = "/_specmatic/http-stub/any-token"))
                assertThat(stub.client.execute(staticStub.request).body)
                    .isEqualTo(StringValue("dynamic"))
            }
        }
    }

    @Nested
    inner class DynamicExpectationIntegration {
        @Test
        fun `should route dynamic expectations to the selected ServiceLoader handler`() {
            val feature = handlerFeature()
            val expectation = ScenarioStub(
                stubToken = "persistent-token",
                request = HttpRequest(method = "GET", path = "/dynamic"),
                response = HttpResponse(status = 201, body = StringValue("dynamic response")),
            )

            HttpStub(
                port = availablePort(),
                features = listOf(feature),
                specmaticConfigSource = SpecmaticConfigSource.fromConfigObject(configWithMode { MockMode.STATEFUL_MOCK }),
            ).use { stub ->
                stub.setExpectation(expectation)
                assertThat(stub.stubCount).isZero()
                assertThat(stub.transientStubCount).isZero()

                val responseBeforeFlush = stub.client.execute(expectation.request)
                assertThat(responseBeforeFlush.status).isEqualTo(expectation.response.status)
                assertThat(responseBeforeFlush.body).isEqualTo(expectation.response.body)

                val flushResponse = stub.client.execute(HttpRequest(method = "DELETE", path = "/_specmatic/http-stub/persistent-token"))
                assertThat(flushResponse.status).isEqualTo(200)
                assertThat(stub.transientStubCount).isZero()

                val responseAfterFlush = stub.client.execute(expectation.request)
                assertThat(responseAfterFlush.status).isEqualTo(expectation.response.status)
                assertThat(responseAfterFlush.body).isEqualTo(expectation.response.body)
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class ModeRouting {
        @ParameterizedTest(name = "stateful feature first={0}")
        @MethodSource("featureOrderings")
        fun `should use feature order to select the mode handler`(statefulFeatureFirst: Boolean) {
            val fixtures = sharedFixtures()
            val orderedFeatures = if (statefulFeatureFirst) {
                listOf(fixtures.statefulFeature, fixtures.ordinaryFeature)
            } else {
                listOf(fixtures.ordinaryFeature, fixtures.statefulFeature)
            }

            HttpStub(
                port = availablePort(),
                features = orderedFeatures,
                rawHttpStubs = fixtures.rawHttpStubs,
                specmaticConfigSource = SpecmaticConfigSource.fromConfigObject(fixtures.config),
            ).use { stub ->
                val response = stub.client.execute(HttpRequest(method = "GET", path = "/shared"))
                assertThat(response.status).isEqualTo(200)
                assertThat(response.body).isEqualTo(
                    if (statefulFeatureFirst) StringValue("served by SPI") else StringValue("ordinary")
                )

                assertThat(response.getHeader("X-Test-SPI")).isEqualTo(
                    if (statefulFeatureFirst) "true" else null
                )

                assertThat(response.getHeader("X-Test-SPI-Features")).isEqualTo(
                    if (statefulFeatureFirst) "1" else null
                )

                assertThat(response.getHeader("X-Test-SPI-Raw-Stubs")).isEqualTo(
                    if (statefulFeatureFirst) "1" else null
                )

                assertThat(stub.stubCount).isEqualTo(1)
                assertThat(stub.transientStubCount).isZero()
            }
        }

        fun featureOrderings(): Stream<Arguments> = Stream.of(
            Arguments.of(true),
            Arguments.of(false),
        )

        private fun sharedFixtures(): SharedFixtures {
            val statefulSpec = resourceFile("http_stub_handler/shared_stateful.yaml")
            val ordinarySpec = resourceFile("http_stub_handler/shared_ordinary.yaml")
            val statefulFeature = openApiFeature(statefulSpec)
            val ordinaryFeature = openApiFeature(ordinarySpec)

            val statefulStub = ScenarioStub(
                request = HttpRequest(method = "GET", path = "/shared"),
                response = HttpResponse(status = 200, body = parsedJSONArray("""[{"id":1}]""")),
            )

            val ordinaryStub = ScenarioStub(
                request = HttpRequest(method = "GET", path = "/shared"),
                response = HttpResponse(status = 200, body = StringValue("ordinary")),
            )

            val config = object : SpecmaticConfig by SpecmaticConfigV1V2Common() {
                override fun getMockMode(specFile: File): MockMode = when (specFile.canonicalFile) {
                    ordinarySpec.canonicalFile -> MockMode.MOCK
                    statefulSpec.canonicalFile -> MockMode.STATEFUL_MOCK
                    else -> MockMode.MOCK
                }
            }

            return SharedFixtures(
                config = config,
                statefulFeature = statefulFeature,
                ordinaryFeature = ordinaryFeature,
                rawHttpStubs = contractInfoToHttpExpectations(
                    listOf(
                        statefulFeature to listOf(statefulStub),
                        ordinaryFeature to listOf(ordinaryStub)
                    )
                ),
            )
        }
    }

    @Nested
    inner class InvalidRequestRouting {
        @ParameterizedTest(name = "invalid response status={0}, storage={1}")
        @CsvSource("405,static", "415,static", "405,dynamic", "415,dynamic")
        fun `should route invalid request examples to their owning ordinary feature`(status: Int, storage: String) {
            val statefulSpec = resourceFile("http_stub_handler/invalid_stateful.yaml")
            val ordinarySpec = resourceFile("http_stub_handler/invalid_ordinary.yaml")
            val statefulFeature = openApiFeature(statefulSpec)
            val ordinaryFeature = openApiFeature(ordinarySpec)

            val expectedBody = if (status == 405) {
                parsedJSONObject("""{"error":"method not allowed"}""")
            } else {
                parsedJSONObject("""{"error":"unsupported media type"}""")
            }

            val scenarioStub = if (status == 405) {
                ScenarioStub(
                    request = HttpRequest(
                        method = "PATCH",
                        path = "/orders",
                        body = parsedJSONObject("""{"data":"found"}"""),
                        headers = mapOf("Content-Type" to "application/json"),
                    ),
                    response = HttpResponse(status = status, body = expectedBody),
                )
            } else {
                ScenarioStub(
                    request = HttpRequest(
                        method = "POST",
                        path = "/orders",
                        body = StringValue("request sent here"),
                        headers = mapOf("Content-Type" to "text/plain"),
                    ),
                    response = HttpResponse(status = status, body = expectedBody),
                )
            }

            val config = object : SpecmaticConfig by SpecmaticConfigV1V2Common() {
                override fun getMockMode(specFile: File): MockMode {
                    return if (specFile.canonicalFile == statefulSpec.canonicalFile) {
                        MockMode.STATEFUL_MOCK
                    } else {
                        MockMode.MOCK
                    }
                }
            }

            HttpStub(
                port = availablePort(),
                features = listOf(statefulFeature, ordinaryFeature),
                specmaticConfigSource = SpecmaticConfigSource.fromConfigObject(config),
                rawHttpStubs = if (storage == "static") contractInfoToHttpExpectations(listOf(ordinaryFeature to listOf(scenarioStub))) else emptyList(),
            ).use { stub ->
                if (storage != "static") {
                    stub.setExpectation(scenarioStub.copy(stubToken = if (storage in setOf("transient", "flushed")) "token" else null))
                }

                val response = stub.client.execute(scenarioStub.request)
                assertThat(response.status).isEqualTo(status)
                assertThat(response.body).isEqualTo(expectedBody)
                assertThat(response.getHeader("X-Test-SPI")).isNull()
                assertThat(stub.stubCount).isEqualTo(if (storage == "static") 1 else 0)
                assertThat(stub.transientStubCount).isZero()
            }
        }
    }

    private fun handlerFeature(): Feature {
        return openApiFeature(resourceFile("http_stub_handler/handler.yaml"))
    }

    private fun openApiFeature(specFile: File): Feature {
        return OpenApiSpecification.fromFile(specFile.canonicalPath).toFeature()
    }

    private fun resourceFile(path: String): File {
        return File("src/test/resources/$path").canonicalFile
    }

    private fun configWithMode(mode: (File) -> MockMode): SpecmaticConfig {
        return object : SpecmaticConfig by SpecmaticConfigV1V2Common() {
            override fun getMockMode(specFile: File): MockMode = mode(specFile)
        }
    }

    private fun emptyContext(): HttpStubHandlerContext {
        return HttpStubHandlerContext(
            strictMode = false,
            features = emptyList(),
            passThroughTargetBase = "",
            rawHttpStubs = emptyList(),
            specToBaseUrlMap = emptyMap(),
            httpClientFactory = HttpClientFactory(),
            specmaticConfigSource = SpecmaticConfigSource.None,
        )
    }

    private fun withoutHttpStubHandlerServices(block: () -> Unit) {
        val currentThread = Thread.currentThread()
        val originalClassLoader = currentThread.contextClassLoader
        val serviceName = "META-INF/services/${HttpStubHandlerFactory::class.java.name}"
        currentThread.contextClassLoader = object : ClassLoader(originalClassLoader) {
            override fun getResources(name: String): java.util.Enumeration<URL> {
                return if (name == serviceName) {
                    Collections.emptyEnumeration()
                } else {
                    super.getResources(name)
                }
            }
        }

        try {
            block()
        } finally {
            currentThread.contextClassLoader = originalClassLoader
        }
    }

    private fun availablePort(): Int = ServerSocket(0).use { it.localPort }

    private data class SharedFixtures(
        val config: SpecmaticConfig,
        val statefulFeature: Feature,
        val ordinaryFeature: Feature,
        val rawHttpStubs: List<HttpStubData>,
    )
}
