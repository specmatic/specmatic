package io.specmatic.test.reports.coverage

import io.specmatic.core.HttpResponse
import io.specmatic.core.Scenario
import io.specmatic.core.utilities.Decision
import io.specmatic.core.utilities.Reasoning
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.API
import io.specmatic.test.TestSkipReason
import io.specmatic.test.TestResultRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CoverageContextTest {
    @Test
    fun `should return spec, test derived and missing in spec operations`() {
        val specEndpoint = endpoint(
            path = "/orders",
            method = "POST",
            requestContentType = "application/json",
            responseStatus = 200,
            responseContentType = "application/json",
        )
        val testDerivedRecord = TestResultRecord(
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
            tests = listOf(testDerivedRecord),
            allSpecEndpoints = listOf(specEndpoint),
            applicationEndpoints = listOf(API(method = "POST", path = "/payments")),
            endpointsApiAvailable = true,
        )

        val allCoverageOperations = context.allCoverageOperations()

        assertThat(allCoverageOperations).containsExactlyInAnyOrder(
            specEndpoint.toOpenApiOperation(),
            OpenAPIOperation(
                path = "/orders",
                method = "POST",
                contentType = "application/json",
                responseCode = 400,
                protocol = SpecmaticProtocol.HTTP,
                responseContentType = "application/json",
            ),
            OpenAPIOperation(
                path = "/payments",
                method = "POST",
                contentType = null,
                responseCode = 0,
                protocol = SpecmaticProtocol.HTTP,
                responseContentType = null,
            )
        )
    }

    @Test
    fun `should exclude filtered application endpoints from missing in spec operations`() {
        val context = CoverageContext(
            tests = emptyList(),
            allSpecEndpoints = listOf(endpoint("/orders", "GET", null, 200, "application/json")),
            applicationEndpoints = emptyList(),
            endpointsApiAvailable = true,
        )

        val allCoverageOperations = context.allCoverageOperations()

        assertThat(allCoverageOperations).containsExactly(endpoint("/orders", "GET", null, 200, "application/json").toOpenApiOperation())
    }

    @Test
    fun `should return skip decisions for matching operation`() {
        val endpoint = endpoint("/orders", "GET", null, 200, "application/json")
        val excluded = Reasoning(mainReason = TestSkipReason.EXCLUDED)
        val examplesRequired = Reasoning(mainReason = TestSkipReason.EXAMPLES_REQUIRED)
        val context = CoverageContext(
            tests = emptyList(),
            allSpecEndpoints = listOf(endpoint),
            decisions = mapOf(endpoint to listOf(skipDecision(excluded), skipDecision(examplesRequired)))
        )

        val skipDecisions = context.getSkipDecisionsFor(endpoint.toOpenApiOperation())
        assertThat(skipDecisions).containsExactly(excluded, examplesRequired)
    }

    @Suppress("UNCHECKED_CAST")
    private fun skipDecision(reasoning: Reasoning): Decision<Scenario, OpenAPIOperation> {
        return Decision.Skip(context = Any(), reasoning = reasoning) as Decision<Scenario, OpenAPIOperation>
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
}
