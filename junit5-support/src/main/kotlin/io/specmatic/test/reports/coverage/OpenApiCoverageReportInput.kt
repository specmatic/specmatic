package io.specmatic.test.reports.coverage

import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.Result
import io.specmatic.core.Scenario
import io.specmatic.core.filters.ExpressionStandardizer
import io.specmatic.core.filters.TestRecordFilter
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.core.utilities.Decision
import io.specmatic.core.utilities.Reasoning
import io.specmatic.core.utilities.mapValue
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.generated.dto.coverage.CoverageEntry
import io.specmatic.reporter.generated.dto.coverage.OpenAPICoverageOperation
import io.specmatic.reporter.generated.dto.coverage.SpecmaticCoverageReport
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.API
import io.specmatic.test.ContractTest
import io.specmatic.test.HttpInteractionsLog
import io.specmatic.test.TestResultRecord
import io.specmatic.test.TestResultRecord.Companion.getCoverageStatus
import io.specmatic.test.countsAsCoveredForApiCoverage
import io.specmatic.test.reports.TestReportListener
import io.specmatic.test.reports.coverage.console.OpenAPICoverageConsoleReport
import io.specmatic.test.reports.coverage.console.OpenApiCoverageConsoleRow
import io.specmatic.test.reports.coverage.console.groupRecords
import io.specmatic.test.reports.onEachListener
import io.specmatic.test.reports.onTestResult
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

