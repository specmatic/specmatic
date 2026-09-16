package io.specmatic.stub.report

import io.specmatic.core.HttpResponse
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.ctrf.model.CoverageReportOperation
import io.specmatic.reporter.ctrf.model.CtrfOperationMetrics
import io.specmatic.reporter.ctrf.model.CtrfOperationQualifiers
import io.specmatic.reporter.ctrf.model.CtrfTestQualifiers
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.internal.dto.coverage.OmittedStatus
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.TestResultRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MockUsageReportGeneratorTest {
    private val reportGenerator = MockUsageReportGenerator()

    @Nested
    inner class TerminationScenarios {
        @Test
        fun `should use non-terminated records for mixed coverage metadata`() {
            val endpoint = endpoint("/orders", "POST", "application/json", 201, "application/json")
            val operation = endpoint.toOpenApiOperation()
            val normalRecord = testResultRecord(
                operation = operation,
                actualResponseStatus = 201,
                actualResponseContentType = "application/json",
            )

            val firstTerminatedRecord = testResultRecord(
                operation = operation,
                actualResponseStatus = 0,
                connectionTerminated = true,
                actualResponseContentType = null,
            )

            val secondTerminatedRecord = testResultRecord(
                operation = operation,
                actualResponseStatus = 0,
                connectionTerminated = true,
                actualResponseContentType = null,
            )

            val records = listOf(normalRecord, firstTerminatedRecord, secondTerminatedRecord)
            val reportOperation = reportOperationFor(endpoint, records)

            assertThat(reportOperation.tests).isEqualTo(records)
            assertThat(reportOperation.eligibleForCoverage).isTrue()
            assertThat(reportOperation.omittedStatus).isEqualTo(OmittedStatus.NONE)
            assertThat(reportOperation.coverageStatus).isEqualTo(CoverageStatus.COVERED)
            assertThat(reportOperation.metrics).isEqualTo(CtrfOperationMetrics(attempts = 1, matches = 1))
            assertThat(reportOperation.qualifiers).isEqualTo(emptyList<CtrfOperationQualifiers>())
            assertThat(reportOperation.tests.flatMap { it.extraFields().qualifiers.orEmpty() }).isEqualTo(
                listOf(CtrfTestQualifiers.TERMINATED, CtrfTestQualifiers.TERMINATED)
            )
        }

        @Test
        fun `should report terminated-only operation as not used with test qualifiers`() {
            val endpoint = endpoint("/orders", "POST", "application/json", 201, "application/json")
            val operation = endpoint.toOpenApiOperation()
            val firstTerminatedRecord = testResultRecord(
                operation = operation,
                actualResponseStatus = 0,
                connectionTerminated = true,
                actualResponseContentType = null,
            )

            val secondTerminatedRecord = testResultRecord(
                operation = operation,
                actualResponseStatus = 0,
                connectionTerminated = true,
                actualResponseContentType = null,
            )

            val records = listOf(firstTerminatedRecord, secondTerminatedRecord)
            val reportOperation = reportOperationFor(endpoint, records)

            assertThat(reportOperation.tests).isEqualTo(records)
            assertThat(reportOperation.eligibleForCoverage).isTrue()
            assertThat(reportOperation.omittedStatus).isEqualTo(OmittedStatus.SKIPPED)
            assertThat(reportOperation.coverageStatus).isEqualTo(CoverageStatus.NOT_USED)
            assertThat(reportOperation.metrics).isEqualTo(CtrfOperationMetrics(attempts = 0, matches = 0))
            assertThat(reportOperation.qualifiers).isEqualTo(emptyList<CtrfOperationQualifiers>())
            assertThat(reportOperation.tests.flatMap { it.extraFields().qualifiers.orEmpty() }).isEqualTo(
                listOf(CtrfTestQualifiers.TERMINATED, CtrfTestQualifiers.TERMINATED)
            )
        }

        @Test
        fun `should use normal-only operation for coverage metadata without qualifiers`() {
            val endpoint = endpoint("/orders", "POST", "application/json", 201, "application/json")
            val normalRecord = testResultRecord(
                actualResponseStatus = 201,
                operation = endpoint.toOpenApiOperation(),
                actualResponseContentType = "application/json",
            )

            val reportOperation = reportOperationFor(endpoint, listOf(normalRecord))

            assertThat(reportOperation.eligibleForCoverage).isTrue()
            assertThat(reportOperation.tests).isEqualTo(listOf(normalRecord))
            assertThat(reportOperation.omittedStatus).isEqualTo(OmittedStatus.NONE)
            assertThat(reportOperation.coverageStatus).isEqualTo(CoverageStatus.COVERED)
            assertThat(reportOperation.tests.flatMap { it.extraFields().qualifiers.orEmpty() }).isEmpty()
            assertThat(reportOperation.qualifiers).isEqualTo(emptyList<CtrfOperationQualifiers>())
            assertThat(reportOperation.metrics).isEqualTo(CtrfOperationMetrics(attempts = 1, matches = 1))
        }
    }

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
        assertThat(coveredOperation.tests).isEqualTo(listOf(coveredRecord))
        assertThat(coveredOperation.metrics).isEqualTo(CtrfOperationMetrics(attempts = 1, matches = 1))
        assertThat(coveredOperation.qualifiers).isEqualTo(emptyList<CtrfOperationQualifiers>())
        assertThat(coveredOperation.eligibleForCoverage).isTrue()
        assertThat(coveredOperation.omittedStatus).isEqualTo(OmittedStatus.NONE)

        assertThat(unusedOperation.coverageStatus).isEqualTo(CoverageStatus.NOT_USED)
        assertThat(unusedOperation.tests).isEqualTo(emptyList<TestResultRecord>())
        assertThat(unusedOperation.metrics).isEqualTo(CtrfOperationMetrics(attempts = 0, matches = 0))
        assertThat(unusedOperation.qualifiers).isEqualTo(emptyList<CtrfOperationQualifiers>())
        assertThat(unusedOperation.eligibleForCoverage).isTrue()
        assertThat(unusedOperation.omittedStatus).isEqualTo(OmittedStatus.SKIPPED)

        assertThat(mismatchOperation.coverageStatus).isEqualTo(CoverageStatus.MISMATCH)
        assertThat(mismatchOperation.tests).isEqualTo(listOf(mismatchRecord))
        assertThat(mismatchOperation.metrics).isEqualTo(CtrfOperationMetrics(attempts = 1, matches = 0))
        assertThat(mismatchOperation.qualifiers).isEqualTo(emptyList<CtrfOperationQualifiers>())
        assertThat(mismatchOperation.eligibleForCoverage).isTrue()
        assertThat(mismatchOperation.omittedStatus).isEqualTo(OmittedStatus.NONE)

        assertThat(missingInSpecOperation.coverageStatus).isEqualTo(CoverageStatus.MISSING_IN_SPEC)
        assertThat(missingInSpecOperation.tests).isEqualTo(listOf(missingInSpecRecord))
        assertThat(missingInSpecOperation.metrics).isEqualTo(CtrfOperationMetrics(attempts = 1, matches = 0))
        assertThat(missingInSpecOperation.qualifiers).isEqualTo(emptyList<CtrfOperationQualifiers>())
        assertThat(missingInSpecOperation.eligibleForCoverage).isFalse()
        assertThat(missingInSpecOperation.omittedStatus).isEqualTo(OmittedStatus.NONE)
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

    private fun reportOperationFor(endpoint: StubEndpoint, testResultRecords: List<TestResultRecord>): CoverageReportOperation<OpenAPIOperation, TestResultRecord> {
        val context = MockUsageContext(tests = testResultRecords, allSpecEndpoints = listOf(endpoint))
        return reportGenerator.generate(context = context).single()
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
        connectionTerminated: Boolean = false,
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
        connectionTerminated = connectionTerminated,
        actualResponseContentType = actualResponseContentType,
        specType = SpecType.OPENAPI,
        requestContentType = operation.contentType,
        operations = setOf(operation),
        specification = "specs/openapi.yaml",
        testType = TestResultRecord.STUB_TEST_TYPE,
    )
}
