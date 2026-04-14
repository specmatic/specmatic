package io.specmatic.test.utils

import io.specmatic.core.report.OpenApiCoverageReportOperation
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.API
import io.specmatic.test.TestResultRecord
import io.specmatic.test.reports.coverage.Endpoint
import io.specmatic.test.reports.coverage.OpenApiCoverage
import io.specmatic.test.reports.coverage.toOpenApiOperation

class OpenApiCoverageBuilder {
    private var filterExpr = ""
    private var endpointsApiFlag = false
    private val applicationApis = mutableListOf<API>()
    private val excludedPaths = mutableListOf<String>()
    private val allSpecEndpoints = mutableListOf<Endpoint>()
    private val inScopeEndpoints = mutableListOf<Endpoint>()
    private val testRecords = mutableListOf<TestResultRecord>()

    fun applicationApi(method: String, path: String) {
        applicationApis += API(method = method, path = path)
        endpointsApiFlag = true
    }

    fun applicationApisUnavailable() {
        endpointsApiFlag = false
    }

    fun excludeApplicationPath(path: String) {
        excludedPaths += path
    }

    fun specEndpoint(
        method: String,
        path: String,
        responseCode: Int,
        requestType: String? = null,
        responseType: String? = null,
    ) {
        endpoint(method, path, requestType, responseCode, responseType).also {
            allSpecEndpoints += it
            inScopeEndpoints += it
        }
    }

    fun outOfScopeSpecEndpoint(
        method: String,
        path: String,
        responseCode: Int,
        requestType: String? = null,
        responseType: String? = null,
    ) {
        endpoint(method, path, requestType, responseCode, responseType).also {
            allSpecEndpoints += it
        }
    }

    fun testResult(
        path: String,
        method: String,
        responseCode: Int,
        result: TestResult,
        requestType: String? = null,
        responseType: String? = null,
        actualResponseCode: Int = responseCode,
        actualResponseType: String? = responseType,
        isWip: Boolean = false,
    ) {
        val endpoint = endpoint(method, path, requestType, responseCode, responseType)
        testRecords += testRecord(
            result = result, isWip = isWip,
            operation = endpoint.toOpenApiOperation(),
            actualResponseStatus = actualResponseCode,
            actualResponseContentType = actualResponseType,
        )
    }

    private fun build(): OpenApiCoverage {
        val coverage = OpenApiCoverage(filterExpression = filterExpr)
        coverage.addEndpoints(allEndpoints = allSpecEndpoints, filteredEndpoints = inScopeEndpoints)
        testRecords.forEach(coverage::addTestReportRecords)
        if (applicationApis.isNotEmpty()) coverage.addAPIs(applicationApis)
        if (excludedPaths.isNotEmpty()) coverage.addExcludedAPIs(excludedPaths)
        coverage.setEndpointsAPIFlag(endpointsApiFlag)
        return coverage
    }

    companion object {
        fun buildCoverage(block: OpenApiCoverageBuilder.() -> Unit): OpenApiCoverage {
            return OpenApiCoverageBuilder().apply(block).build()
        }
    }
}

class OpenApiCoverageVerifier(val report: List<OpenApiCoverageReportOperation>) {
    constructor(openApiCoverage: OpenApiCoverage): this(openApiCoverage.generate())
    val operations: List<OpenApiCoverageOperationVerifier> by lazy(LazyThreadSafetyMode.NONE) {
        report.map(::OpenApiCoverageOperationVerifier)
    }

    fun single(method: String, path: String, responseCode: Int? = null, requestType: String? = null, responseType: String? = null): OpenApiCoverageOperationVerifier {
        return operations.single { op ->
            op.apiOperation.path == path &&
            op.apiOperation.method.equals(method, ignoreCase = true) &&
            (requestType == null || op.apiOperation.contentType == requestType) &&
            (responseCode == null || op.apiOperation.responseCode == responseCode) &&
            (responseType == null || op.apiOperation.responseContentType == responseType)
        }
    }

    class OpenApiCoverageOperationVerifier(val operation: OpenApiCoverageReportOperation) {
        val apiOperation: OpenAPIOperation = operation.operation
        val tests: List<TestResultRecord> = operation.tests
    }

    companion object {
        fun List<OpenApiCoverageReportOperation>.verify(block: OpenApiCoverageVerifier.() -> Unit): OpenApiCoverageVerifier {
            return OpenApiCoverageVerifier(this).apply(block)
        }
    }
}

internal fun endpoint(
    method: String,
    path: String,
    requestType: String? = null,
    responseCode: Int,
    responseType: String? = null,
) = Endpoint(
    path = path,
    method = method,
    specType = SpecType.OPENAPI,
    responseStatus = responseCode,
    requestContentType = requestType,
    protocol = SpecmaticProtocol.HTTP,
    responseContentType = responseType,
    specification = "specs/openapi.yaml",
)

internal fun testRecord(
    result: TestResult,
    operation: OpenAPIOperation,
    actualResponseStatus: Int,
    actualResponseContentType: String? = null,
    isWip: Boolean = false
) = TestResultRecord(
    isWip = isWip,
    request = null,
    response = null,
    result = result,
    path = operation.path,
    method = operation.method,
    specType = SpecType.OPENAPI,
    specification = "specs/openapi.yaml",
    responseStatus = operation.responseCode,
    requestContentType = operation.contentType,
    actualResponseStatus = actualResponseStatus,
    responseContentType = operation.responseContentType,
    actualResponseContentType = actualResponseContentType,
)
