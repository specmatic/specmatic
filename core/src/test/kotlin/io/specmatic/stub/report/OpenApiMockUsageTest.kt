package io.specmatic.stub.report

import io.specmatic.core.HttpResponse
import io.specmatic.core.SpecmaticConfig
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.TestResultRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenApiMockUsageTest {
    @Test
    fun `should generate operations and calculate coverage percentage`() {
        val mockUsage = OpenApiMockUsage(SpecmaticConfig())

        val coveredEndpoint = endpoint("/orders", "POST", "application/json", 201, "application/json")
        val notCoveredEndpoint = endpoint("/orders", "GET", null, 200, "application/json")
        val mismatchEndpoint = endpoint("/orders", "PUT", "application/json", 202, "application/json")

        mockUsage.addEndpoints(listOf(coveredEndpoint, notCoveredEndpoint, mismatchEndpoint))
        mockUsage.addTestResultRecord(
            testResultRecord(
                operation = coveredEndpoint.toOpenApiOperation(),
                actualResponseStatus = 201,
                actualResponseContentType = "application/json",
            )
        )
        mockUsage.addTestResultRecord(
            testResultRecord(
                operation = mismatchEndpoint.toOpenApiOperation(),
                actualResponseStatus = 500,
                actualResponseContentType = "application/json",
            )
        )
        mockUsage.addTestResultRecord(
            TestResultRecord(
                path = "/unknown",
                method = "GET",
                responseStatus = 0,
                request = null,
                response = HttpResponse(status = 404),
                result = TestResult.MissingInSpec,
                specType = SpecType.OPENAPI,
                actualResponseStatus = 404,
                testType = TestResultRecord.STUB_TEST_TYPE,
            )
        )

        val reportOperations = mockUsage.generate()

        assertThat(reportOperations).hasSize(4)
        assertThat(reportOperations.single { it.operation.path == "/orders" && it.operation.method == "POST" }.coverageStatus)
            .isEqualTo(CoverageStatus.COVERED)
        assertThat(mockUsage.totalCoveragePercentage()).isEqualTo(33)
    }

    private fun endpoint(
        path: String,
        method: String,
        requestContentType: String?,
        responseStatus: Int,
        responseContentType: String?,
    ) = StubEndpoint(
        path = path,
        method = method,
        responseCode = responseStatus,
        requestContentType = requestContentType,
        responseContentType = responseContentType,
        protocol = SpecmaticProtocol.HTTP,
        specType = SpecType.OPENAPI,
        specification = "specs/openapi.yaml",
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
        result = TestResult.Success,
        actualResponseStatus = actualResponseStatus,
        actualResponseContentType = actualResponseContentType,
        specType = SpecType.OPENAPI,
        requestContentType = operation.contentType,
        operations = setOf(operation),
        specification = "specs/openapi.yaml",
        testType = TestResultRecord.STUB_TEST_TYPE,
    )
}
