package io.specmatic.test.utils

import io.specmatic.core.DefaultStrategies
import io.specmatic.core.Feature
import io.specmatic.core.HttpHeadersPattern
import io.specmatic.core.HttpPathPattern
import io.specmatic.core.HttpRequestPattern
import io.specmatic.core.HttpResponsePattern
import io.specmatic.core.Scenario
import io.specmatic.core.ScenarioInfo
import io.specmatic.core.utilities.Decision
import io.specmatic.core.utilities.Reasoning
import io.specmatic.core.utilities.mapValue
import io.specmatic.reporter.ctrf.model.CtrfOperationMetrics
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.API
import io.specmatic.test.ContractTest
import io.specmatic.test.ScenarioAsTest
import io.specmatic.test.TestResultRecord
import io.specmatic.test.reports.TestReportListener
import io.specmatic.test.reports.coverage.Endpoint
import io.specmatic.test.reports.coverage.OpenApiCoverage
import io.specmatic.test.reports.coverage.OpenApiCoverageReport
import io.specmatic.test.reports.coverage.OpenApiCoverageReportOperation
import io.specmatic.test.reports.coverage.console.OpenAPICoverageConsoleReport
import io.specmatic.test.reports.coverage.console.OpenApiCoverageConsoleRow
import io.specmatic.test.reports.coverage.toOpenApiOperationOrNull
import org.assertj.core.api.Assertions.assertThat

class OpenApiCoverageBuilder {
    private var configFilePath = ""
    private var filterExpr = ""
    private var endpointsApiFlag = false
    private val applicationApis = mutableListOf<API>()
    private val excludedPaths = mutableListOf<String>()
    private val allSpecEndpoints = mutableListOf<Endpoint>()
    private val inScopeEndpoints = mutableListOf<Endpoint>()
    private val testRecords = mutableListOf<TestResultRecord>()
    private val previousCoverageMetrics = mutableMapOf<OpenAPIOperation, CtrfOperationMetrics>()
    private val decisions = mutableListOf<Decision<ContractTest, Scenario>>()
    private val listeners = mutableListOf<TestReportListener>()

    fun configFilePath(path: String) {
        configFilePath = path
    }

    fun addListener(listener: TestReportListener) {
        listeners += listener
    }

    fun applicationApi(method: String, path: String) {
        applicationApis += API(method = method, path = path)
        endpointsApiFlag = true
    }

    fun applicationApisUnavailable() {
        endpointsApiFlag = false
    }

    fun setEndpointsAPIFlag(isSet: Boolean) {
        endpointsApiFlag = isSet
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
        specification: String = "specs/openapi.yaml",
        sourceProvider: String? = null,
        sourceRepository: String? = null,
        sourceRepositoryBranch: String? = null,
        specType: SpecType = SpecType.OPENAPI,
        protocol: SpecmaticProtocol = SpecmaticProtocol.HTTP,
    ) {
        specEndpoint(endpoint(method, path, requestType, responseCode, responseType, specification, sourceProvider, sourceRepository, sourceRepositoryBranch, specType, protocol))
    }

    fun specEndpoint(endpoint: Endpoint) {
        allSpecEndpoints += endpoint
        inScopeEndpoints += endpoint
    }

    fun outOfScopeSpecEndpoint(
        method: String,
        path: String,
        responseCode: Int,
        requestType: String? = null,
        responseType: String? = null,
        specification: String = "specs/openapi.yaml",
        sourceProvider: String? = null,
        sourceRepository: String? = null,
        sourceRepositoryBranch: String? = null,
        specType: SpecType = SpecType.OPENAPI,
        protocol: SpecmaticProtocol = SpecmaticProtocol.HTTP,
    ) {
        endpoint(method, path, requestType, responseCode, responseType, specification, sourceProvider, sourceRepository, sourceRepositoryBranch, specType, protocol).also {
            allSpecEndpoints += it
        }
    }

    fun outOfScopeSpecEndpoint(endpoint: Endpoint) {
        allSpecEndpoints += endpoint
    }

