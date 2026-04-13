package io.specmatic.test.reports.coverage

import io.specmatic.core.HttpResponse
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.API
import io.specmatic.test.TestResultRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenApiCoverageTest {
    private val coverage = OpenApiCoverage()

    @Test
    fun `should expose the snapshot to operation to report operation flow`() {
        val coveredEndpoint = endpoint("/orders", "POST", "application/json", 200, "application/json")
        val notTestedEndpoint = endpoint("/orders", "GET", null, 200, "application/json")
        val coveredRecord = testResultRecord(
            operation = coveredEndpoint.toOpenApiOperation(),
            actualResponseStatus = 200,
            actualResponseContentType = "application/json",
        )

        coverage.addEndpoints(
            allEndpoints = listOf(coveredEndpoint, notTestedEndpoint),
            filteredEndpoints = listOf(coveredEndpoint, notTestedEndpoint),
        )
        coverage.addTestReportRecords(coveredRecord)
        coverage.addAPIs(listOf(API(method = "POST", path = "/payments")))
        coverage.setEndpointsAPIFlag(true)

        val context = coverage.coverageContext()
        val reportOperations = coverage.generate()

        assertThat(context.specEndpointsInScope).containsExactlyInAnyOrder(coveredEndpoint, notTestedEndpoint)
        assertThat(reportOperations).hasSize(3)
        assertThat(reportOperations.single {
            val operation = it.operation as OpenAPIOperation
            operation.method == "POST" && operation.path == "/orders" && operation.responseCode == 200
        }.coverageStatus)
            .isEqualTo(CoverageStatus.COVERED)
        assertThat(reportOperations.single {
            val operation = it.operation as OpenAPIOperation
            operation.method == "GET" && operation.path == "/orders"
        }.coverageStatus)
            .isEqualTo(CoverageStatus.NOT_TESTED)
        assertThat(reportOperations.single {
            val operation = it.operation as OpenAPIOperation
            operation.path == "/payments"
        }.coverageStatus)
            .isEqualTo(CoverageStatus.MISSING_IN_SPEC)
    }

    private fun endpoint(
        path: String,
        method: String,
        requestContentType: String?,
        responseStatus: Int,
        responseContentType: String?,
    ) = Endpoint(
        path = path,
        method = method,
        responseStatus = responseStatus,
        requestContentType = requestContentType,
        responseContentType = responseContentType,
        protocol = SpecmaticProtocol.HTTP,
        specType = SpecType.OPENAPI,
        specification = "specs/openapi.yaml",
    )

    private fun Endpoint.toOpenApiOperation() = OpenAPIOperation(
        path = path,
        method = soapAction ?: method,
        contentType = requestContentType,
        responseCode = responseStatus,
        protocol = protocol,
        responseContentType = responseContentType,
    )

    private fun testResultRecord(
        operation: OpenAPIOperation,
        actualResponseStatus: Int,
        actualResponseContentType: String?,
    ) = TestResultRecord(
        path = operation.path,
        method = operation.method,
        responseStatus = operation.responseCode,
        responseContentType = operation.responseContentType,
        request = null,
        response = HttpResponse(
            status = actualResponseStatus,
            headers = actualResponseContentType?.let { mapOf("Content-Type" to it) }.orEmpty(),
        ),
        result = if (actualResponseStatus == operation.responseCode) TestResult.Success else TestResult.Failed,
        actualResponseStatus = actualResponseStatus,
        actualResponseContentType = actualResponseContentType,
        specType = SpecType.OPENAPI,
        requestContentType = operation.contentType,
        operations = setOf(operation),
        specification = "specs/openapi.yaml",
    )
}
