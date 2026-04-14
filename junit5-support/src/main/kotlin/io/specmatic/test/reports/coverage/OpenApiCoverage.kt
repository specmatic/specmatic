package io.specmatic.test.reports.coverage

import io.specmatic.core.filters.ExpressionStandardizer
import io.specmatic.core.filters.TestRecordFilter
import io.specmatic.core.report.OpenApiCoverageReportOperation
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.API
import io.specmatic.test.TestResultRecord
import io.specmatic.test.reports.coverage.console.OpenAPICoverageConsoleReport

class OpenApiCoverage(
    private val filterExpression: String = "",
    private val coverageReportGenerator: CoverageReportGenerator = CoverageReportGenerator(),
) {
    private val testResultRecords: MutableList<TestResultRecord> = mutableListOf()
    private val applicationAPIs: MutableList<API> = mutableListOf()
    private val excludedAPIs: MutableList<String> = mutableListOf()
    private val allSpecEndpoints: MutableList<Endpoint> = mutableListOf()
    private val specEndpointsInScope: MutableList<Endpoint> = mutableListOf()
    private var endpointsAPISet: Boolean = false

    fun addTestReportRecords(testResultRecord: TestResultRecord) {
        testResultRecords.add(testResultRecord)
    }

    fun addAPIs(apis: List<API>) {
        applicationAPIs.addAll(apis)
    }

    fun addExcludedAPIs(apis: List<String>) {
        excludedAPIs.addAll(apis)
    }

    fun addEndpoints(allEndpoints: List<Endpoint>, filteredEndpoints: List<Endpoint>) {
        this.allSpecEndpoints.addAll(allEndpoints)
        this.specEndpointsInScope.addAll(filteredEndpoints)
    }

    fun setEndpointsAPIFlag(isSet: Boolean) {
        endpointsAPISet = isSet
    }

    fun generateReportOperations(): List<OpenApiCoverageReportOperation> {
        return coverageReportGenerator.generateReportOperations(coverageContext())
    }

    fun generate(): OpenAPICoverageConsoleReport {
        return coverageReportGenerator.generate(coverageContext())
    }

    private fun coverageContext(): CoverageContext {
        return CoverageContext(
            tests = filteredTestResultRecords(),
            allSpecEndpoints = allSpecEndpoints.toList(),
            specEndpointsInScope = specEndpointsInScope.toList(),
            applicationEndpoints = filteredApplicationEndpoints(),
            endpointsApiAvailable = endpointsAPISet,
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