    fun decisionExecute(
        path: String,
        method: String,
        responseCode: Int,
        requestType: String? = null,
        responseType: String? = null,
        reasoning: Reasoning,
        protocol: SpecmaticProtocol = SpecmaticProtocol.HTTP,
        specType: SpecType = SpecType.OPENAPI,
    ) {
        val scenario = Scenario(
            ScenarioInfo(
                protocol = protocol,
                specType = specType,
                httpRequestPattern = HttpRequestPattern(httpPathPattern = HttpPathPattern.from(path), method = method, headersPattern = HttpHeadersPattern(contentType = requestType)),
                httpResponsePattern = HttpResponsePattern(status = responseCode, headersPattern = HttpHeadersPattern(contentType = responseType)),
            )
        )

        val feature = Feature(name = "test", scenarios = listOf(scenario), protocol = protocol)
        val contractTest = ScenarioAsTest(feature = feature, scenario = scenario, protocol = protocol, specType = specType, flagsBased = DefaultStrategies, originalScenario = scenario)
        decisions += Decision.Execute(contractTest, scenario, reasoning)
    }

    fun decisionSkip(
        path: String,
        method: String,
        responseCode: Int,
        requestType: String? = null,
        responseType: String? = null,
        reasoning: Reasoning,
        protocol: SpecmaticProtocol = SpecmaticProtocol.HTTP,
        specType: SpecType = SpecType.OPENAPI,
    ) {
        val scenario = Scenario(
            ScenarioInfo(
                protocol = protocol,
                specType = specType,
                httpRequestPattern = HttpRequestPattern(httpPathPattern = HttpPathPattern.from(path), method = method, headersPattern = HttpHeadersPattern(contentType = requestType)),
                httpResponsePattern = HttpResponsePattern(status = responseCode, headersPattern = HttpHeadersPattern(contentType = responseType)),
            )
        )
        decisions += Decision.Skip(scenario, reasoning)
    }

    fun testResult(
        path: String,
        method: String,
        responseCode: Int,
        result: TestResult,
        requestType: String? = null,
        responseType: String? = null,
        specification: String = "specs/openapi.yaml",
        sourceProvider: String? = null,
        sourceRepository: String? = null,
        sourceRepositoryBranch: String? = null,
        isWip: Boolean = false,
        specType: SpecType = SpecType.OPENAPI,
        protocol: SpecmaticProtocol = SpecmaticProtocol.HTTP,
        actualResponseCode: Int = responseCode,
        actualResponseType: String? = responseType,
    ) {
        val endpoint = endpoint(
            method = method,
            path = path,
            requestType = requestType,
            responseCode = responseCode,
            responseType = responseType,
            specification = specification,
            sourceProvider = sourceProvider,
            sourceRepository = sourceRepository,
            sourceRepositoryBranch = sourceRepositoryBranch,
            specType = specType,
            protocol = protocol
        )

        testRecords += testRecord(
            operation = endpoint.toOpenApiOperation(),
            actualResponseStatus = actualResponseCode,
            actualResponseContentType = actualResponseType,
            result = result,
            specification = specification,
            sourceProvider = sourceProvider,
            sourceRepository = sourceRepository,
            sourceRepositoryBranch = sourceRepositoryBranch,
            isWip = isWip,
            specType = specType,
            protocol = protocol
        )
    }

    fun testResult(record: TestResultRecord) {
        testRecords += record.normalizedForDsl()
    }

    fun previousTestResult(record: TestResultRecord) {
        val normalized = record.normalizedForDsl()
        val operation = normalized.toOpenApiOperationOrNull() ?: return
        val existing = previousCoverageMetrics[operation] ?: CtrfOperationMetrics(matches = 0, attempts = 0)
        previousCoverageMetrics[operation] = existing.copy(
            attempts = existing.attempts + 1,
            matches = existing.matches + if (normalized.matchesResponseIdentifiers) 1 else 0,
        )
    }

