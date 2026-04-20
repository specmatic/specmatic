package io.specmatic.stub.report

import io.specmatic.core.HttpResponse
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.internal.dto.coverage.OmittedStatus
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.TestResultRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MockUsageReportGeneratorTest {
    private val reportGenerator = MockUsageReportGenerator()

    @Test
    fun `should generate covered not used mismatch and missing in spec rows for mock usage`() {
        val coveredEndpoint = endpoint("/orders", "POST", "application/json", 201, "application/json")
        val unusedEndpoint = endpoint("/orders", "GET", null, 200, "application/json")
        val mismatchEndpoint = endpoint("/orders", "PUT", "application/json", 202, "application/json")
        val coveredRecord = testResultRecord(
            operation = coveredEndpoint.toOpenApiOperation(),
            actualResponseStatus = 201,
            actualResponseContentType = "application/json",
        )
        val mismatchRecord = testResultRecord(
            operation = mismatchEndpoint.toOpenApiOperation(),
            actualResponseStatus = 500,
            actualResponseContentType = "application/json",
        )
        val missingInSpecRecord = TestResultRecord(
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

        val context = MockUsageContext(
            tests = listOf(coveredRecord, mismatchRecord, missingInSpecRecord),
            allSpecEndpoints = listOf(coveredEndpoint, unusedEndpoint, mismatchEndpoint),
        )

        val reportOperations = reportGenerator.generate(context)
        val coveredOperation = reportOperations.single { it.operation.path == "/orders" && it.operation.method == "POST" }
        val unusedOperation = reportOperations.single { it.operation.path == "/orders" && it.operation.method == "GET" }
        val mismatchOperation = reportOperations.single { it.operation.path == "/orders" && it.operation.method == "PUT" }
        val missingInSpecOperation = reportOperations.single { it.operation.path == "/unknown" }

        assertThat(coveredOperation.coverageStatus).isEqualTo(CoverageStatus.COVERED)
        assertThat(coveredOperation.omittedStatus).isEqualTo(OmittedStatus.NONE)
        assertThat(coveredOperation.metrics?.attempts).isEqualTo(1)
        assertThat(coveredOperation.metrics?.matches).isEqualTo(1)

        assertThat(unusedOperation.coverageStatus).isEqualTo(CoverageStatus.NOT_USED)
        assertThat(unusedOperation.omittedStatus).isEqualTo(OmittedStatus.SKIPPED)
        assertThat(unusedOperation.metrics?.attempts).isEqualTo(0)
        assertThat(unusedOperation.metrics?.matches).isEqualTo(0)

        assertThat(mismatchOperation.coverageStatus).isEqualTo(CoverageStatus.MISMATCH)
        assertThat(mismatchOperation.omittedStatus).isEqualTo(OmittedStatus.NONE)
        assertThat(mismatchOperation.metrics?.attempts).isEqualTo(1)
        assertThat(mismatchOperation.metrics?.matches).isEqualTo(0)

        assertThat(missingInSpecOperation.coverageStatus).isEqualTo(CoverageStatus.MISSING_IN_SPEC)
        assertThat(missingInSpecOperation.omittedStatus).isEqualTo(OmittedStatus.NONE)
        assertThat(missingInSpecOperation.metrics?.attempts).isEqualTo(1)
        assertThat(missingInSpecOperation.metrics?.matches).isEqualTo(0)
    }

    @Test
    fun `should not duplicate parameterized spec operation when test path is normalized`() {
        val parameterizedEndpoint = endpoint("/orders/(id:number)", "GET", null, 200, "application/json")
        val normalizedTestRecord = testResultRecord(
            operation = OpenAPIOperation(
                path = "/orders/{id}",
                method = "GET",
                contentType = null,
                responseCode = 200,
                protocol = SpecmaticProtocol.HTTP,
                responseContentType = "application/json",
            ),
            actualResponseStatus = 200,
            actualResponseContentType = "application/json",
        )

        val context = MockUsageContext(
            tests = listOf(normalizedTestRecord),
            allSpecEndpoints = listOf(parameterizedEndpoint),
        )

        val reportOperations = reportGenerator.generate(context)

        assertThat(reportOperations).hasSize(1)
        assertThat(reportOperations.single().tests).hasSize(1)
        assertThat(reportOperations.single().operation.path).isEqualTo("/orders/{id}")
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