class OpenApiCoverageReportInput(
    private var configFilePath: String,
    private val testResultRecords: MutableList<TestResultRecord> = mutableListOf(),
    private val applicationAPIs: MutableList<API> = mutableListOf(),
    private val excludedAPIs: MutableList<String> = mutableListOf(),
    private val allEndpoints: MutableList<Endpoint> = mutableListOf(),
    internal var endpointsAPISet: Boolean = false,
    private val filterExpression: String = "",
    private val coverageHooks: List<TestReportListener> = emptyList(),
    private val httpInteractionsLog: HttpInteractionsLog = HttpInteractionsLog(),
    private val previousTestResultRecord: List<TestResultRecord> = emptyList(),
    private val filteredEndpoints: MutableList<Endpoint> = mutableListOf(),
    private val skipDecisions: MutableMap<Endpoint, Reasoning> = mutableMapOf(),
) {
    fun endpoints() = allEndpoints.toList()

    fun missingInSpecEndpoints(): List<Endpoint> {
        return missingInSpecTestResultRecords().map {
            Endpoint(
                path = it.path,
                method = it.method,
                responseStatus =  it.responseStatus,
                sourceProvider = it.sourceProvider,
                sourceRepository = it.repository,
                sourceRepositoryBranch = it.branch,
                protocol = SpecmaticProtocol.HTTP,
                specType = SpecType.OPENAPI,
                specification = it.specification
            )
        }
    }

    fun ctrfSpecConfigs(): List<CtrfSpecConfig> {
        return endpoints()
            .plus(missingInSpecEndpoints())
            .groupBy { it.specification.orEmpty() }
            .flatMap { (_, groupedEndpoints) ->
                groupedEndpoints.map {
                    CtrfSpecConfig(
                        protocol = it.protocol.key,
                        specType = it.specType.value,
                        specification = it.specification.orEmpty(),
                        sourceProvider = it.sourceProvider,
                        repository = it.sourceRepository,
                        branch = it.sourceRepositoryBranch ?: "main"
                    )
                }
            }
    }

    fun onProcessingComplete() = coverageHooks.onEachListener { onEnd() }

    fun onGovernanceResult(result: Result) = coverageHooks.onEachListener { onGovernance(result) }

    fun onTestDecision(decision: Decision<Pair<ContractTest, String>, Scenario>) = coverageHooks.onEachListener { onTestDecision(decision) }

    fun totalDuration(): Long {
        return httpInteractionsLog.totalDuration()
    }

    fun findMatchingScenarios(logMessageSelector: (HttpLogMessage) -> Boolean): List<HttpLogMessage> {
        return httpInteractionsLog.testHttpLogMessages.filter(logMessageSelector)
    }

    fun addTestReportRecords(testResultRecord: TestResultRecord) {
        coverageHooks.onTestResult(testResultRecord, httpInteractionsLog.testHttpLogMessages.toList())
        testResultRecords.add(testResultRecord)
    }

    fun addAPIs(apis: List<API>) {
        applicationAPIs.addAll(apis)
        val filterExpression = ExpressionStandardizer.filterToEvalEx(filterExpression)
        val apisNotExcluded = apis.filter { api ->
            val testResultRecord = TestResultRecord(
                path = api.path,
                method = api.method,
                responseStatus = 0,
                request = null,
                response = null,
                result = TestResult.MissingInSpec,
                specType = SpecType.OPENAPI,
                operations = setOf(OpenAPIOperation(path = api.path, method = api.method, contentType = null, responseCode = 0, protocol = SpecmaticProtocol.HTTP))
            )
            filterExpression.with("context", TestRecordFilter(testResultRecord)).evaluate().booleanValue
        }
        val apisExcluded = apis.toSet().minus(apisNotExcluded.toSet()).toList()
        coverageHooks.onEachListener { onActuatorApis(apisNotExcluded = apisNotExcluded, apisExcluded = apisExcluded) }
    }

    fun addExcludedAPIs(apis: List<String>) {
        excludedAPIs.addAll(apis)
    }

    fun addEndpoints(allEndpoints: List<Endpoint>, filteredEndpoints: List<Endpoint>) {
        this.allEndpoints.addAll(allEndpoints)
        this.filteredEndpoints.addAll(filteredEndpoints)
        val excludedEndpoints = allEndpoints.toSet().minus(filteredEndpoints.toSet()).toList()
        coverageHooks.onEachListener { onEndpointApis(endpointsNotExcluded = filteredEndpoints, endpointsExcluded = excludedEndpoints) }
    }

    fun addSkipReasoning(skipDecision: Decision<Scenario, Scenario>) {
        if (skipDecision !is Decision.Skip) return
        val endpoint = Endpoint(
            path = convertPathParameterStyle(skipDecision.context.path),
            method = skipDecision.context.method,
            responseStatus = skipDecision.context.httpResponsePattern.status,
            soapAction = skipDecision.context.soapActionUnescaped,
            sourceProvider = skipDecision.context.sourceProvider,
            sourceRepository = skipDecision.context.sourceRepository,
            sourceRepositoryBranch = skipDecision.context.sourceRepositoryBranch,
            specification = skipDecision.context.specification,
            requestContentType = skipDecision.context.requestContentType,
            responseContentType = skipDecision.context.responseContentType,
            protocol = skipDecision.context.protocol,
            specType = skipDecision.context.specType
        )
        skipDecisions[endpoint] = skipDecision.reasoning
    }

    fun setEndpointsAPIFlag(isSet: Boolean) {
        coverageHooks.onEachListener { onActuator(isSet) }
        endpointsAPISet = isSet
    }

    fun getApplicationAPIs(): List<API> {
        return applicationAPIs
    }

    fun testResultRecords(): List<TestResultRecord> {
        val testResults = testResultRecords
            .plus(previousTestResultRecord)
            .filter { testResult -> excludedAPIs.none { it == testResult.path } }
            .identifyWipTestsAndUpdateResult()


        val testResultsWithNotImplementedEndpoints =
            identifyFailedTestsDueToUnimplementedEndpointsAddMissingTests(testResults)

        return addTestResultsForMissingEndpoints(testResultsWithNotImplementedEndpoints)
            .addTestResultsForTestsNotGeneratedBySpecmatic(filteredEndpoints)
    }

    fun generate(): OpenAPICoverageConsoleReport {
        return generateCoverageReport(coverageHooks)
    }

    fun generateCoverageReport(listeners: List<TestReportListener> = emptyList()): OpenAPICoverageConsoleReport {
        val allTests = testResultRecords()
        val apiCoverageRows: MutableList<OpenApiCoverageConsoleRow> = mutableListOf()
        val groupedTestResultRecords = allTests.groupRecords()

        // Build rows for each path -> method -> requestContentType -> responseStatus group
        for ((path, methodMap) in groupedTestResultRecords) {
            val rowsForPath = mutableListOf<OpenApiCoverageConsoleRow>()
            val totalCoveragePercentage = calculateTotalCoveragePercentage(methodMap)
            listeners.onEachListener { onPathCoverageCalculated(path, totalCoveragePercentage) }

            for ((method, contentTypeMap) in methodMap) {
                for ((requestContentType, responseCodeMap) in contentTypeMap) {
                    // Sort response status keys numerically when possible, otherwise lexicographically
                    val sortedResponseEntries = responseCodeMap.entries.sortedWith(
                        compareBy(
                            { it.key.toIntOrNull() ?: Int.MAX_VALUE },
                            { it.key }
                        )
                    )

                    for ((responseStatus, testResults) in sortedResponseEntries) {
                        val showPath = rowsForPath.isEmpty()
                        val showMethod = rowsForPath.none { it.method == method }
                        val count = testResults.count { it.isExercised }.toString()
                        val remarks = testResults.getCoverageStatus()

                        rowsForPath.add(
                            OpenApiCoverageConsoleRow(
                                path = path,
                                showPath = showPath,
                                method = method,
                                showMethod = showMethod,
                                requestContentType = requestContentType,
                                responseStatus = responseStatus,
                                count = count,
                                remarks = remarks,
                                coveragePercentage = totalCoveragePercentage,
                            )
                        )
                    }
                }
            }

            apiCoverageRows.addAll(rowsForPath)
        }

        val totalOperations = groupedTestResultRecords.keys.size
        val testsGroupedByOperation = allTests.groupByOperation()

        val missedOperations = testsGroupedByOperation.count { (_, tests) ->
            tests.getCoverageStatus() == CoverageStatus.MISSING_IN_SPEC
        }

        val notImplementedOperations = testsGroupedByOperation.count { (_, tests) ->
            tests.getCoverageStatus() == CoverageStatus.NOT_IMPLEMENTED
        }

        return OpenAPICoverageConsoleReport(
            apiCoverageRows,
            allTests,
            totalOperations,
            missedOperations,
            notImplementedOperations,
            listeners,
        )
    }

    private fun List<TestResultRecord>.addTestResultsForTestsNotGeneratedBySpecmatic(endpoints: List<Endpoint>): List<TestResultRecord> {
        val endpointsWithoutTests =
            endpoints.filter { endpoint ->
                this.none { it.path == endpoint.path && it.method == endpoint.method && it.responseStatus == endpoint.responseStatus }
                        && excludedAPIs.none { it == endpoint.path }
            }
        return this.plus(
            endpointsWithoutTests.map { endpoint ->
                TestResultRecord(
                    path = endpoint.path,
                    method = endpoint.method,
                    requestContentType = endpoint.requestContentType,
                    responseContentType = endpoint.responseContentType,
                    responseStatus = endpoint.responseStatus,
                    request = null,
                    response = null,
                    result = TestResult.NotCovered,
                    sourceProvider = endpoint.sourceProvider,
                    repository = endpoint.sourceRepository,
                    branch = endpoint.sourceRepositoryBranch,
                    specification = endpoint.specification,
                    specType = endpoint.specType,
                    operations = setOf(
                        OpenAPIOperation(
                            path = endpoint.path,
                            method = endpoint.method,
                            contentType = endpoint.requestContentType,
                            responseCode = endpoint.responseStatus,
                            responseContentType = endpoint.responseContentType,
                            protocol = endpoint.protocol
                        )
                    ),
                    reasoning = skipDecisions.getOrDefault(endpoint, Reasoning())
                )
            }
        )
    }

    fun generateJsonReport(): SpecmaticCoverageReport {
        val testResults = testResultRecords.filter { testResult -> excludedAPIs.none { it == testResult.path } }
        val testResultsWithNotImplementedEndpoints =
            identifyFailedTestsDueToUnimplementedEndpointsAddMissingTests(testResults)
        val allTests = addTestResultsForMissingEndpoints(testResultsWithNotImplementedEndpoints)
        return SpecmaticCoverageReport()
            .withSpecmaticConfigPath(configFilePath)
            .withApiCoverage(allTests.toCoverageEntries())
    }

    private fun List<TestResultRecord>.toCoverageEntries(): List<CoverageEntry> {
        return this.groupBy {
            CoverageGroupKey(
                it.sourceProvider,
                it.repository,
                it.branch,
                it.specification,
                it.operations.firstOrNull()?.protocol?.key?.uppercase()
            )
        }.map { (key, recordsOfGroup) ->
            CoverageEntry()
                .withSpecification(key.specification)
                .withType(key.sourceProvider)
                .withRepository(key.sourceRepository)
                .withBranch(key.sourceRepositoryBranch)
                .withServiceType(key.serviceType)
                .withSpecType("OPENAPI")
                .withOperations(recordsOfGroup.toOpenAPICoverageOperations())
        }
    }

    private fun List<TestResultRecord>.toOpenAPICoverageOperations(): List<OpenAPICoverageOperation> {
        return this.groupBy {
            Triple(it.path, it.method, it.responseStatus)
        }.map { (operationGroup, operationRows) ->
            OpenAPICoverageOperation()
                .withPath(operationGroup.first)
                .withMethod(operationGroup.second)
                .withResponseCode(operationGroup.third)
                .withCoverageStatus(operationRows.getCoverageStatus())
                .withCount(operationRows.count { it.isExercised })
        }
    }

    private fun List<TestResultRecord>.groupByOperation(): Map<OperationKey, List<TestResultRecord>> {
        return groupBy {
            OperationKey(
                path = it.path,
                method = it.soapAction ?: it.method,
                requestContentType = it.requestContentType,
                responseStatus = it.responseStatus
            )
        }
    }

    private fun addTestResultsForMissingEndpoints(testResults: List<TestResultRecord>): List<TestResultRecord> {
        if (!endpointsAPISet)
            return testResults

        val projectedFilterExpression = ExpressionStandardizer.filterToEvalExForSupportedKeys(filterExpression) {
            TestRecordFilter.supportsFilterKey(it)
        }

        val filteredMissingApiResults = missingInSpecTestResultRecords().filter { missingTestResult ->
            projectedFilterExpression.with("context", TestRecordFilter(missingTestResult)).evaluate().booleanValue
        }

        return testResults + filteredMissingApiResults
    }

    private fun missingInSpecTestResultRecords(): List<TestResultRecord> {
        return applicationAPIs.filter { api ->
            val noTestResultFoundForThisAPI = allEndpoints.none { it.path == api.path && it.method == api.method }
            val isNotExcluded = api.path !in excludedAPIs
            noTestResultFoundForThisAPI && isNotExcluded
        }.map { api ->
            val closestMatchingEndpoint = closestMatchingEndpointFor(api.path, api.method)
            TestResultRecord(
                path = api.path,
                method = api.method,
                responseStatus = 0,
                request = null,
                response = null,
                result = TestResult.MissingInSpec,
                sourceProvider = closestMatchingEndpoint?.sourceProvider,
                repository = closestMatchingEndpoint?.sourceRepository,
                branch = closestMatchingEndpoint?.sourceRepositoryBranch,
                specType = SpecType.OPENAPI,
                operations = setOf(
                    OpenAPIOperation(
                        path = api.path,
                        method = api.method,
                        contentType = null,
                        responseCode = 0,
                        protocol = SpecmaticProtocol.HTTP
                    )
                ),
                specification = closestMatchingEndpoint?.specification
            )
        }
    }

    private fun closestMatchingEndpointFor(path: String, method: String): Endpoint? {
        val endpointsWithSpecs = allEndpoints.filter { it.specification != null }
        if (endpointsWithSpecs.isEmpty()) {
            return null
        }

        val methodMatchedEndpoints = endpointsWithSpecs.filter { it.method == method }
        val candidateEndpoints = methodMatchedEndpoints.ifEmpty { endpointsWithSpecs }

        return candidateEndpoints
            .maxWithOrNull(
                compareBy<Endpoint> { commonPathPrefixSegments(path, it.path) }
                    .thenBy { normalizedPathSegments(it.path).size }
            )
            ?: endpointsWithSpecs.first()
    }

    private fun commonPathPrefixSegments(leftPath: String, rightPath: String): Int {
        val leftSegments = normalizedPathSegments(leftPath)
        val rightSegments = normalizedPathSegments(rightPath)

        return leftSegments.zip(rightSegments).takeWhile { (left, right) -> left == right }.count()
    }

    private fun normalizedPathSegments(path: String): List<String> {
        return path.trim('/').split('/').filter { it.isNotBlank() }
    }

    private fun calculateTotalCoveragePercentage(methodMap: Map<String, Map<String?, Map<String, List<TestResultRecord>>>>): Int {
        val (totalCount, coveredCount) = calculateCoverageCounts(methodMap)
        return (coveredCount.toFloat() / totalCount * 100).roundToInt()
    }

    private fun calculateCoverageCounts(methodMap: Map<String, Map<String?, Map<String, List<TestResultRecord>>>>): Pair<Int, Int> {
        val responseMaps = methodMap.values.flatMap { it.values }
        val totalResponseGroupCount = responseMaps.sumOf { it.size }
        val coveredResponseGroupCount = responseMaps.sumOf { responseMap ->
            responseMap.values.count { testResults -> testResults.countsAsCoveredForApiCoverage() }
        }

        return totalResponseGroupCount to coveredResponseGroupCount
    }

    private fun identifyFailedTestsDueToUnimplementedEndpointsAddMissingTests(testResults: List<TestResultRecord>): List<TestResultRecord> {
        return testResults.flatMap { testResult ->
            val updated = if (testResult.hasFailedAndEndpointIsNotImplemented())
                testResult.copy(result = TestResult.NotImplemented)
            else
                testResult

            if (updated.testedEndpointIsMissingInSpec())
                createMissingInSpecRecordAndIncludeOriginalRecordIfApplicable(updated)
            else
                listOf(updated)
        }
    }

    private fun createMissingInSpecRecordAndIncludeOriginalRecordIfApplicable(testResult: TestResultRecord): List<TestResultRecord> = listOfNotNull(
        testResult.copy(
            operations = setOf(
                OpenAPIOperation(
                    path = testResult.path,
                    method = testResult.method,
                    contentType = testResult.requestContentType,
                    responseCode = testResult.actualResponseStatus,
                    responseContentType = testResult.responseContentType,
                    protocol = SpecmaticProtocol.HTTP
                )
            ),
            responseStatus = testResult.actualResponseStatus,
            result = TestResult.MissingInSpec,
            actualResponseStatus = testResult.actualResponseStatus
        ),
        testResult.takeIf {
            it.sourceEndpointIsPresentInSpec()
        }
    )

    private fun TestResultRecord.hasFailedAndEndpointIsNotImplemented(): Boolean {
        return this.result == TestResult.Failed && endpointsAPISet &&
                applicationAPIs.isNotEmpty() &&
                applicationAPIs.none {
                    it.path == this.path && it.method == this.method
                }
    }

    private fun TestResultRecord.testedEndpointIsMissingInSpec(): Boolean {
        if (this.isWip) {
            return false
        }

        if (this.result == TestResult.NotImplemented) {
            return false
        }

        val endpointExistsInSpecification = allEndpoints.any {
            it.path == this.path && it.method == this.method && it.responseStatus == this.actualResponseStatus
        }
        return (!this.isConnectionRefused() && !endpointExistsInSpecification)
    }

    private fun TestResultRecord.sourceEndpointIsPresentInSpec(): Boolean {
        return allEndpoints.any {
            it.path == this.path && it.method == this.method && it.responseStatus == this.responseStatus
        }
    }

    private fun List<TestResultRecord>.identifyWipTestsAndUpdateResult(): List<TestResultRecord> {
        val wipTestResults = this.filter { it.scenarioResult?.scenario?.ignoreFailure == true }
        val updatedWipTestResults = wipTestResults.map { it.copy(isWip = true) }
        return this.minus(wipTestResults.toSet()).plus(updatedWipTestResults)
    }
}

private data class OperationKey(
    val path: String,
    val method: String,
    val requestContentType: String?,
    val responseStatus: Int
)

data class CoverageGroupKey(
    val sourceProvider: String?,
    val sourceRepository: String?,
    val sourceRepositoryBranch: String?,
    val specification: String?,
    val serviceType: String?
)

@Serializable
data class Endpoint(
    val path: String,
    val method: String,
    val responseStatus: Int,
    val soapAction: String? = null,
    val sourceProvider: String? = null,
    val sourceRepository: String? = null,
    val sourceRepositoryBranch: String? = null,
    val specification: String? = null,
    val requestContentType: String? = null,
    val responseContentType: String? = null,
    val protocol: SpecmaticProtocol,
    val specType: SpecType,
)
