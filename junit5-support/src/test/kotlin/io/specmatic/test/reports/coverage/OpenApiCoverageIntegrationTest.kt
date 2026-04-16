package io.specmatic.test.reports.coverage

import io.ktor.http.HttpStatusCode
import io.specmatic.core.HttpResponse
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.pattern.parsedJsonValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.toXML
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.ctrf.model.CtrfOperationQualifiers
import io.specmatic.reporter.ctrf.model.CtrfTestQualifiers
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.utils.ContractTestScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.io.TempDir
import java.io.File

class OpenApiCoverageIntegrationTest {
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

        ContractTestScope.from(specYaml, tempDir).execute(SpecmaticConfig().enableResiliencyTests()) { server ->
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

        ContractTestScope.from(specYaml, tempDir).execute { server ->
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
            assertThat(wipView.tests).hasSize(1).allSatisfy { test -> assertThat(test.result).isEqualTo(TestResult.Error) }
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

        ContractTestScope.from(specYaml, tempDir).execute { server ->
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
                assertThat(test.extraFields().qualifiers).contains(CtrfTestQualifiers.RESPONSE_UNDECLARED)
            }
        }
    }

    @Test
    @Disabled // TODO: Needs to be fixed in Core, PR Raised separately
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

        ContractTestScope(wsdlSpecFile, tempDir).execute { server ->
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
