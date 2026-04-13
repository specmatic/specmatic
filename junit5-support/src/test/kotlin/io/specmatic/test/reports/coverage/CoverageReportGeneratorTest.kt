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

class CoverageReportGeneratorTest {
    private val reportGenerator = CoverageReportGenerator()

    @Test
    fun `should assemble coverage report operations with expected statuses and spec configs`() {
        val coveredEndpoint = endpoint("/orders", "POST", "application/json", 200, "application/json")
        val notTestedEndpoint = endpoint("/orders", "GET", null, 200, "application/json")
        val attemptedButNotMatchedEndpoint = endpoint("/orders", "PUT", "application/json", 202, "application/json")

        val coveredRecord = testResultRecord(
            operation = coveredEndpoint.toOpenApiOperation(),
            actualResponseStatus = 200,
            actualResponseContentType = "application/json",
        )
        val notImplementedRecord = testResultRecord(
            operation = attemptedButNotMatchedEndpoint.toOpenApiOperation(),
            actualResponseStatus = 400,
            actualResponseContentType = "application/json",
        )
        val testDerivedMissingInSpec = TestResultRecord(
            path = "/orders",
            method = "POST",
            responseStatus = 400,
            responseContentType = "application/json",
            request = null,
            response = HttpResponse(
                status = 400,
                headers = mapOf("Content-Type" to "application/json"),
            ),
            result = TestResult.Success,
            specification = "specs/openapi.yaml",
            specType = SpecType.OPENAPI,
            requestContentType = "application/json",
            actualResponseStatus = 400,
            actualResponseContentType = "application/json",
        )
        val context = CoverageContext(
            tests = listOf(coveredRecord, notImplementedRecord, testDerivedMissingInSpec),
            allSpecEndpoints = listOf(coveredEndpoint, notTestedEndpoint, attemptedButNotMatchedEndpoint),
            specEndpointsInScope = listOf(coveredEndpoint, notTestedEndpoint, attemptedButNotMatchedEndpoint),
            applicationEndpoints = listOf(API(method = "POST", path = "/payments")),
            endpointsApiAvailable = true,
        )

        val reportOperations = reportGenerator.generate(context)

        assertThat(reportOperations.single { (it.operation as OpenAPIOperation).responseCode == 200 && (it.operation as OpenAPIOperation).method == "POST" }.coverageStatus)
            .isEqualTo(CoverageStatus.COVERED)
        assertThat(reportOperations.single { (it.operation as OpenAPIOperation).method == "GET" }.coverageStatus)
            .isEqualTo(CoverageStatus.NOT_TESTED)
        assertThat(reportOperations.single { (it.operation as OpenAPIOperation).method == "PUT" }.coverageStatus)
            .isEqualTo(CoverageStatus.NOT_IMPLEMENTED)

        val missingInSpecFromTest = reportOperations.single {
            val operation = it.operation as OpenAPIOperation
            operation.path == "/orders" && operation.responseCode == 400
        }
        assertThat(missingInSpecFromTest.coverageStatus).isEqualTo(CoverageStatus.MISSING_IN_SPEC)
        assertThat(missingInSpecFromTest.specConfig.specification).isEqualTo("specs/openapi.yaml")

        val missingInSpecFromApplicationEndpoint = reportOperations.single {
            val operation = it.operation as OpenAPIOperation
            operation.path == "/payments"
        }
        assertThat(missingInSpecFromApplicationEndpoint.coverageStatus).isEqualTo(CoverageStatus.MISSING_IN_SPEC)
        assertThat(missingInSpecFromApplicationEndpoint.specConfig.specification).isEqualTo("specs/openapi.yaml")
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
