package io.specmatic.test.reports.coverage

import io.specmatic.core.HttpResponse
import io.specmatic.core.Scenario
import io.specmatic.core.utilities.Decision
import io.specmatic.core.utilities.Reasoning
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.API
import io.specmatic.test.ContractTest
import io.specmatic.test.TestExecutionReason
import io.specmatic.test.TestSkipReason
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
            applicationEndpoints = listOf(API(method = "POST", path = "/payments")),
            endpointsApiAvailable = true,
        )

        val reportOperations = reportGenerator.generateReportOperations(context)
        assertThat(reportOperations.single { it.operation.responseCode == 200 && it.operation.method == "POST" }.coverageStatus).isEqualTo(CoverageStatus.COVERED)
        assertThat(reportOperations.single { it.operation.method == "GET" }.coverageStatus).isEqualTo(CoverageStatus.NOT_TESTED)
        assertThat(reportOperations.single { it.operation.method == "PUT" }.coverageStatus).isEqualTo(CoverageStatus.NOT_IMPLEMENTED)

        val missingInSpecFromTest = reportOperations.single { it.operation.path == "/orders" && it.operation.responseCode == 400 }
        assertThat(missingInSpecFromTest.coverageStatus).isEqualTo(CoverageStatus.MISSING_IN_SPEC)
        assertThat(missingInSpecFromTest.specConfig.specification).isEqualTo("specs/openapi.yaml")

        val missingInSpecFromApplicationEndpoint = reportOperations.single { it.operation.path == "/payments" }
        assertThat(missingInSpecFromApplicationEndpoint.coverageStatus).isEqualTo(CoverageStatus.MISSING_IN_SPEC)
        assertThat(missingInSpecFromApplicationEndpoint.specConfig.specification).isEqualTo("specs/openapi.yaml")
    }

    @Test
    fun `should include skip reasons in report operation and mark ineligible for coverage when excluded reason exists`() {
        val endpoint = endpoint("/orders", "GET", null, 200, "application/json")
        val reasoningWithExcluded = Reasoning(mainReason = TestSkipReason.EXCLUDED, otherReasons = listOf(TestSkipReason.EXAMPLES_REQUIRED))
        val reasoningWithoutExcluded = Reasoning(mainReason = TestSkipReason.EXAMPLES_REQUIRED)

        val context = CoverageContext(
            tests = emptyList(),
            allSpecEndpoints = listOf(endpoint),
            decisions = mapOf(endpoint to listOf(skipDecision(reasoningWithExcluded), skipDecision(reasoningWithoutExcluded)))
        )

        val operation = reportGenerator.generateReportOperations(context).single()
        assertThat(operation.reasons.map { it.id }).containsExactly(TestSkipReason.EXCLUDED.id, TestSkipReason.EXAMPLES_REQUIRED.id, TestSkipReason.EXAMPLES_REQUIRED.id)
        assertThat(operation.eligibleForCoverage).isFalse()
    }

    @Test
    fun `should keep operation eligible for coverage when skip reasons do not contain excluded`() {
        val endpoint = endpoint("/orders", "GET", null, 200, "application/json")
        val context = CoverageContext(
            tests = emptyList(),
            allSpecEndpoints = listOf(endpoint),
            decisions = mapOf(endpoint to listOf(skipDecision(Reasoning(mainReason = TestExecutionReason.NO_EXAMPLE))))
        )

        val operation = reportGenerator.generateReportOperations(context).single()
        assertThat(operation.reasons.map { it.id }).containsExactly(TestExecutionReason.NO_EXAMPLE.id)
        assertThat(operation.eligibleForCoverage).isTrue()
    }

    @Suppress("UNCHECKED_CAST")
    private fun skipDecision(reasoning: Reasoning): Decision<ContractTest, Scenario> {
        return Decision.Skip(context = Any(), reasoning = reasoning) as Decision<ContractTest, Scenario>
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
