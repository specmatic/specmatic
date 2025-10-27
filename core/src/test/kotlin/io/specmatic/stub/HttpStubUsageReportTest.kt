package io.specmatic.stub

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.HttpRequest
import io.specmatic.core.SourceProvider
import io.specmatic.reports.OpenApiOperationGroup
import io.specmatic.reports.ReportItem
import io.specmatic.reports.ReportMetadata
import io.specmatic.reports.ServiceType
import io.specmatic.reports.StubUsageReport
import io.specmatic.stub.report.StubEndpoint
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestTemplate
import java.io.File
import kotlin.concurrent.thread

class HttpStubUsageReportTest {
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

    @AfterEach
    fun cleanUpAnyExistingReports() {
        stubUsageReportFile.delete()
    }

    @Test
    fun `should determine all the api endpoints across all the specs`() {
        val stubContract1 = OpenApiSpecification.fromYAML(helloAndDataSpec, "").toFeature()
        val stubContract2 = OpenApiSpecification.fromYAML(hello2AndData2Spec, "").toFeature()

        HttpStub(listOf(stubContract1, stubContract2)).use { stub ->
            assertThat(stub.allEndpoints).isEqualTo(listOf(
                StubEndpoint("hello world. Response: Says hello", "/data", "GET", 200, serviceType = "HTTP"),
                StubEndpoint("hello world. Response: Says hello", "/hello", "GET", 200, serviceType = "HTTP"),
                StubEndpoint("hello world. Response: Says hello", "/data2", "GET", 200, serviceType = "HTTP"),
                StubEndpoint("hello world. Response: Says hello", "/hello2", "GET", 200, serviceType = "HTTP"),
            ))
        }
    }

    @Test
    fun `should generate stub usage report when stub is stopped`() {
        val stubContract1 = OpenApiSpecification.fromYAML(helloAndDataSpec, "", sourceProvider = "git", sourceRepository = "https://github.com/specmatic/specmatic-order-contracts.git", sourceRepositoryBranch = "main", specificationPath = "in/specmatic/examples/store/helloAndDataSpec.yaml").toFeature()
        val stubContract2 = OpenApiSpecification.fromYAML(hello2AndData2Spec, "", sourceProvider = "git", sourceRepository = "https://github.com/specmatic/specmatic-order-contracts.git", sourceRepositoryBranch = "main", specificationPath = "in/specmatic/examples/store/hello2AndData2Spec.yaml").toFeature()

        HttpStub(listOf(stubContract1, stubContract2), specmaticConfigPath = "./specmatic.json").use { stub ->
            stub.client.execute(HttpRequest("GET", "/data"))
            stub.client.execute(HttpRequest("GET", "/unknown"))
            stub.client.execute(HttpRequest("GET", "/hello"))
            stub.client.execute(HttpRequest("GET", "/unknown"))
            stub.client.execute(HttpRequest("GET", "/hello2"))
            stub.client.execute(HttpRequest("GET", "/hello2"))
            stub.client.execute(HttpRequest("GET", "/unknown"))
            stub.client.execute(HttpRequest("GET", "/data2"))
            stub.client.execute(HttpRequest("GET", "/data2"))
        }

        val stubUsageReport: StubUsageReport = Json.decodeFromString(stubUsageReportFile.readText())
        assertThat(stubUsageReport).isEqualTo(
            StubUsageReport(
                specmaticConfigPath = StubUsageReportTest.CONFIG_FILE_PATH,
                stubUsage = listOf(
                    ReportItem(
                        metadata = ReportMetadata(
                            SourceProvider.git,
                            "https://github.com/specmatic/specmatic-order-contracts.git",
                            "main",
                            "in/specmatic/examples/store/helloAndDataSpec.yaml",
                            ServiceType.HTTP,
                        ),
                        operations = listOf(
                            OpenApiOperationGroup("/data", "GET",200, 1),
                            OpenApiOperationGroup("/hello", "GET",200, 1),
                        ),
                    ),
                    ReportItem(
                        metadata = ReportMetadata(
                            SourceProvider.git,
                            "https://github.com/specmatic/specmatic-order-contracts.git",
                            "main",
                            "in/specmatic/examples/store/hello2AndData2Spec.yaml",
                            ServiceType.HTTP,
                        ),
                        operations = listOf(
                            OpenApiOperationGroup("/data2", "GET",200, 2),
                            OpenApiOperationGroup("/hello2", "GET",200, 2),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `should log all successful requests when response is faked`() {
        val contract = OpenApiSpecification.fromYAML(helloAndDataSpec, "").toFeature()

        HttpStub(contract).use { stub ->
            stub.client.execute(HttpRequest("GET", "/data"))
            stub.client.execute(HttpRequest("GET", "/hello"))

            assertThat(stub.logs).isEqualTo(listOf(
                StubEndpoint("hello world. Response: Says hello", "/data", "GET", 200, serviceType = "HTTP"),
                StubEndpoint("hello world. Response: Says hello", "/hello", "GET", 200, serviceType = "HTTP")
            ))
        }
    }

    @Test
    fun `should log all successful requests when response is stubbed`() {
        val contract = OpenApiSpecification.fromYAML(helloAndDataSpec, "").toFeature()

        HttpStub(contract).use { stub ->
            stub.setExpectation("""
                {
                    "http-request": {
                        "method": "GET",
                        "path": "/data"
                    },
                    "http-response": {
                        "status": 200,
                        "body": 10
                    }
                }
            """.trimIndent())

            val response = stub.client.execute(HttpRequest("GET", "/data"))
            assertThat(response.body.toStringLiteral()).isEqualTo("10")

            stub.client.execute(HttpRequest("GET", "/hello"))

            stub.client.execute(HttpRequest("GET", "/unknown"))

            assertThat(stub.logs).isEqualTo(listOf(
                StubEndpoint("hello world. Response: Says hello", "/data", "GET", 200, serviceType = "HTTP"),
                StubEndpoint("hello world. Response: Says hello", "/hello", "GET", 200, serviceType = "HTTP"),
                StubEndpoint(null, "/unknown", "GET", 400, serviceType = "HTTP")
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
                StubEndpoint("hello world. Response: Says hello", "/data", "GET", 200, serviceType = "HTTP"),
                StubEndpoint(null, "/unknown", "GET", 400, serviceType = "HTTP")
            ))
        }
    }

    @Test
    fun `should not log unsuccessful requests in strict mode`() {
        val contract = OpenApiSpecification.fromYAML(helloAndDataSpec, "").toFeature()

        HttpStub(features = listOf(contract), strictMode = true).use { stub ->

            stub.setExpectation("""
                {
                    "http-request": {
                        "method": "GET",
                        "path": "/data"
                    },
                    "http-response": {
                        "status": 200,
                        "body": 10
                    }
                }
            """.trimIndent())

            stub.client.execute(HttpRequest("GET", "/data"))
            stub.client.execute(HttpRequest("GET", "/hello"))

            assertThat(stub.logs).isEqualTo(listOf(
                StubEndpoint("hello world. Response: Says hello", "/data", "GET", 200, serviceType = "HTTP"),
                StubEndpoint("hello world. Response: Says hello", "/hello", "GET", 400, serviceType = "HTTP"),
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
            assertThat(stub.logs.all { it == StubEndpoint("hello world. Response: Says hello", "/hello", "GET", 200, serviceType = "HTTP") }).isTrue
        }
    }
}