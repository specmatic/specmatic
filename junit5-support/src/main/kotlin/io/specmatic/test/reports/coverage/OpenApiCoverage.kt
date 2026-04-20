package io.specmatic.test.reports.coverage

import io.specmatic.core.Scenario
import io.specmatic.core.filters.ExpressionStandardizer
import io.specmatic.core.filters.TestRecordFilter
import io.specmatic.core.utilities.Decision
import io.specmatic.core.utilities.mapValue
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.ctrf.model.CtrfOperationMetrics
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.API
import io.specmatic.test.ContractTest
import io.specmatic.test.HttpInteractionsLog
import io.specmatic.test.TestResultRecord
import io.specmatic.test.reports.TestReportListener
import io.specmatic.test.reports.onEachListener
import io.specmatic.test.reports.onTestResult

class OpenApiCoverage(
    private val configFilePath: String,
    private val filterExpression: String = "",
    private val coverageHooks: List<TestReportListener> = emptyList(),
    private val previousRunCoverageMetrics: Map<OpenAPIOperation, CtrfOperationMetrics> = emptyMap(),
    private val httpInteractionsLog: HttpInteractionsLog = HttpInteractionsLog(),
    private val coverageReportGenerator: CoverageReportGenerator = CoverageReportGenerator(),
) {
    private val testResultRecords: MutableList<TestResultRecord> = mutableListOf()
    private val applicationAPIs: MutableList<API> = mutableListOf()
    private val excludedAPIs: MutableList<String> = mutableListOf()
    private val allSpecEndpoints: MutableList<Endpoint> = mutableListOf()
    private val specEndpointsInScope: MutableList<Endpoint> = mutableListOf()
    private val contractTestDecisions: MutableMap<Endpoint, List<Decision<ContractTest, Scenario>>> = mutableMapOf()
    private var endpointsAPISet: Boolean = false

    fun addTestReportRecords(testResultRecord: TestResultRecord) {
        coverageHooks.onTestResult(testResultRecord, httpInteractionsLog.testHttpLogMessages.toList())
        testResultRecords.add(testResultRecord)
    }

    fun addAPIs(apis: List<API>) {
        val filterExpression = ExpressionStandardizer.filterToEvalEx(filterExpression)
        val apisNotExcluded = apis.filter { api ->
            val operation = OpenAPIOperation(path = api.path, method = api.method, contentType = null, responseCode = 0, protocol = SpecmaticProtocol.HTTP)
            val testResultRecord = TestResultRecord(path = api.path, method = api.method, responseStatus = 0, request = null, response = null, result = TestResult.MissingInSpec, specType = SpecType.OPENAPI, operations = setOf(operation))
            filterExpression.with("context", TestRecordFilter(testResultRecord)).evaluate().booleanValue
        }

        applicationAPIs.addAll(apis)
        coverageHooks.onEachListener {
            onActuatorApis(
                apisNotExcluded = apisNotExcluded,
                apisExcluded = apis.toSet().minus(apisNotExcluded.toSet()).toList()
            )
        }
    }

    fun addExcludedAPIs(apis: List<String>) {
        excludedAPIs.addAll(apis)
    }

    fun addEndpoints(allEndpoints: List<Endpoint> = emptyList(), filteredEndpoints: List<Endpoint> = emptyList()) {
        this.allSpecEndpoints.addAll(allEndpoints)
        this.specEndpointsInScope.addAll(filteredEndpoints)
        val excludedEndpoints = allEndpoints.toSet().minus(filteredEndpoints.toSet()).toList()
        coverageHooks.onEachListener {
            onEndpointApis(endpointsNotExcluded = filteredEndpoints, endpointsExcluded = excludedEndpoints)
        }
    }

    fun setEndpointsAPIFlag(isSet: Boolean) {
        endpointsAPISet = isSet
        coverageHooks.onEachListener { onActuator(isSet) }
    }

    fun onContractTestDecision(contractTestDecision: Decision<Pair<ContractTest, String>, Scenario>) {
        val key = Endpoint(contractTestDecision.context)
        val decision = contractTestDecision.mapValue { it.first }
        coverageHooks.onEachListener { onTestDecision(decision) }
        contractTestDecisions[key] = contractTestDecisions.getOrDefault(key, emptyList()).plus(decision)
    }

    fun isEndpointsApiSet(): Boolean {
        return endpointsAPISet
    }

    fun getApplicationAPIs(): List<API> {
        return applicationAPIs.toList()
    }

    fun generateWithoutHooks(): OpenApiCoverageReport {
        return generateInternal(emptyList())
    }

    fun generate(): OpenApiCoverageReport {
        return generateInternal(coverageHooks)
    }

    private fun generateInternal(coverageHooks: List<TestReportListener>): OpenApiCoverageReport {
        val context = coverageContext()
        val coverageOperations = coverageReportGenerator.generateReportOperations(context)
        return OpenApiCoverageReport(
            coverageHooks = coverageHooks,
            configFilePath = configFilePath,
            testResultRecords = context.tests,
            actuatorEnabled = endpointsAPISet,
            deprecatedData = createDeprecateData(),
            coverageOperations = coverageOperations,
            httpInteractionsLog = httpInteractionsLog,
        )
    }

    private fun createDeprecateData(): OpenApiCoverageDeprecatedData {
        return OpenApiCoverageDeprecatedData(
            excludedAPIs = excludedAPIs,
            endpointsApiSet = endpointsAPISet,
            applicationAPIs = applicationAPIs,
            allSpecEndpoints = allSpecEndpoints,
            filterExpression = filterExpression
        )
    }

    private fun coverageContext(): CoverageContext {
        return CoverageContext(
            decisions = contractTestDecisions,
            endpointsApiAvailable = endpointsAPISet,
            allSpecEndpoints = allSpecEndpoints.toList(),
            applicationEndpoints = filteredApplicationEndpoints(),
            previousCoverageMetrics = previousRunCoverageMetrics,
            tests = filteredTestResultRecords(),
        )
    }

    private fun filteredTestResultRecords(): List<TestResultRecord> {
        return testResultRecords.filter { it.path !in excludedAPIs }
    }

    private fun filteredApplicationEndpoints(): List<API> {
        if (!endpointsAPISet) {
            return emptyList()
        }

        return applicationAPIs
            .filter { it.path !in excludedAPIs }
            .filter { shouldIncludeMissingInSpecOperation(it) }
    }

    private fun shouldIncludeMissingInSpecOperation(api: API): Boolean {
        val projectedFilterExpression = ExpressionStandardizer.filterToEvalExForSupportedKeys(filterExpression) {
            TestRecordFilter.supportsFilterKey(it)
        }

        val missingInSpecRecord = TestResultRecord(
            path = api.path,
            method = api.method,
            responseStatus = 0,
            request = null,
            response = null,
            result = TestResult.MissingInSpec,
            specType = SpecType.OPENAPI,
        )

        return projectedFilterExpression.with("context", TestRecordFilter(missingInSpecRecord)).evaluate().booleanValue
    }
}
