package io.specmatic.proxy

import io.ktor.http.*
import io.specmatic.Waiter
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.YAML
import io.specmatic.core.parseGherkinStringToFeature
import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.stub.HttpStub
import io.ktor.util.*
import io.specmatic.core.pattern.QueryParameterScalarPattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.mock.DELAY_IN_MILLISECONDS
import io.specmatic.stub.SPECMATIC_RESPONSE_CODE_HEADER
import io.specmatic.test.HttpClient
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.postForEntity
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

internal class ProxyTest {
    private val dynamicHttpHeaders =
        listOf(
            HttpHeaders.Authorization,
            HttpHeaders.UserAgent,
            HttpHeaders.Referrer,
            HttpHeaders.AcceptLanguage,
            HttpHeaders.Host,
            HttpHeaders.IfModifiedSince,
            HttpHeaders.IfNoneMatch,
            HttpHeaders.CacheControl,
            HttpHeaders.ContentLength,
            HttpHeaders.Range,
            HttpHeaders.XForwardedFor,
        )

    private val simpleFeature =
        parseGherkinStringToFeature(
            """
            Feature: Math
              Scenario: Square
                When POST /
                And request-body (number)
                Then status 200
                And response-body 100
            
              Scenario: Square
                When POST /multiply
                And request-body (number)
                Then status 200
                And response-body 100
                
              Scenario: Random
                When GET /
                Then status 200
                And response-body (number)
            """.trimIndent(),
        )

    private var fakeFileWriter: FakeFileWriter = FakeFileWriter()
    private val generatedContracts = File("./build/generatedContracts")

    @BeforeEach
    fun setup() {
        if (generatedContracts.exists()) {
            generatedContracts.deleteRecursively()
        }
        generatedContracts.mkdirs()
    }

    @Test
    fun `basic test of the proxy`() {
        HttpStub(simpleFeature).use {
            Proxy(host = "localhost", port = 9001, "http://localhost:9000", fakeFileWriter).use {
                val restProxy = java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress("localhost", 9001))
                val requestFactory = SimpleClientHttpRequestFactory()
                requestFactory.setProxy(restProxy)
                val client = RestTemplate(requestFactory)
                val response = client.postForEntity("http://localhost:9000/", "10", String::class.java)

                assertThat(response.statusCode.value()).isEqualTo(200)
                assertThatNoException().isThrownBy { response.body!!.toInt() }
            }
        }