    private fun build(): OpenApiCoverage {
        val coverage = OpenApiCoverage(
            filterExpression = filterExpr,
            configFilePath = configFilePath,
            coverageHooks = listeners,
            previousRunCoverageMetrics = previousCoverageMetrics
        )
        coverage.addEndpoints(allEndpoints = allSpecEndpoints, filteredEndpoints = inScopeEndpoints)
        testRecords.forEach(coverage::addTestReportRecords)
        decisions.forEach { decision -> coverage.onContractTestDecision(decision.mapValue { it to "" }) }
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

private fun TestResultRecord.normalizedForDsl(): TestResultRecord {
    val normalizedSpecification = specification ?: "specs/openapi.yaml"
    val normalizedActualStatus = if (result == TestResult.Success && actualResponseStatus == 0) responseStatus else actualResponseStatus
    return copy(specification = normalizedSpecification, actualResponseStatus = normalizedActualStatus)
}

class OpenApiCoverageVerifier(val report: OpenApiCoverageReport) {
    constructor(openApiCoverage: OpenApiCoverage): this(openApiCoverage.generate())
    val consoleReport: OpenAPICoverageConsoleReport by lazy { report.toConsoleReport() }
    val totalOperations: Int get() = consoleReport.totalOperations
    val missedOperations: Int get() = consoleReport.missedOperations
    val notImplementedOperations: Int get() = consoleReport.notImplementedOperations
    val operations: List<OpenApiCoverageOperationVerifier> by lazy(LazyThreadSafetyMode.NONE) {
        report.coverageOperations.map(::OpenApiCoverageOperationVerifier)
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

    fun row(
        method: String,
        path: String,
        responseCode: Int,
        requestType: String? = null,
        responseType: String? = null,
    ): OpenApiCoverageConsoleRow {
        return consoleReport.coverageRows.single {
            it.method.equals(method, ignoreCase = true) &&
            it.path == path &&
            it.responseStatus == responseCode.toString() &&
            (requestType == null || it.requestContentType == requestType) &&
            (responseType == null || it.responseContentType == responseType)
        }
    }

    fun assertRow(
        method: String,
        path: String,
        responseCode: Int,
        count: Int,
        coveragePercentage: Int,
        remarks: CoverageStatus? = null,
        requestType: String? = null,
        responseType: String? = null,
    ) {
        val row = row(method = method, path = path, responseCode = responseCode, requestType = requestType, responseType = responseType)
        assertThat(row.exercisedCount).isEqualTo(count)
        assertThat(row.coveragePercentage).isEqualTo(coveragePercentage)
        if (remarks != null) assertThat(row.remarks).isEqualTo(remarks)
    }

    class OpenApiCoverageOperationVerifier(val operation: OpenApiCoverageReportOperation) {
        val apiOperation: OpenAPIOperation = operation.operation
        val tests: List<TestResultRecord> = operation.tests
    }

    companion object {
        fun OpenApiCoverageReport.verify(block: OpenApiCoverageVerifier.() -> Unit): OpenApiCoverageVerifier {
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
    specification: String = "specs/openapi.yaml",
    sourceProvider: String? = null,
    sourceRepository: String? = null,
    sourceRepositoryBranch: String? = null,
    specType: SpecType = SpecType.OPENAPI,
    protocol: SpecmaticProtocol = SpecmaticProtocol.HTTP,
) = Endpoint(
    path = path,
    method = method,
    specType = specType,
    protocol = protocol,
    responseStatus = responseCode,
    requestContentType = requestType,
    responseContentType = responseType,
    specification = specification,
    sourceProvider = sourceProvider,
    sourceRepository = sourceRepository,
    sourceRepositoryBranch = sourceRepositoryBranch,
)

internal fun testRecord(
    result: TestResult,
    operation: OpenAPIOperation,
    actualResponseStatus: Int,
    specification: String = "specs/openapi.yaml",
    sourceProvider: String? = null,
    sourceRepository: String? = null,
    sourceRepositoryBranch: String? = null,
    isWip: Boolean = false,
    specType: SpecType = SpecType.OPENAPI,
    actualResponseContentType: String? = null,
    protocol: SpecmaticProtocol = SpecmaticProtocol.HTTP,
) = TestResultRecord(
    isWip = isWip,
    request = null,
    response = null,
    result = result,
    specType = specType,
    protocol = protocol,
    path = operation.path,
    method = operation.method,
    specification = specification,
    sourceProvider = sourceProvider,
    repository = sourceRepository,
    branch = sourceRepositoryBranch,
    isGherkin = specType == SpecType.WSDL,
    responseStatus = operation.responseCode,
    requestContentType = operation.contentType,
    actualResponseStatus = actualResponseStatus,
    responseContentType = operation.responseContentType,
    actualResponseContentType = actualResponseContentType,
)
