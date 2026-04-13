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

class OpenApiCoverageFlowTest {
    private val coverage = OpenApiCoverage()

    @Test
    fun `marks an operation covered when attempts and matches are present`() {
        val endpoint = endpoint(
            path = "/orders",
            method = "POST",
            requestContentType = "application/json",
            responseStatus = 200,
            responseContentType = "application/json",
        )
        val operation = endpoint.toOpenAPIOperation()
        val record = testResultRecord(operation = operation, actualResponseStatus = 200, actualResponseContentType = "application/json")

        coverage.addEndpoints(allEndpoints = listOf(endpoint), filteredEndpoints = listOf(endpoint))
        coverage.addTestReportRecords(record)

        val reportOperations = coverage.generate()

        val reportOperation = reportOperations.single()
        assertThat(reportOperation.operation).isEqualTo(operation)
        assertThat(reportOperation.coverageStatus).isEqualTo(CoverageStatus.COVERED)
        assertThat(reportOperation.tests).containsExactly(record)
    }

    @Test
    fun `marks an operation not implemented when it was attempted but did not match`() {
        val endpoint = endpoint(
            path = "/orders",
            method = "POST",
            requestContentType = "application/json",
            responseStatus = 201,
            responseContentType = "application/json",
        )
        val operation = endpoint.toOpenAPIOperation()
        val record = testResultRecord(
            operation = operation,
            actualResponseStatus = 400,
            actualResponseContentType = "application/json",
        )

        coverage.addEndpoints(allEndpoints = listOf(endpoint), filteredEndpoints = listOf(endpoint))
        coverage.addTestReportRecords(record)

        val reportOperations = coverage.generate()

        val reportOperation = reportOperations.single()
        assertThat(reportOperation.coverageStatus).isEqualTo(CoverageStatus.NOT_IMPLEMENTED)
        assertThat(reportOperation.tests).containsExactly(record)
    }

    @Test
    fun `marks a spec operation not tested when it has no attempts or matches`() {
        val endpoint = endpoint(
            path = "/orders",
            method = "GET",
            requestContentType = null,
            responseStatus = 200,
            responseContentType = "application/json",
        )

        coverage.addEndpoints(allEndpoints = listOf(endpoint), filteredEndpoints = listOf(endpoint))

        val reportOperations = coverage.generate()

        val reportOperation = reportOperations.single()
        assertThat(reportOperation.coverageStatus).isEqualTo(CoverageStatus.NOT_TESTED)
        assertThat(reportOperation.tests).isEmpty()
    }

    @Test
    fun `adds a missing in spec operation from actuator when path and method are absent from the spec`() {
        val endpoint = endpoint(
            path = "/orders",
            method = "GET",
            requestContentType = null,
            responseStatus = 200,
            responseContentType = "application/json",
        )

        coverage.addEndpoints(allEndpoints = listOf(endpoint), filteredEndpoints = listOf(endpoint))
        coverage.setEndpointsAPIFlag(true)
        coverage.addAPIs(
            listOf(
                API(method = "GET", path = "/orders"),
                API(method = "POST", path = "/payments"),
            )
        )

        val reportOperations = coverage.generate()

        assertThat(reportOperations).hasSize(2)
        assertThat(reportOperations.single { it.operation == endpoint.toOpenAPIOperation() }.coverageStatus)
            .isEqualTo(CoverageStatus.NOT_TESTED)

        val missingInSpecReportOperation = reportOperations.single { (it.operation as OpenAPIOperation).path == "/payments" }
        val missingInSpecOperation = missingInSpecReportOperation.operation as OpenAPIOperation
        assertThat(missingInSpecOperation.method).isEqualTo("POST")
        assertThat(missingInSpecOperation.responseCode).isEqualTo(0)
        assertThat(missingInSpecReportOperation.coverageStatus).isEqualTo(CoverageStatus.MISSING_IN_SPEC)
        assertThat(missingInSpecReportOperation.specConfig.specification).isEqualTo("specs/openapi.yaml")
        assertThat(missingInSpecReportOperation.eligibleForCoverage).isFalse()
        assertThat(missingInSpecReportOperation.tests).isEmpty()
    }