        assertThat(fakeFileWriter.receivedContract?.trim()).startsWith("openapi:")
        assertThatCode {
            OpenApiSpecification.fromYAML(
                fakeFileWriter.receivedContract!!,
                "",
            )
        }.doesNotThrowAnyException()
        assertThatCode { parsedJSON(fakeFileWriter.receivedStub ?: "") }.doesNotThrowAnyException()
        assertThat(fakeFileWriter.receivedPaths.toList()).containsExactlyInAnyOrder(
            "proxy_generated.yaml",
            "POST_200_1.json",
        )
    }

    @Test
    fun `basic test of the proxy with a request containing a path variable`() {
        HttpStub(simpleFeature).use {
            Proxy(host = "localhost", port = 9001, "http://localhost:9000", fakeFileWriter).use {
                val restProxy = java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress("localhost", 9001))
                val requestFactory = SimpleClientHttpRequestFactory()
                requestFactory.setProxy(restProxy)
                val client = RestTemplate(requestFactory)
                val response = client.postForEntity("http://localhost:9000/multiply", "10", String::class.java)

                assertThat(response.statusCode.value()).isEqualTo(200)
                assertThatNoException().isThrownBy { response.body!!.toInt() }
            }
        }

        assertThat(fakeFileWriter.receivedContract?.trim()).startsWith("openapi:")
        assertThatCode {
            OpenApiSpecification.fromYAML(
                fakeFileWriter.receivedContract!!,
                "",
            )
        }.doesNotThrowAnyException()
        assertThatCode { parsedJSON(fakeFileWriter.receivedStub ?: "") }.doesNotThrowAnyException()
        assertThat(fakeFileWriter.receivedPaths.toList()).containsExactlyInAnyOrder(
            "proxy_generated.yaml",
            "multiply_POST_200_1.json",
        )
    }

    @Test
    fun `proxy should record large numeric path segments as ids`() {
        val feature =
            parseGherkinStringToFeature(
                """
                Feature: Orders
                  Scenario: Get order
                    When GET /orders/(id:string)
                    Then status 200
                """.trimIndent(),
            )

        HttpStub(feature).use {
            Proxy(host = "localhost", port = 9001, "http://localhost:9000", fakeFileWriter).use {
                val restProxy = java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress("localhost", 9001))
                val requestFactory = SimpleClientHttpRequestFactory()
                requestFactory.setProxy(restProxy)
                val client = RestTemplate(requestFactory)
                val response = client.getForEntity("http://localhost:9000/orders/5432154321", String::class.java)

                assertThat(response.statusCode.value()).isEqualTo(200)
            }
        }

        assertThat(fakeFileWriter.receivedContract?.trim()).contains("/orders/{id}")
    }

    @Test
    fun `basic test of the reverse proxy`() {
        HttpStub(simpleFeature).use {
            Proxy(host = "localhost", port = 9001, "http://localhost:9000", fakeFileWriter).use {
                val client = RestTemplate()
                val response = client.postForEntity("http://localhost:9001/", "10", String::class.java)

                assertThat(response.statusCode.value()).isEqualTo(200)
                assertThatNoException().isThrownBy { response.body!!.toInt() }
            }
        }

        assertThat(fakeFileWriter.receivedContract?.trim()).startsWith("openapi:")
        assertThatCode {
            OpenApiSpecification.fromYAML(
                fakeFileWriter.receivedContract!!,
                "",
            )
        }.doesNotThrowAnyException()
        assertThatCode { parsedJSON(fakeFileWriter.receivedStub ?: "") }.doesNotThrowAnyException()
        assertThat(fakeFileWriter.receivedPaths).containsExactlyInAnyOrder("proxy_generated.yaml", "POST_200_1.json")
    }

    @Test
    fun `reverse proxy should record a space appropriately`() {
        val spec =
            OpenApiSpecification
                .fromYAML(
                    """
                    openapi: 3.0.1
                    info:
                      title: Data
                      version: "1"
                    paths:
                      /da ta:
                        get:
                          summary: Data
                          responses:
                            "200":
                              description: Data
                              content:
                                text/plain:
                                  schema:
                                    type: string
                    """.trimIndent(),
                    "",
                ).toFeature()

        HttpStub(spec).use {
            Proxy(host = "localhost", port = 9001, "http://localhost:9000", fakeFileWriter).use {
                val client = RestTemplate()
                client.getForEntity("http://localhost:9001/da ta", String::class.java)
            }
        }

        assertThat(fakeFileWriter.receivedContract?.trim()).startsWith("openapi:")
        assertThat(fakeFileWriter.receivedContract!!).contains("/da ta")
    }

    @Test
    fun `should not include standard http headers in the generated specification`() {
        HttpStub(simpleFeature).use {
            Proxy(host = "localhost", port = 9001, "http://localhost:9000", fakeFileWriter).use {
                val client = RestTemplate()
                val response = client.postForEntity("http://localhost:9001/", "10", String::class.java)

                assertThat(response.statusCode.value()).isEqualTo(200)
                assertThatNoException().isThrownBy { response.body!!.toInt() }
            }
        }

        assertThat(fakeFileWriter.receivedContract?.trim()).startsWith("openapi:")

        dynamicHttpHeaders.forEach {
            assertThat(fakeFileWriter.receivedContract)
                .withFailMessage("Specification should not have contained $it")
                .doesNotContainIgnoringCase("name: $it")
            assertThat(fakeFileWriter.receivedStub).withFailMessage("Stub should not have contained $it")
        }
    }

    @Test
    fun `should not include a body for GET requests with no body`() {
        HttpStub(simpleFeature).use {
            Proxy(host = "localhost", port = 9001, "http://localhost:9000", fakeFileWriter).use {
                val client = RestTemplate()
                val response = client.getForEntity("http://localhost:9001/", String::class.java)

                assertThat(response.statusCode.value()).isEqualTo(200)
                assertThatNoException().isThrownBy { response.body!!.toInt() }
            }
        }

        assertThat(fakeFileWriter.receivedContract?.trim()).doesNotContainIgnoringCase("requestBody")

        val requestInTheGeneratedExpectation =
            (parsedJSONObject(fakeFileWriter.receivedStub!!).jsonObject["http-request"] as JSONObjectValue).jsonObject
        assertThat(requestInTheGeneratedExpectation).doesNotContainKeys("body")
    }

    @Test
    fun `should use the text-html content type from the actual response instead of inferring it`() {
        val featureWithHTMLResponse =
            OpenApiSpecification
                .fromYAML(
                    """
                    openapi: 3.0.1
                    info:
                      title: Data
                      version: "1"
                    paths:
                      /:
                        get:
                          summary: Data
                          responses:
                            "200":
                              description: Data
                              content:
                                text/html:
                                  schema:
                                    type: string
                    """.trimIndent(),
                    "",
                ).toFeature()

        HttpStub(featureWithHTMLResponse).use {
            Proxy(host = "localhost", port = 9001, "http://localhost:9000", fakeFileWriter).use {
                val client = RestTemplate()
                client.getForEntity("http://localhost:9001/", String::class.java)
            }
        }

        assertThat(fakeFileWriter.receivedStub).withFailMessage(fakeFileWriter.receivedStub).contains("text/html")
        assertThat(fakeFileWriter.receivedContract).withFailMessage(fakeFileWriter.receivedContract).contains("text/html")

        HttpStub(OpenApiSpecification.fromYAML(fakeFileWriter.receivedContract!!, "").toFeature()).use { stub ->
        }
    }

    @Test
    fun `should return health status as UP if the actuator health endpoint is hit`() {
        HttpStub(simpleFeature).use {
            Proxy(host = "localhost", port = 9001, "http://localhost:9001", fakeFileWriter).use {
                val client = RestTemplate()
                val response = client.getForEntity("http://localhost:9001/actuator/health", Map::class.java)

                assertThat(response.statusCode.value()).isEqualTo(200)
                assertThat(response.body).isEqualTo(mapOf("status" to "UP"))
            }
        }
    }

    @Test
    fun `should dump the examples and spec if the specmatic proxy dump endpoint is hit`() {
        HttpStub(simpleFeature).use {
            Proxy(host = "localhost", port = 9001, "http://localhost:9000", fakeFileWriter).use {
                val restProxy = java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress("localhost", 9001))
                val requestFactory = SimpleClientHttpRequestFactory()
                requestFactory.setProxy(restProxy)
                val client = RestTemplate(requestFactory)
                client.postForEntity("http://localhost:9000/multiply", "10", String::class.java)

                val response = client.postForEntity<String>("http://localhost:9001/_specmatic/proxy/dump")

                assertThat(response.statusCode.value()).isEqualTo(202)
                assertThat(response.body).isEqualTo("Dump process of spec and examples has started in the background")

                val waiter = Waiter(1000L, 30000L)

                while (waiter.canWaitForMoreTime()) {
                    if (fakeFileWriter.receivedContract != null) {
                        break
                    }

                    waiter.waitForMoreTime()
                }

                if (fakeFileWriter.receivedContract == null && waiter.hasTimeRunOut()) {
                    throw Exception("Timed out waiting for the contract to be written")
                }

                assertThat(fakeFileWriter.receivedContract?.trim()).startsWith("openapi:")
                assertThatCode {
                    OpenApiSpecification.fromYAML(
                        fakeFileWriter.receivedContract!!,
                        "",
                    )
                }.doesNotThrowAnyException()
                assertThatCode { parsedJSON(fakeFileWriter.receivedStub ?: "") }.doesNotThrowAnyException()
                assertThat(fakeFileWriter.receivedPaths.toList()).contains(
                    "proxy_generated.yaml",
                    "multiply_POST_200_1.json",
                )
            }
        }
    }

    @Test
    fun `should not timeout if custom timeout is greater than backend service delay`() {
        HttpStub(simpleFeature).use { fake ->
            val expectation =
                """
                 {
                    "http-request": {
                        "method": "POST",
                        "path": "/",
                        "body": "10"
                    },
                    "http-response": {
                        "status": 200,
                        "body": "100"
                    },
                    "$DELAY_IN_MILLISECONDS": 100
                }
                """.trimIndent()

            val stubResponse =
                RestTemplate().postForEntity<String>(fake.endPoint + "/_specmatic/expectations", expectation)
            assertThat(stubResponse.statusCode.value()).isEqualTo(200)

            Proxy(host = "localhost", port = 9001, "", fakeFileWriter, timeoutInMilliseconds = 5000).use {
                val restProxy = java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress("localhost", 9001))
                val requestFactory = SimpleClientHttpRequestFactory()
                requestFactory.setProxy(restProxy)
                val client = RestTemplate(requestFactory)
                val response = client.postForEntity("http://localhost:9000/", "10", String::class.java)

                assertThat(response.statusCode.value()).isEqualTo(200)
                assertThatNoException().isThrownBy { response.body!!.toInt() }
            }
        }
    }

    @Test
    fun `should timeout if custom timeout is less than backend service delay`() {
        HttpStub(simpleFeature).use { fake ->
            val expectation =
                """
                 {
                    "http-request": {
                        "method": "POST",
                        "path": "/",
                        "body": "10"
                    },
                    "http-response": {
                        "status": 200,
                        "body": "100"
                    },
                    "$DELAY_IN_MILLISECONDS": 5000
                }
                """.trimIndent()

            val stubResponse =
                RestTemplate().postForEntity<String>(fake.endPoint + "/_specmatic/expectations", expectation)
            assertThat(stubResponse.statusCode.value()).isEqualTo(200)

            assertThrows<Exception> {
                Proxy(host = "localhost", port = 9001, "", fakeFileWriter, timeoutInMilliseconds = 100).use {
                    val restProxy = java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress("localhost", 9001))
                    val requestFactory = SimpleClientHttpRequestFactory()
                    requestFactory.setProxy(restProxy)
                    val client = RestTemplate(requestFactory)
                    client.postForEntity("http://localhost:9000/", "10", String::class.java)
                }
            }
        }
    }

    @Test
    fun `should not include any specmatic headers in the final stubs and api specification`() {
        val openApiFile = File("src/test/resources/openapi/partial_with_discriminator/openapi.yaml")
        val feature = OpenApiSpecification.fromFile(openApiFile.canonicalPath).toFeature()

        HttpStub(feature).use {
            Proxy(host = "localhost", port = 9001, "http://localhost:9000", fakeFileWriter).use {
                val request = feature.scenarios.first().generateHttpRequest().addHeaderIfMissing(SPECMATIC_RESPONSE_CODE_HEADER, "201")
                val response = HttpClient(baseURL = "http://localhost:9001").use { it.execute(request) }

                assertThat(response.status).isEqualTo(201)
                assertThat(response.headers.keys.map(String::toLowerCasePreservingASCIIRules)).contains(
                    "x-specmatic-result", "x-specmatic-type", "content-type"
                )
            }
        }

        assertThat(fakeFileWriter.receivedContract).doesNotContainIgnoringCase("Specmatic", "Content-Type")
        assertThat(fakeFileWriter.receivedStub).doesNotContainIgnoringCase("Specmatic").containsIgnoringCase("Content-Type")
    }

    @Test
    fun `dumped specification headers and query params should be in-sync with dumped examples and also scalar type`() {
        val openApiFile = File("src/test/resources/openapi/partial_with_discriminator/openapi.yaml")
        val feature = OpenApiSpecification.fromFile(openApiFile.canonicalPath).toFeature()

        HttpStub(feature).use {
            Proxy(host = "localhost", port = 9001, "http://localhost:9000", fakeFileWriter).use {
                val request = feature.scenarios.first().generateHttpRequest()
                    .addHeaderIfMissing(SPECMATIC_RESPONSE_CODE_HEADER, "201")
                    .addHeaderIfMissing(HttpHeaders.AccessControlAllowOrigin, "*")
                    .addHeaderIfMissing(HttpHeaders.ContentDisposition, "*")

                val response = HttpClient(baseURL = "http://localhost:9001").use { it.execute(request) }
                assertThat(response.status).isEqualTo(201)
                assertThat(response.headers.keys.map(String::toLowerCasePreservingASCIIRules)).contains(
                    "x-specmatic-result", "x-specmatic-type", "content-type"
                )
            }
        }

        assertThat(fakeFileWriter.receivedContract).doesNotContainIgnoringCase("Specmatic", "Content-Type", HttpHeaders.AccessControlAllowOrigin, HttpHeaders.ContentDisposition)
        assertThat(fakeFileWriter.receivedStub).doesNotContainIgnoringCase("Specmatic").containsIgnoringCase("Content-Type")
        val scenario = OpenApiSpecification.fromYAML(fakeFileWriter.receivedContract!!, "").toFeature().scenarios.first()
        val reqHeader = scenario.httpRequestPattern.headersPattern.pattern["my-req-header"]
        if (reqHeader == null) {
            fail("Expected request header 'my-req-header' to be of type StringPattern but did not get it")
        }
        assertThat(reqHeader).isInstanceOf(StringPattern::class.java)

        val queryParam = scenario.httpRequestPattern.httpQueryParamPattern.queryPatterns["my-req-query?"]
        if (queryParam == null) {
            fail("Expected query param 'my-req-query' to be of type StringPattern but did not get it")
        }
        assertThat(queryParam).isInstanceOf(QueryParameterScalarPattern::class.java)
        assertThat(queryParam.pattern).isInstanceOf(StringPattern::class.java)

        val resHeader = scenario.httpResponsePattern.headersPattern.pattern["my-res-header"]
        if (resHeader == null) {
            fail("Expected response header 'my-res-header' to be of type StringPattern but did not get it")
        }
        assertThat(resHeader).isInstanceOf(StringPattern::class.java)
    }
}

