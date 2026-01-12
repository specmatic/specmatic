package io.specmatic.test.reports.coverage

import io.specmatic.core.filters.ExpressionStandardizer
import io.specmatic.core.filters.TestRecordFilter
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.generated.dto.coverage.CoverageEntry
import io.specmatic.reporter.generated.dto.coverage.OpenAPICoverageOperation
import io.specmatic.reporter.generated.dto.coverage.SpecmaticCoverageReport
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.API
import io.specmatic.test.HttpInteractionsLog
import io.specmatic.test.TestResultRecord
import io.specmatic.test.TestResultRecord.Companion.getCoverageStatus
import io.specmatic.test.reports.TestReportListener
import io.specmatic.test.reports.coverage.console.GroupedTestResultRecords
import io.specmatic.test.reports.coverage.console.OpenAPICoverageConsoleReport
import io.specmatic.test.reports.coverage.console.OpenApiCoverageConsoleRow
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
) {
    fun endpoints() = allEndpoints.toList()

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
        coverageHooks.onEachListener { onActuatorApis(apis) }
        applicationAPIs.addAll(apis)
    }

    fun addExcludedAPIs(apis: List<String>) {
        excludedAPIs.addAll(apis)
    }

    fun addEndpoints(allEndpoints: List<Endpoint>, filteredEndpoints: List<Endpoint>) {
        this.allEndpoints.addAll(allEndpoints)
        coverageHooks.onEachListener { onEndpointApis(allEndpoints) }
        this.filteredEndpoints.addAll(filteredEndpoints)
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

        val testResultsWithNotImplementedEndpoints =
            identifyFailedTestsDueToUnimplementedEndpointsAddMissingTests(testResults)

        return addTestResultsForMissingEndpoints(testResultsWithNotImplementedEndpoints)
            .addTestResultsForTestsNotGeneratedBySpecmatic(filteredEndpoints)
            .identifyWipTestsAndUpdateResult()
            .checkForInvalidTestsAndUpdateResult()
    }

    fun generate(): OpenAPICoverageConsoleReport {
        return generateCoverageReport(coverageHooks)
    }

    fun generateCoverageReport(listeners: List<TestReportListener> = emptyList()): OpenAPICoverageConsoleReport {
        val allTests = testResultRecords()
        val apiCoverageRowsInternal: MutableList<OpenApiCoverageConsoleRow> = mutableListOf()
        val groupedTestResultRecords = allTests.groupRecords()

        groupedTestResultRecords.forEach { (path, methodMap) ->
            val routeAPIRows: MutableList<OpenApiCoverageConsoleRow> = mutableListOf()
            val totalCoveragePercentage = calculateTotalCoveragePercentage(methodMap)
            listeners.onEachListener { onPathCoverageCalculated(path, totalCoveragePercentage) }
            methodMap.forEach { (method, contentTypeMap) ->
                contentTypeMap.forEach { (requestContentType, responseCodeMap) ->
                    responseCodeMap.forEach { (responseStatus, testResults) ->
                        routeAPIRows.add(
                            OpenApiCoverageConsoleRow(
                                path = path,
                                showPath = routeAPIRows.isEmpty(),
                                method = method,
                                showMethod = routeAPIRows.none { it.method == method },
                                requestContentType = requestContentType,
                                responseStatus = responseStatus,
                                count = testResults.count { it.isExercised }.toString(),
                                remarks = testResults.getCoverageStatus(),
                                coveragePercentage = totalCoveragePercentage,
                            ),
                        )
                    }
                }
            }
            apiCoverageRowsInternal.addAll(routeAPIRows)
        }

        val totalAPICount = groupedTestResultRecords.keys.size
        val testsGroupedByPath = allTests.groupBy { it.path }

        val missedAPICount = testsGroupedByPath.count { (_, tests) ->
            tests.all { it.result == TestResult.MissingInSpec }
        }

        val notImplementedAPICount = testsGroupedByPath.count { (_, tests) ->
            tests.all { it.result == TestResult.NotImplemented }
        }

        val partiallyMissedAPICount = testsGroupedByPath.count { (_, tests) ->
            tests.any { it.result == TestResult.MissingInSpec } && tests.any { it.result != TestResult.MissingInSpec }
        }

        val partiallyNotImplementedAPICount = testsGroupedByPath.count { (_, tests) ->
            tests.any { it.result == TestResult.NotImplemented } && tests.any { it.result != TestResult.NotImplemented }
        }

        return OpenAPICoverageConsoleReport(
            apiCoverageRowsInternal,
            allTests,
            totalAPICount,
            missedAPICount,
            notImplementedAPICount,
            partiallyMissedAPICount,
            partiallyNotImplementedAPICount,
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
                            protocol = endpoint.protocol
                        )
                    )
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

    private fun List<TestResultRecord>.groupRecords(): GroupedTestResultRecords {
        return groupBy { it.path }.mapValues { (_, pathMap) ->
            pathMap.groupBy { it.soapAction ?: it.method }.mapValues { (_, methodMap) ->
                methodMap.groupBy { it.requestContentType }.mapValues { (_, contentTypeMap) ->
                    contentTypeMap.groupBy { it.responseStatus.toString() }
                }
            }
        }
    }

    private fun addTestResultsForMissingEndpoints(testResults: List<TestResultRecord>): List<TestResultRecord> {
        if (!endpointsAPISet)
            return testResults

        val testResultsForMissingAPIs = applicationAPIs.filter { api ->
            val noTestResultFoundForThisAPI = allEndpoints.none { it.path == api.path && it.method == api.method }
            val isNotExcluded = api.path !in excludedAPIs
            noTestResultFoundForThisAPI && isNotExcluded
        }.map { api ->
            TestResultRecord(
                path = api.path,
                method = api.method,
                responseStatus = 0,
                request = null,
                response = null,
                result = TestResult.MissingInSpec,
                specType = SpecType.OPENAPI,
                operations = setOf(
                    OpenAPIOperation(
                        path = api.path,
                        method = api.method,
                        contentType = null,
                        responseCode = 0,
                        protocol = SpecmaticProtocol.HTTP
                    )
                )
            )
        }


        val filterExpression = ExpressionStandardizer.filterToEvalEx(filterExpression)

        return (testResults + testResultsForMissingAPIs).filter { eachTestResult ->
            filterExpression.with("context", TestRecordFilter(eachTestResult)).evaluate().booleanValue
        }
    }

    private fun calculateTotalCoveragePercentage(methodMap: Map<String, Map<String?, Map<String, List<TestResultRecord>>>>): Int {
        val (totalCount, coveredCount) = calculateCoverageCounts(methodMap)
        return (coveredCount.toFloat() / totalCount * 100).roundToInt()
    }

    private fun calculateCoverageCounts(methodMap: Map<String, Map<String?, Map<String, List<TestResultRecord>>>>): Pair<Int, Int> {
        val responseMaps = methodMap.values.flatMap { it.values }
        val totalResponseGroupCount = responseMaps.sumOf { it.size }
        val coveredResponseGroupCount = responseMaps.sumOf { responseMap ->
            responseMap.values.count { testResults -> testResults.any { it.isCovered } }
        }

        return totalResponseGroupCount to coveredResponseGroupCount
    }

    private fun identifyFailedTestsDueToUnimplementedEndpointsAddMissingTests(testResults: List<TestResultRecord>): List<TestResultRecord> {
        return testResults.map { testResult ->
            when {
                testResult.hasFailedAndEndpointIsNotImplemented() -> testResult.copy(result = TestResult.NotImplemented)
                else -> testResult
            }
        }.flatMap { testResult ->
            when {
                testResult.testedEndpointIsMissingInSpec() -> {
                    createMissingInSpecRecordAndIncludeOriginalRecordIfApplicable(testResult)
                }
                else -> listOf(testResult)
            }
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
                applicationAPIs.none {
                    it.path == this.path && it.method == this.method
                }
    }

    private fun TestResultRecord.testedEndpointIsMissingInSpec(): Boolean {
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

    private fun List<TestResultRecord>.checkForInvalidTestsAndUpdateResult(): List<TestResultRecord> {
        val invalidTestResults = this.filterNot(::isTestResultValid)
        val updatedInvalidTestResults = invalidTestResults.map { it.copy(isValid = false) }

        return this.minus(invalidTestResults.toSet()).plus(updatedInvalidTestResults)
    }

    private fun isTestResultValid(testResultRecord: TestResultRecord): Boolean {
        val paramRegex = Regex("\\{.+}")
        val isPathWithParams = paramRegex.find(testResultRecord.path) != null
        if (isPathWithParams) return true

        return when (testResultRecord.responseStatus) {
            404 -> false
            else -> true
        }
    }

    private fun List<TestResultRecord>.identifyWipTestsAndUpdateResult(): List<TestResultRecord> {
        val wipTestResults = this.filter { it.scenarioResult?.scenario?.ignoreFailure == true }
        val updatedWipTestResults = wipTestResults.map { it.copy(isWip = true) }
        return this.minus(wipTestResults.toSet()).plus(updatedWipTestResults)
    }
}

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
    val sourceProvider: String? = null,
    val sourceRepository: String? = null,
    val sourceRepositoryBranch: String? = null,
    val specification: String? = null,
    val requestContentType: String? = null,
    val responseContentType: String? = null,
    val protocol: SpecmaticProtocol,
    val specType: SpecType,
)