    @Test
    fun `includes executed expected operations that are not part of filtered spec endpoints`() {
        val endpoint = endpoint(
            path = "/orders",
            method = "POST",
            requestContentType = "application/json",
            responseStatus = 200,
            responseContentType = "application/json",
        )
        val generatedNegativeTest = TestResultRecord(
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

        coverage.addEndpoints(allEndpoints = listOf(endpoint), filteredEndpoints = listOf(endpoint))
        coverage.addTestReportRecords(generatedNegativeTest)

        val reportOperations = coverage.generate()

        assertThat(reportOperations.map { it.operation as OpenAPIOperation })
            .contains(
                endpoint.toOpenAPIOperation(),
                OpenAPIOperation(
                    path = "/orders",
                    method = "POST",
                    contentType = "application/json",
                    responseCode = 400,
                    protocol = SpecmaticProtocol.HTTP,
                    responseContentType = "application/json",
                )
            )
        assertThat(reportOperations.single { (it.operation as OpenAPIOperation).responseCode == 200 }.coverageStatus)
            .isEqualTo(CoverageStatus.NOT_TESTED)
        assertThat(reportOperations.single { (it.operation as OpenAPIOperation).responseCode == 400 }.coverageStatus)
            .isEqualTo(CoverageStatus.MISSING_IN_SPEC)
    }

    @Test
    fun `builds coverage operations from filtered endpoints and accumulated test records`() {
        val filteredEndpoint = endpoint(
            path = "/orders",
            method = "GET",
            requestContentType = null,
            responseStatus = 200,
            responseContentType = "application/json",
        )
        val excludedEndpoint = endpoint(
            path = "/internal/orders",
            method = "GET",
            requestContentType = null,
            responseStatus = 200,
            responseContentType = "application/json",
        )
        val record = testResultRecord(
            operation = filteredEndpoint.toOpenAPIOperation(),
            actualResponseStatus = 200,
            actualResponseContentType = "application/json",
        )

        coverage.addEndpoints(
            allEndpoints = listOf(filteredEndpoint, excludedEndpoint),
            filteredEndpoints = listOf(filteredEndpoint),
        )
        coverage.addTestReportRecords(record)
        coverage.setEndpointsAPIFlag(true)
        coverage.addAPIs(listOf(API(method = "POST", path = "/payments")))

        val reportOperations = coverage.generate()

        assertThat(reportOperations.map { (it.operation as OpenAPIOperation).path })
            .containsExactlyInAnyOrder("/orders", "/payments")
        assertThat(reportOperations.single { (it.operation as OpenAPIOperation).path == "/orders" }.coverageStatus)
            .isEqualTo(CoverageStatus.COVERED)
        assertThat(reportOperations.single { (it.operation as OpenAPIOperation).path == "/payments" }.coverageStatus)
            .isEqualTo(CoverageStatus.MISSING_IN_SPEC)
    }

    @Test
    fun `does not add missing in spec operations when endpoints api is unavailable`() {
        val endpoint = endpoint(
            path = "/orders",
            method = "GET",
            requestContentType = null,
            responseStatus = 200,
            responseContentType = "application/json",
        )

        coverage.addEndpoints(allEndpoints = listOf(endpoint), filteredEndpoints = listOf(endpoint))
        coverage.addAPIs(listOf(API(method = "POST", path = "/payments")))
        coverage.setEndpointsAPIFlag(false)

        val reportOperations = coverage.generate()

        assertThat(reportOperations).hasSize(1)
        assertThat((reportOperations.single().operation as OpenAPIOperation).path).isEqualTo("/orders")
    }

    @Test
    fun `does not include excluded actuator operations in coverage report`() {
        val endpoint = endpoint(
            path = "/orders",
            method = "GET",
            requestContentType = null,
            responseStatus = 200,
            responseContentType = "application/json",
        )

        coverage.addEndpoints(allEndpoints = listOf(endpoint), filteredEndpoints = listOf(endpoint))
        coverage.addAPIs(listOf(API(method = "POST", path = "/payments")))
        coverage.addExcludedAPIs(listOf("/payments"))
        coverage.setEndpointsAPIFlag(true)

        val reportOperations = coverage.generate()

        assertThat(reportOperations).hasSize(1)
        assertThat((reportOperations.single().operation as OpenAPIOperation).path).isEqualTo("/orders")
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

    private fun Endpoint.toOpenAPIOperation() = OpenAPIOperation(
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
    )
}
