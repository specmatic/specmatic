package `in`.specmatic.test.reports.coverage

import `in`.specmatic.conversions.convertPathParameterStyle
import `in`.specmatic.core.TestResult
import `in`.specmatic.core.pattern.ContractException
import `in`.specmatic.test.API
import `in`.specmatic.test.TestResultRecord
import kotlin.math.min
import kotlin.math.roundToInt

class OpenApiCoverageReportInput(
    private val testResultRecords: MutableList<TestResultRecord> = mutableListOf(),
    private val applicationAPIs: MutableList<API> = mutableListOf(),
    private val excludedAPIs: MutableList<String> = mutableListOf()
) {
    fun addTestReportRecords(testResultRecord: TestResultRecord) {
        testResultRecords.add(testResultRecord)
    }

    fun addAPIs(apis: List<API>) {
        applicationAPIs.addAll(apis)
    }

    fun addExcludedAPIs(apis: List<String>){
        excludedAPIs.addAll(apis)
    }

    fun generate(): OpenAPICoverageReport {
        val testResults = testResultRecords.filter { testResult -> excludedAPIs.none { it == testResult.path } }
        val testResultsWithNotImplementedEndpoints = identifyTestsThatFailedBecauseOfEndpointsThatWereNotImplemented(testResults)
        var allTests = addTestResultsForMissingEndpoints(testResultsWithNotImplementedEndpoints)
        allTests = sortByPathMethodResponseStatus(allTests)

        val apiTestsGrouped = groupTestsByPathMethodAndResponseStatus(allTests)
        val apiCoverageRows: MutableList<OpenApiCoverageRow> = mutableListOf()
        apiTestsGrouped.forEach { (route, methodMap) ->
            val routeAPIRows: MutableList<OpenApiCoverageRow> = mutableListOf()
            val topLevelCoverageRow = createTopLevelApiCoverageRow(route, methodMap)
            methodMap.forEach { (method, responseCodeMap) ->
                responseCodeMap.forEach { (responseStatus, testResults) ->
                    if (routeAPIRows.isEmpty()) {
                        routeAPIRows.add(topLevelCoverageRow)
                    } else {
                        val lastCoverageRow = routeAPIRows.last()
                        val rowMethod = if (method != lastCoverageRow.method) method else ""
                        routeAPIRows.add(
                            topLevelCoverageRow.copy(
                                method = rowMethod,
                                path ="",
                                responseStatus = responseStatus.toString(),
                                count = testResults.count{it.includeForCoverage}.toString(),
                                coveragePercentage = 0,
                                remarks = getRemarks(testResults)
                            )
                        )
                    }
                }
            }
            apiCoverageRows.addAll(routeAPIRows)
        }

        val totalAPICount = apiTestsGrouped.keys.size
        val missedAPICount = allTests.groupBy { it.path }.filter { pathMap -> pathMap.value.any { it.result == TestResult.Skipped } }.size
        val notImplementedAPICount = allTests.groupBy { it.path }.filter { pathMap -> pathMap.value.any { it.result == TestResult.NotImplemented } }.size
        return OpenAPICoverageReport(apiCoverageRows, totalAPICount, missedAPICount, notImplementedAPICount)
    }

    private fun groupTestsByPathMethodAndResponseStatus(allAPITests: List<TestResultRecord>): MutableMap<String, MutableMap<String, MutableMap<Int, MutableList<TestResultRecord>>>> {
        return allAPITests.groupBy { it.path }
            .mapValues { (_, pathResults) ->
                pathResults.groupBy { it.method }
                    .mapValues { (_, methodResults) ->
                        methodResults.groupBy { it.responseStatus }
                            .mapValues { (_, responseResults) ->
                                responseResults.toMutableList()
                            }.toMutableMap()
                    }.toMutableMap()
            }.toMutableMap()
    }

    private fun sortByPathMethodResponseStatus(testResultRecordList: List<TestResultRecord>): List<TestResultRecord> {
        val recordsWithFixedURLs = testResultRecordList.map {
            it.copy(path = convertPathParameterStyle(it.path))
        }
        return recordsWithFixedURLs.groupBy {
            "${it.path}-${it.method}-${it.responseStatus}"
        }.let { sortedRecords: Map<String, List<TestResultRecord>> ->
            sortedRecords.keys.sorted().map { key ->
                sortedRecords.getValue(key)
            }
        }.flatten()
    }

    private fun addTestResultsForMissingEndpoints(testResults: List<TestResultRecord>): List<TestResultRecord> {
        val testReportRecordsIncludingMissingAPIs = testResults.toMutableList()
        applicationAPIs.forEach { api ->
            if (testResults.none { it.path == api.path && it.method == api.method } && excludedAPIs.none { it == api.path }) {
                testReportRecordsIncludingMissingAPIs.add(TestResultRecord(api.path, api.method, 0, TestResult.Skipped))
            }
        }
        return testReportRecordsIncludingMissingAPIs
    }

    private fun createTopLevelApiCoverageRow(
        route: String,
        methodMap: MutableMap<String, MutableMap<Int, MutableList<TestResultRecord>>>,
    ): OpenApiCoverageRow {
        val method = methodMap.keys.first()
        val responseStatus = methodMap[method]?.keys?.first()
        val remarks = getRemarks(methodMap[method]?.get(responseStatus)!!)
        val count = methodMap[method]?.get(responseStatus)?.count { it.includeForCoverage }

        val totalMethodResponseCodeCount = methodMap.values.sumOf { it.keys.size }
        var totalMethodResponseCodeCoveredCount = 0
        methodMap.forEach { (_, responses) ->
            responses.forEach { (_, testResults) ->
                val increment = min(testResults.count { it.includeForCoverage }, 1)
                totalMethodResponseCodeCoveredCount += increment
            }
        }

        val coveragePercentage =
            ((totalMethodResponseCodeCoveredCount.toFloat() / totalMethodResponseCodeCount.toFloat()) * 100).roundToInt()
        return OpenApiCoverageRow(
            method,
            route,
            responseStatus!!,
            count!!,
            coveragePercentage,
            remarks
        )
    }

    private fun getRemarks(testResultRecords: List<TestResultRecord>): Remarks {
        val coveredCount = testResultRecords.count { it.includeForCoverage }
        return when (coveredCount == 0) {
            true -> when (testResultRecords.first().result) {
                TestResult.Skipped -> Remarks.Missed
                TestResult.NotImplemented -> Remarks.NotImplemented
                else -> throw ContractException("Cannot determine remarks for unknown test result: ${testResultRecords.first().result}")
            }

            else -> Remarks.Covered
        }
    }

    private fun identifyTestsThatFailedBecauseOfEndpointsThatWereNotImplemented(testResults: List<TestResultRecord>): List<TestResultRecord> {
        val notImplementedTests =
            testResults.filter { it.result == TestResult.Failed && applicationAPIs.none { api -> api.path == it.path && api.method == it.method } }
        return testResults.minus(notImplementedTests.toSet())
            .plus(notImplementedTests.map { it.copy(result = TestResult.NotImplemented) })
    }
}