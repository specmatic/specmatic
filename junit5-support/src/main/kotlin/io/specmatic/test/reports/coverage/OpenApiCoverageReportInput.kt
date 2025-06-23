package io.specmatic.test.reports.coverage

import io.specmatic.conversions.SERVICE_TYPE_HTTP
import io.specmatic.core.TestResult
import io.specmatic.core.filters.ExpressionStandardizer
import io.specmatic.core.filters.TestRecordFilter
import io.specmatic.test.API
import io.specmatic.test.TestResultRecord
import io.specmatic.test.reports.TestReportHooks
import io.specmatic.test.reports.coverage.console.GroupedTestResultRecords
import io.specmatic.test.reports.coverage.console.OpenAPICoverageConsoleReport
import io.specmatic.test.reports.coverage.console.OpenApiCoverageConsoleRow
import io.specmatic.test.reports.coverage.console.Remarks
import io.specmatic.test.reports.coverage.json.OpenApiCoverageJsonReport
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

class OpenApiCoverageReportInput(
    private var configFilePath: String,
    internal val testResultRecords: MutableList<TestResultRecord> = mutableListOf(),
    private val applicationAPIs: MutableList<API> = mutableListOf(),
    private val excludedAPIs: MutableList<String> = mutableListOf(),
    private val allEndpoints: MutableList<Endpoint> = mutableListOf(),
    internal var endpointsAPISet: Boolean = false,
    private var groupedTestResultRecords: GroupedTestResultRecords = mutableMapOf(),
    private var apiCoverageRows: MutableList<OpenApiCoverageConsoleRow> = mutableListOf(),
    private val filterExpression: String = ""
) {
    fun addTestReportRecords(testResultRecord: TestResultRecord) {
        TestReportHooks.onTestResult(testResultRecord)
        testResultRecords.add(testResultRecord)
    }

    fun addAPIs(apis: List<API>) {
        TestReportHooks.onEachListener { onActuatorApis(apis) }
        applicationAPIs.addAll(apis)
    }

    fun addExcludedAPIs(apis: List<String>) {
        excludedAPIs.addAll(apis)
    }

    fun addEndpoints(endpoints: List<Endpoint>) {
        TestReportHooks.onEachListener { onEndpointApis(endpoints) }
        allEndpoints.addAll(endpoints)
    }

    fun setEndpointsAPIFlag(isSet: Boolean) {
        TestReportHooks.onEachListener { onActuator(isSet) }
        endpointsAPISet = isSet
    }

    fun getApplicationAPIs(): List<API> {
        return applicationAPIs
    }

    fun generate(): OpenAPICoverageConsoleReport {
        val testResults = testResultRecords.filter { testResult -> excludedAPIs.none { it == testResult.path } }
        val testResultsWithNotImplementedEndpoints =
            identifyFailedTestsDueToUnimplementedEndpointsAddMissingTests(testResults)
        var allTests = addTestResultsForMissingEndpoints(testResultsWithNotImplementedEndpoints)
        allTests = addTestResultsForTestsNotGeneratedBySpecmatic(allTests, allEndpoints)
        allTests = identifyWipTestsAndUpdateResult(allTests)
        allTests = checkForInvalidTestsAndUpdateResult(allTests)

        groupedTestResultRecords = allTests.groupRecords()
        groupedTestResultRecords.forEach { (path, methodMap) ->
            val routeAPIRows: MutableList<OpenApiCoverageConsoleRow> = mutableListOf()
            val totalCoveragePercentage = calculateTotalCoveragePercentage(methodMap)

            TestReportHooks.onEachListener { onPathCoverageCalculated(path, totalCoveragePercentage) }

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
                                remarks = Remarks.resolve(testResults),
                                coveragePercentage = totalCoveragePercentage
                            )
                        )
                    }
                }
            }
            apiCoverageRows.addAll(routeAPIRows)
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
            apiCoverageRows,
            allTests,
            totalAPICount,
            missedAPICount,
            notImplementedAPICount,
            partiallyMissedAPICount,
            partiallyNotImplementedAPICount
        )
    }

    private fun addTestResultsForTestsNotGeneratedBySpecmatic(
        allTests: List<TestResultRecord>,
        allEndpoints: List<Endpoint>
    ): List<TestResultRecord> {
        val endpointsWithoutTests =
            allEndpoints.filter { endpoint ->
                allTests.none { it.path == endpoint.path && it.method == endpoint.method && it.responseStatus == endpoint.responseStatus }
                        && excludedAPIs.none { it == endpoint.path }
            }
        return allTests.plus(
            endpointsWithoutTests.map { endpoint ->
                TestResultRecord(
                    endpoint.path,
                    endpoint.method,
                    endpoint.responseStatus,
                    TestResult.NotCovered,
                    endpoint.sourceProvider,
                    endpoint.sourceRepository,
                    endpoint.sourceRepositoryBranch,
                    endpoint.specification,
                    endpoint.serviceType
                )
            }
        )
    }

    fun generateJsonReport(): OpenApiCoverageJsonReport {
        val testResults = testResultRecords.filter { testResult -> excludedAPIs.none { it == testResult.path } }
        val testResultsWithNotImplementedEndpoints =
            identifyFailedTestsDueToUnimplementedEndpointsAddMissingTests(testResults)
        val allTests = addTestResultsForMissingEndpoints(testResultsWithNotImplementedEndpoints)
        return OpenApiCoverageJsonReport(configFilePath, allTests)
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
                api.path,
                api.method,
                0,
                TestResult.MissingInSpec,
                serviceType = SERVICE_TYPE_HTTP
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
        val notImplementedAndMissingTests = mutableListOf<TestResultRecord>()
        val failedTestResults = testResults.filter { it.result == TestResult.Failed }

        for (failedTestResult in failedTestResults) {

            val pathHasErrorResponse = allEndpoints.any {
                it.path == failedTestResult.path && it.method == failedTestResult.method && it.responseStatus == failedTestResult.actualResponseStatus
            }

            if (!failedTestResult.isConnectionRefused() && !pathHasErrorResponse) {
                notImplementedAndMissingTests.add(
                    failedTestResult.copy(
                        responseStatus = failedTestResult.actualResponseStatus,
                        result = TestResult.MissingInSpec,
                        actualResponseStatus = failedTestResult.actualResponseStatus
                    )
                )
            }

            if (!endpointsAPISet) {
                notImplementedAndMissingTests.add(failedTestResult)
                continue
            }

            val isInApplicationAPI =
                applicationAPIs.any { api -> api.path == failedTestResult.path && api.method == failedTestResult.method }
            notImplementedAndMissingTests.add(failedTestResult.copy(result = if (isInApplicationAPI) TestResult.Failed else TestResult.NotImplemented))
        }

        return testResults.minus(failedTestResults.toSet()).plus(notImplementedAndMissingTests)
    }

    private fun checkForInvalidTestsAndUpdateResult(allTests: List<TestResultRecord>): List<TestResultRecord> {
        val invalidTestResults = allTests.filterNot(::isTestResultValid)
        val updatedInvalidTestResults = invalidTestResults.map { it.copy(isValid = false) }

        return allTests.minus(invalidTestResults.toSet()).plus(updatedInvalidTestResults)
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

    private fun identifyWipTestsAndUpdateResult(testResults: List<TestResultRecord>): List<TestResultRecord> {
        val wipTestResults = testResults.filter { it.scenarioResult?.scenario?.ignoreFailure == true }
        val updatedWipTestResults = wipTestResults.map { it.copy(isWip = true) }
        return testResults.minus(wipTestResults.toSet()).plus(updatedWipTestResults)
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
    val serviceType: String? = null,
    val requestContentType: String? = null,
    val responseContentType: String? = null
)
