package io.specmatic.test.reports.coverage

import io.ktor.http.HttpStatusCode
import io.specmatic.core.HttpResponse
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.filters.ScenarioMetadataFilter
import io.specmatic.core.pattern.parsedJsonValue
import io.specmatic.core.utilities.contractTestPathsFrom
import io.specmatic.core.config.toSpecmaticConfig
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.toXML
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.ctrf.model.CtrfOperationQualifiers
import io.specmatic.reporter.ctrf.model.CtrfTestQualifiers
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.API
import io.specmatic.test.SpecmaticJUnitSupport
import io.specmatic.test.utils.ContractTestScope
import io.specmatic.test.utils.OpenApiCoverageVerifier.Companion.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class OpenApiCoverageIntegrationTest {
    @Test
    fun `should report 405 external example test against original operation method`(@TempDir tempDir: File) {
        val specFile = tempDir.resolve("orders.yaml").apply {
            writeText(
                """
                openapi: 3.0.0
                info:
                  title: Orders
                  version: 1.0.0
                paths:
                  /orders:
                    post:
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema:
                              type: object
                              required:
                                - data
                              properties:
                                data:
                                  type: string
                      responses:
                        '405':
                          description: method not allowed
                          content:
                            application/json:
                              schema:
                                type: object
                                required:
                                  - error
                                properties:
                                  error:
                                    type: string
                """.trimIndent()
            )
        }
        tempDir.resolve("orders_examples").apply {
            mkdirs()
            resolve("patch-orders-405.json").writeText(
                """
                {
                  "http-request": {
                    "method": "PATCH",
                    "path": "/orders",
                    "headers": {
                      "Content-Type": "application/json"
                    },
                    "body": {
                      "data": "found"
                    }
                  },
                  "http-response": {
                    "status": 405,
                    "headers": {
                      "Content-Type": "application/json"
                    },
                    "body": {
                      "error": "occurred"
                    }
                  }
                }
                """.trimIndent()
            )
        }

        ContractTestScope(specFile, tempDir).execute(v3Config(tempDir)) { server ->
            server.on("/orders", "PATCH") {
                body("""{"data":"found"}""")
                respond(HttpResponse(status = 405, headers = mapOf("Content-Type" to "application/json"), body = parsedJsonValue("""{"error":"occurred"}""")))
            }
        }.verifyOpenApiCoverage {
            val methodNotAllowed = single("POST", "/orders", 405, requestType = "application/json", responseType = "application/json")
            assertThat(methodNotAllowed.tests).hasSize(1).allSatisfy { test ->
                assertThat(test.result).isEqualTo(TestResult.Success)
                assertThat(test.method).isEqualTo("POST")
                assertThat(test.request?.method).isEqualTo("PATCH")
            }
        }
    }

    @Test
    fun `should report 415 external example test against original operation request content type`(@TempDir tempDir: File) {
        val specFile = tempDir.resolve("orders.yaml").apply {
            writeText(
                """
                openapi: 3.0.0
                info:
                  title: Orders
                  version: 1.0.0
                paths:
                  /orders:
                    post:
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema:
                              type: object
                              required:
                                - data
                              properties:
                                data:
                                  type: string
                      responses:
                        '200':
                          description: ok
                        '415':
                          description: unsupported media type
                          content:
                            application/json:
                              schema:
                                type: object
                                required:
                                  - error
                                properties:
                                  error:
                                    type: string
                """.trimIndent()
            )
        }
        tempDir.resolve("orders_examples").apply {
            mkdirs()
            resolve("post-orders-415.json").writeText(
                """
                {
                  "http-request": {
                    "method": "POST",
                    "path": "/orders",
                    "headers": {
                      "Content-Type": "text/plain"
                    },
                    "body": "request sent here"
                  },
                  "http-response": {
                    "status": 415,
                    "headers": {
                      "Content-Type": "application/json"
                    },
                    "body": {
                      "error": "occurred"
                    }
                  }
                }
                """.trimIndent()
            )
        }

        ContractTestScope(specFile, tempDir).execute(v3Config(tempDir)) { server ->
            server.on("/orders", "POST") {
                header("Content-Type", "text/plain")
                body("request sent here")
                respond(HttpResponse(status = 415, headers = mapOf("Content-Type" to "application/json"), body = parsedJsonValue("""{"error":"occurred"}""")))
            }
            server.on("/orders", "POST") {
                respond(HttpResponse.OK)
            }
        }.verifyOpenApiCoverage {
            val unsupportedMediaType = single("POST", "/orders", 415, requestType = "application/json", responseType = "application/json")
            assertThat(unsupportedMediaType.tests).hasSize(1).allSatisfy { test ->
                assertThat(test.result).isEqualTo(TestResult.Success)
                assertThat(test.method).isEqualTo("POST")
                assertThat(test.request?.headers?.get("Content-Type")).isEqualTo("text/plain")
            }
        }
    }

    @Test
    fun `should preserve git root relative spec paths in spec and missing in spec operations`(@TempDir tempDir: File) {
        val repoRoot = tempDir.resolve("repo").apply { mkdirs() }
        runGit(repoRoot, "init")

        val specFile = repoRoot.resolve("specs/orders.yaml").apply {
            parentFile.mkdirs()
            writeText(
                """
                openapi: 3.0.0
                info:
                  title: Orders
                  version: 1.0.0
                paths:
                  /orders:
                    get:
                      responses:
                        '200':
                          description: OK
                """.trimIndent()
            )
        }
        val specsDirectory = repoRoot.resolve("specs").canonicalPath
        val configFile =
            repoRoot.resolve("specmatic.yaml").apply {
                writeText(
                    """
                    version: 3
                    systemUnderTest:
                      service:
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: $specsDirectory
                              specs:
                                - spec:
                                    id: orders
                                    path: orders.yaml
                    """.trimIndent()
                )
            }

        val loadedScenarios = loadScenariosFrom(configFile, repoRoot)

        val coverage = OpenApiCoverage(configFilePath = "specmatic.yaml")
        coverage.addEndpoints(
            allEndpoints = loadedScenarios.allEndpoints,
            filteredEndpoints = loadedScenarios.filteredEndpoints,
        )
        coverage.addAPIs(listOf(API(method = "GET", path = "/orders/search")))
        coverage.setEndpointsAPIFlag(true)

        val report = coverage.generate()
        val expectedSpecificationPath = "specs/orders.yaml"

        report.verify {
            assertThat(report.coverageReportSpecifications()).hasSize(1)
            assertThat(report.coverageReportSpecifications().single().specConfig.specification).isEqualTo(expectedSpecificationPath)
        }
    }

    private fun runGit(directory: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", directory.absolutePath) + args).start()
        val exitCode = process.waitFor()
        assertThat(exitCode).isZero()
    }

    private fun loadScenariosFrom(configFile: File, repoRoot: File) =
        contractTestPathsFrom(
            configFilePath = configFile.canonicalPath,
            workingDirectory = repoRoot.canonicalPath,
        ).single().let { contractPathData ->
            SpecmaticJUnitSupport().loadTestScenarios(
                path = contractPathData.path,
                sourceProvider = contractPathData.provider,
                sourceRepository = contractPathData.repository,
                sourceRepositoryBranch = contractPathData.branch,
                specificationPath = contractPathData.specificationPath,
                filterName = null,
                filterNotName = null,
                specmaticConfig = SpecmaticConfig(),
                filter = ScenarioMetadataFilter.from(""),
            )
        }

    @Test
    fun `should report non-matching response identifiers for -ve generate tests to the nearest matching operations`(@TempDir tempDir: File) {
        val specYaml = """
        openapi: 3.0.0
        info:
          title: Reporting generative
          version: 1.0.0
        paths:
          /orders:
            get:
              parameters:
                - in: header
                  name: X-Header
                  required: true
                  schema:
                    type: integer
              responses:
                '200':
                  description: OK
                '400':
                  description: Bad request
                  content:
                    text/plain:
                      schema:
                        type: string
                    application/json:
                      schema:
                        type: object
                        properties:
                          message:
                            type: string
                '422':
                  description: Unprocessable request
                  content:
                    text/plain:
                      schema:
                        type: string
                    application/json:
                      schema:
                        type: object
                        properties:
                          message:
                            type: string
        """.trimIndent()

        ContractTestScope.from(specYaml, tempDir).execute(v3Config(tempDir).enableResiliencyTests()) { server ->
            server.on("/orders", "GET") {
                header("X-Header", "(number)")
                respond(200)
            }

            server.on("/orders", "GET") {
                header("X-Header", "(boolean)")
                respond(HttpResponse(status = 422, body = "<message>text/xml</message>".toXML()))
            }

            server.on("/orders", "GET") {
                header("X-Header", "(string)")
                respond(HttpResponse(status = 500, body = parsedJsonValue("""{"message": "application/json"}""")))
            }

            server.on("/orders", "GET") {
                respond(HttpResponse(status = 400, body = StringValue("text/plain")))
            }
        }.verifyOpenApiCoverage {
            assertThat(totalOperations).isEqualTo(5)
            assertThat(operations).hasSize(5)

            val successView = single("GET", "/orders", 200)
            assertThat(successView.operation.eligibleForCoverage).isTrue
            assertThat(successView.operation.coverageStatus).isEqualTo(CoverageStatus.COVERED)
            assertThat(successView.tests).hasSize(1).allSatisfy { test -> assertThat(test.result).isEqualTo(TestResult.Success) }

            val badReqAppJson = single("GET", "/orders", 400, responseType = "application/json")
            assertThat(badReqAppJson.operation.eligibleForCoverage).isTrue
            assertThat(badReqAppJson.operation.coverageStatus).isEqualTo(CoverageStatus.NOT_IMPLEMENTED)
            assertThat(badReqAppJson.tests).hasSize(1).allSatisfy { test ->
                assertThat(test.result).isEqualTo(TestResult.Failed)
                assertThat(test.actualResponseStatus).isEqualTo(HttpStatusCode.InternalServerError.value)
            }

            val badReqPlainText = single("GET", "/orders", 400, responseType = "text/plain")
            assertThat(badReqPlainText.operation.eligibleForCoverage).isTrue
            assertThat(badReqPlainText.operation.coverageStatus).isEqualTo(CoverageStatus.COVERED)
            assertThat(badReqPlainText.tests).hasSize(1).allSatisfy { test -> assertThat(test.result).isEqualTo(TestResult.Success) }

            val unprocessableAppJson = single("GET", "/orders", 422, responseType = "application/json")
            assertThat(unprocessableAppJson.operation.eligibleForCoverage).isTrue
            assertThat(unprocessableAppJson.operation.coverageStatus).isEqualTo(CoverageStatus.NOT_TESTED)
            assertThat(unprocessableAppJson.tests).isEmpty()

            val unprocessablePlainText = single("GET", "/orders", 422, responseType = "text/plain")
            assertThat(unprocessablePlainText.operation.eligibleForCoverage).isTrue
            assertThat(unprocessablePlainText.operation.coverageStatus).isEqualTo(CoverageStatus.NOT_IMPLEMENTED)
            assertThat(unprocessablePlainText.tests).hasSize(1).allSatisfy { test ->
                assertThat(test.result).isEqualTo(TestResult.Failed)
                assertThat(test.actualResponseContentType).isEqualTo("text/xml")
            }
        }
    }

    @Test
    fun `should report wip tests with appropriate qualifier`(@TempDir tempDir: File) {
        val specYaml = """
        openapi: 3.0.0
        info:
          title: Reporting wip endpoint
          version: 1.0.0
        tags:
          - name: WIP
          - name: Ordering
        paths:
          /orders:
            get:
              tags:
                - WIP
              responses:
                '200':
                  description: OK
        """.trimIndent()

        ContractTestScope.from(specYaml, tempDir).execute(v3Config(tempDir)) { server ->
            server.on("/orders", "GET") { respond(405) }
        }.verifyOpenApiCoverage {
            assertThat(totalOperations).isEqualTo(1)
            assertThat(operations).hasSize(1)
            val wipView = single("GET", "/orders", 200)
            assertThat(wipView.operation.eligibleForCoverage).isTrue
            assertThat(wipView.operation.metrics?.attempts).isEqualTo(1)
            assertThat(wipView.operation.metrics?.matches).isEqualTo(0)
            assertThat(wipView.operation.qualifiers).contains(CtrfOperationQualifiers.WIP)
            assertThat(wipView.operation.coverageStatus).isEqualTo(CoverageStatus.NOT_IMPLEMENTED)
            assertThat(wipView.tests).hasSize(1).allSatisfy { test -> assertThat(test.result).isEqualTo(TestResult.Failed) }
        }
    }

    @Test
    fun `should report undeclared response qualifier for response outside specification`(@TempDir tempDir: File) {
        val specYaml = """
        openapi: 3.0.0
        info:
          title: Reporting undeclared response
          version: 1.0.0
        paths:
          /orders:
            get:
              responses:
                '200':
                  description: OK
        """.trimIndent()

        ContractTestScope.from(specYaml, tempDir).execute(v3Config(tempDir)) { server ->
            server.on("/orders", "GET") {
                respond(500)
            }
        }.verifyOpenApiCoverage {
            assertThat(totalOperations).isEqualTo(1)
            assertThat(operations).hasSize(1)

            val orderView = single("GET", "/orders", 200)
            assertThat(orderView.operation.coverageStatus).isEqualTo(CoverageStatus.NOT_IMPLEMENTED)
            assertThat(orderView.tests).hasSize(1).allSatisfy { test ->
                assertThat(test.result).isEqualTo(TestResult.Failed)
                assertThat(test.isResponseInSpecification).isFalse()
                assertThat(test.extraFields().qualifiers).contains(CtrfTestQualifiers.UNDECLARED_RESPONSE)
            }
        }
    }

    @Test
    fun `should report wsdl soap coverage end to end with protocol and spec type`(@TempDir tempDir: File) {
        val wsdlSpecFile = File("src/test/resources/simple.wsdl")
        val addInventoryResponse = HttpResponse(
            status = 200,
            headers = mapOf("Content-Type" to "text/xml"),
            body = """<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/" xmlns:tns="http://www.example.com/inventory">
              <soap:Body>
                <tns:AddInventoryResponse>
                  <tns:message>ok</tns:message>
                </tns:AddInventoryResponse>
              </soap:Body>
            </soap:Envelope>""".toXML()
        )

        val getInventoryResponse = HttpResponse(
            status = 200,
            headers = mapOf("Content-Type" to "text/xml"),
            body = """<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/" xmlns:tns="http://www.example.com/inventory">
              <soap:Body>
                <tns:GetInventoryResponse>
                  <tns:productid>101</tns:productid>
                  <tns:inventory>10</tns:inventory>
                </tns:GetInventoryResponse>
              </soap:Body>
            </soap:Envelope>""".toXML()
        )

        ContractTestScope(wsdlSpecFile, tempDir).execute(v3Config(tempDir)) { server ->
            server.on("/ws", "POST") {
                header("SOAPAction", "/addInventory")
                body("(anything)")
                respond(addInventoryResponse)
            }

            server.on("/ws", "POST") {
                header("SOAPAction", "/getInventory")
                body("(anything)")
                respond(getInventoryResponse)
            }
        }.verifyOpenApiCoverage {
            assertThat(totalOperations).isEqualTo(2)
            assertThat(operations).hasSize(2)

            val addInventory = single("addInventory", "/ws", 200)
            assertThat(addInventory.operation.eligibleForCoverage).isTrue
            assertThat(addInventory.operation.coverageStatus).isEqualTo(CoverageStatus.COVERED)
            assertThat(addInventory.apiOperation.protocol).isEqualTo(SpecmaticProtocol.SOAP)
            assertThat(addInventory.tests).hasSize(1).allSatisfy { test ->
                assertThat(test.result).isEqualTo(TestResult.Success)
                assertThat(test.protocol).isEqualTo(SpecmaticProtocol.SOAP)
                assertThat(test.specType).isEqualTo(SpecType.WSDL)
            }

            val getInventory = single("getInventory", "/ws", 200)
            assertThat(getInventory.operation.eligibleForCoverage).isTrue
            assertThat(getInventory.operation.coverageStatus).isEqualTo(CoverageStatus.COVERED)
            assertThat(getInventory.apiOperation.protocol).isEqualTo(SpecmaticProtocol.SOAP)
            assertThat(getInventory.tests).hasSize(1).allSatisfy { test ->
                assertThat(test.result).isEqualTo(TestResult.Success)
                assertThat(test.protocol).isEqualTo(SpecmaticProtocol.SOAP)
                assertThat(test.specType).isEqualTo(SpecType.WSDL)
            }
        }
    }

}

private fun v3Config(tempDir: File) =
    tempDir.resolve("specmatic.yaml").apply {
        writeText("version: 3")
    }.toSpecmaticConfig()
