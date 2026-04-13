package io.specmatic.test.utils

import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.ctrf.model.CoverageReportOperation
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.API
import io.specmatic.test.TestResultRecord
import io.specmatic.test.reports.coverage.CoverageContext
import io.specmatic.test.reports.coverage.Endpoint
import io.specmatic.test.reports.coverage.OpenApiCoverage
import io.specmatic.test.reports.coverage.toOpenApiOperation

class CoverageBuilder {
    private var filterExpr = ""
    private var endpointsApiFlag = false
    private val applicationApis = mutableListOf<API>()
    private val excludedPaths = mutableListOf<String>()
    private val allSpecEndpoints = mutableListOf<Endpoint>()
    private val inScopeEndpoints = mutableListOf<Endpoint>()
    private val testRecords = mutableListOf<TestResultRecord>()
    private var assertBlocks: MutableList<(CoverageContextView.() -> Unit)> = mutableListOf()

    fun inScope(
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

    fun observed(
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
        val ep = endpoint(method, path, requestType, responseCode, responseType)
        testRecords += testRecord(
            result = result, isWip = isWip,
            operation = ep.toOpenApiOperation(),
            actualResponseStatus = actualResponseCode,
            actualResponseContentType = actualResponseType,
        )
    }

    fun applicationApi(method: String, path: String) {
        applicationApis += API(method = method, path = path)
        endpointsApiFlag = true
    }

    fun filter(expression: String) {
        filterExpr = expression
    }

    fun verify(block: CoverageContextView.() -> Unit) {
        assertBlocks.add(block)
    }

    private fun run() {
        val coverage = OpenApiCoverage(filterExpression = filterExpr)
        coverage.addEndpoints(allEndpoints = allSpecEndpoints, filteredEndpoints = inScopeEndpoints)
        testRecords.forEach(coverage::addTestReportRecords)
        if (applicationApis.isNotEmpty()) coverage.addAPIs(applicationApis)
        if (excludedPaths.isNotEmpty()) coverage.addExcludedAPIs(excludedPaths)
        coverage.setEndpointsAPIFlag(endpointsApiFlag)
        val context = coverage.coverageContext()
        val report = coverage.generate()
        assertBlocks.forEach { it.invoke(CoverageContextView(context, report)) }
    }

    companion object {
        fun coverage(block: CoverageBuilder.() -> Unit) {
            CoverageBuilder().apply(block).run()
        }
    }
}

class CoverageContextView(val context: CoverageContext, val report: List<CoverageReportOperation>) {
    constructor(openApiCoverage: OpenApiCoverage): this(openApiCoverage.coverageContext(), openApiCoverage.generate())
    val operations: List<CoverageOperationView> = report.map(::CoverageOperationView)

    fun matching(method: String, path: String, responseCode: Int? = null, requestType: String? = null, responseType: String? = null): List<CoverageOperationView> {
        return operations.filter { op ->
            op.operation.method.equals(method, ignoreCase = true) &&
            op.operation.path == path &&
            (responseType == null || op.operation.responseContentType == responseType) &&
            (responseCode == null || op.operation.responseCode == responseCode) &&
            (requestType == null || op.operation.contentType == requestType)
        }
    }
}

class CoverageOperationView(val report: CoverageReportOperation) {
    val operation: OpenAPIOperation = report.operation as? OpenAPIOperation ?: error("Expected OpenAPIOperation but found ${report.operation::class.simpleName}")
    val tests: List<TestResultRecord> = report.tests.map { test ->
        test as? TestResultRecord ?: error("Expected TestResultRecord but found ${test::class.simpleName}")
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