class FakeFileWriter private constructor(
    private val state: FakeFileWriterState,
    private val pathPrefix: String,
) : FileWriter {
    constructor() : this(FakeFileWriterState(), "")

    var receivedContract: String?
        get() = state.receivedContract
        set(value) {
            state.receivedContract = value
        }

    var receivedStub: String?
        get() = state.receivedStub
        set(value) {
            state.receivedStub = value
        }

    val flags: MutableList<String>
        get() = state.flags

    val receivedPaths: MutableList<String>
        get() = state.receivedPaths

    override fun createDirectory() {
        state.flags.add("createDirectory")
        File(state.baseDir, pathPrefix).mkdirs()
    }

    override fun clearDirectory() {
        state.flags.add("removeDirectory")
        File(state.baseDir, pathPrefix).deleteRecursively()
    }

    override fun writeText(
        path: String,
        content: String,
    ) {
        val fullPath = fullPath(path)
        state.receivedPaths.add(path)

        if (path.endsWith(".$YAML")) {
            state.receivedContract = content
        } else {
            state.receivedStub = content
        }

        val target = File(state.baseDir, fullPath)
        target.parentFile?.mkdirs()
        target.writeText(content)
    }

    override fun subDirectory(path: String): FileWriter =
        FakeFileWriter(state, fullPath(path))

    override fun fileName(path: String): String =
        File(state.baseDir, fullPath(path)).path

    private fun fullPath(path: String): String =
        if (pathPrefix.isBlank()) path else File(pathPrefix, path).path
}

private class FakeFileWriterState {
    val baseDir: File = Files.createTempDirectory("specmatic-proxy-test").toFile()
    var receivedContract: String? = null
    var receivedStub: String? = null
    val flags: MutableList<String> = mutableListOf()
    val receivedPaths: MutableList<String> = mutableListOf()
}
