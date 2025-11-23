package io.specmatic.test

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.test.reports.coverage.OpenApiCoverageReportInput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.*

class SpecmaticDynamicTestStreamTest {
    @Test
    fun `should be able to get actuator endpoints from swaggerUI`() {
        val contractTestHarness = SpecmaticDynamicTestStream(
            OpenApiCoverageReportInput(""),
            HttpInteractionsLog(),
            Vector<String>(),
            null
        )

        contractTestHarness.actuatorFromSwagger("", object: TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse(
                    200,
                    body = """
                    openapi: 3.0.1
                    info:
                      title: Order BFF
                      version: '1.0'
                    paths:
                      /orders:
                        post:
                          responses:
                            '200':
                              description: OK
                      /products:
                        post:
                          responses:
                            '200':
                              description: OK
                      /findAvailableProducts/{date_time}:
                        get:
                          parameters:
                            - ${"$"}ref: '#/components/parameters/DateTimeParameter'
                          responses:
                            '200':
                              description: OK
                    components:
                        schemas:
                            DateTime:
                                type: string
                                format: date-time
                        parameters:
                            DateTimeParameter:
                                name: date_time
                                in: path
                                required: true
                                schema:
                                    ${"$"}ref: '#/components/schemas/DateTime'
                    """.trimIndent()
                )
            }
        })

        assertThat(contractTestHarness.openApiCoverageReportInput.endpointsAPISet).isTrue()
        assertThat(contractTestHarness.openApiCoverageReportInput.getApplicationAPIs()).isEqualTo(listOf(
            API("POST", "/orders"),
            API("POST", "/products"),
            API("GET", "/findAvailableProducts/{date_time}")
        ))
    }
}