package io.specmatic.stub

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.HttpRequest
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType
import io.specmatic.stub.report.StubEndpoint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestTemplate
import java.io.File
import kotlin.concurrent.thread

class HttpStubLoggingTest {
    companion object {
        private val stubUsageReportFile = File("./build/reports/specmatic/stub_usage_report.json")
        private val helloAndDataSpec = """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    get:
      summary: hello world
      description: test
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: number
  /hello:
    get:
      summary: hello world
      description: say hello
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
  
        """.trimIndent()
        private val hello2AndData2Spec = """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data2:
    get:
      summary: hello world
      description: test
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: number
  /hello2:
    get:
      summary: hello world
      description: say hello
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
  
        """.trimIndent()
    }

    @Test
    fun `should determine all the api endpoints across all the specs`() {
        val stubContract1 = OpenApiSpecification.fromYAML(helloAndDataSpec, "").toFeature()
        val stubContract2 = OpenApiSpecification.fromYAML(hello2AndData2Spec, "").toFeature()

        HttpStub(listOf(stubContract1, stubContract2)).use { stub ->
            assertThat(stub.allEndpoints).isEqualTo(listOf(
                StubEndpoint(
                    path = "/data",
                    method = "GET",
                    responseCode = 200,
                    responseContentType = "text/plain",
                    specification = "",
                    protocol = SpecmaticProtocol.HTTP,
                    specType = SpecType.OPENAPI
                ),
                StubEndpoint(
                    path = "/hello",
                    method = "GET",
                    responseCode = 200,
                    responseContentType = "text/plain",
                    specification = "",
                    protocol = SpecmaticProtocol.HTTP,
                    specType = SpecType.OPENAPI
                ),
                StubEndpoint(
                    path = "/data2",
                    method = "GET",
                    responseCode = 200,
                    responseContentType = "text/plain",
                    specification = "",
                    protocol = SpecmaticProtocol.HTTP,
                    specType = SpecType.OPENAPI
                ),
                StubEndpoint(
                    path = "/hello2",
                    method = "GET",
                    responseCode = 200,
                    responseContentType = "text/plain",
                    specification = "",
                    protocol = SpecmaticProtocol.HTTP,
                    specType = SpecType.OPENAPI
                )
            ))
        }
    }

    @Test
    fun `should not generate stub usage report when stub is stopped`() {
        stubUsageReportFile.delete()

        val contract = OpenApiSpecification.fromYAML(helloAndDataSpec, "").toFeature()
        HttpStub(
            features = listOf(contract),
            specmaticConfigSource = SpecmaticConfigSource.fromConfigFile("./specmatic.json")
        ).use { stub ->
            stub.client.execute(HttpRequest("GET", "/data"))
        }

        assertThat(stubUsageReportFile).doesNotExist()
    }

    @Test
    fun `should log all successful requests when response is faked`() {
        val contract = OpenApiSpecification.fromYAML(helloAndDataSpec, "").toFeature()

        HttpStub(contract).use { stub ->
            stub.client.execute(HttpRequest("GET", "/data"))
            stub.client.execute(HttpRequest("GET", "/hello"))

            assertThat(stub.logs).isEqualTo(listOf(
                StubEndpoint(
                    path = "/data",
                    method = "GET",
                    responseCode = 200,
                    requestContentType = "text/plain",
                    responseContentType = "text/plain",
                    protocol = SpecmaticProtocol.HTTP,
                    specType = SpecType.OPENAPI
                ),
                StubEndpoint(
                    path = "/hello",
                    method = "GET",
                    responseCode = 200,
                    requestContentType = "text/plain",
                    responseContentType = "text/plain",
                    protocol = SpecmaticProtocol.HTTP,
                    specType = SpecType.OPENAPI
                )
            ))
        }
    }

    @Test
    fun `should log all successful requests when response is stubbed`() {
        val contract = OpenApiSpecification.fromYAML(helloAndDataSpec, "").toFeature()

        HttpStub(contract).use { stub ->
            stub.setExpectation("""{
                "http-request": {
                    "method": "GET",
                    "path": "/data"
                },
                "http-response": {
                    "status": 200,
                    "body": 10
                }
            }""".trimIndent())

            val response = stub.client.execute(HttpRequest("GET", "/data"))
            assertThat(response.body.toStringLiteral()).isEqualTo("10")

            stub.client.execute(HttpRequest("GET", "/hello"))
            stub.client.execute(HttpRequest("GET", "/unknown"))

            assertThat(stub.logs).isEqualTo(listOf(
                StubEndpoint(
                    path = "/data",
                    method = "GET",
                    responseCode = 200,
                    requestContentType = "text/plain",
                    responseContentType = "text/plain",
                    protocol = SpecmaticProtocol.HTTP,
                    specType = SpecType.OPENAPI
                ),
                StubEndpoint(
                    path = "/hello",
                    method = "GET",
                    responseCode = 200,
                    requestContentType = "text/plain",
                    responseContentType = "text/plain",
                    protocol = SpecmaticProtocol.HTTP,
                    specType = SpecType.OPENAPI
                )
            ))
        }
    }

    @Test
    fun `should not log unsuccessful requests`() {
        val contract = OpenApiSpecification.fromYAML(helloAndDataSpec, "").toFeature()
        HttpStub(contract).use { stub ->
            stub.client.execute(HttpRequest("GET", "/data"))
            stub.client.execute(HttpRequest("GET", "/unknown"))

            assertThat(stub.logs).isEqualTo(listOf(
                StubEndpoint(
                    path = "/data",
                    method = "GET",
                    responseCode = 200,
                    requestContentType = "text/plain",
                    responseContentType = "text/plain",
                    protocol = SpecmaticProtocol.HTTP,
                    specType = SpecType.OPENAPI
                ),
            ))
        }
    }

    @Test
    fun `should not log unsuccessful requests in strict mode`() {
        val contract = OpenApiSpecification.fromYAML(helloAndDataSpec, "").toFeature()
        HttpStub(features = listOf(contract), strictMode = true).use { stub ->
            stub.setExpectation("""{
                "http-request": {
                    "method": "GET",
                    "path": "/data"
                },
                "http-response": {
                    "status": 200,
                    "body": 10
                }
            }""".trimIndent())

            stub.client.execute(HttpRequest("GET", "/data"))
            stub.client.execute(HttpRequest("GET", "/hello"))
            assertThat(stub.logs).isEqualTo(listOf(
                StubEndpoint(
                    path = "/data",
                    method = "GET",
                    responseCode = 200,
                    requestContentType = "text/plain",
                    responseContentType = "text/plain",
                    protocol = SpecmaticProtocol.HTTP,
                    specType = SpecType.OPENAPI
                ),
            ))
        }
    }

    @Test
    fun `should log all requests successfully when multiple threads make requests concurrently`() {
        val contract = OpenApiSpecification.fromYAML(helloAndDataSpec, "").toFeature()
        HttpStub(contract).use { stub ->
            val threads = List(10) {
                thread {
                    RestTemplate().getForEntity("http://localhost:9000/hello", String::class.java)
                }
            }

            threads.forEach { it.join() }
            assertThat(stub.logs.count()).isEqualTo(10)
            assertThat(stub.logs.all {
                it == StubEndpoint(
                    path = "/hello",
                    method = "GET",
                    responseCode = 200,
                    responseContentType = "text/plain",
                    protocol = SpecmaticProtocol.HTTP,
                    specType = SpecType.OPENAPI
                )
            }).isTrue
        }
    }
}
