package io.specmatic.test.reports.coverage.json

import io.specmatic.core.filters.ExpressionStandardizer
import io.specmatic.core.filters.TestRecordFilter
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.generated.dto.coverage.CoverageEntry
import io.specmatic.reporter.generated.dto.coverage.OpenAPICoverageOperation
import io.specmatic.reporter.generated.dto.coverage.SpecmaticCoverageReport
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.TestResultRecord
import io.specmatic.test.TestResultRecord.Companion.getCoverageStatus
import io.specmatic.test.reports.coverage.Endpoint
import io.specmatic.test.reports.coverage.OpenApiCoverageReport

class LegacyTestJsonGenerator(val openApiCoverageReport: OpenApiCoverageReport) {
    fun generateJsonReport(): SpecmaticCoverageReport {
        val testResults = openApiCoverageReport.testResultRecords.filter { testResult ->
            openApiCoverageReport.deprecatedData.excludedAPIs.none { it == testResult.path }
        }

        val testResultsWithNotImplementedEndpoints = identifyFailedTestsDueToUnimplementedEndpointsAddMissingTests(testResults)
        val allTests = addTestResultsForMissingEndpoints(testResultsWithNotImplementedEndpoints)
        return SpecmaticCoverageReport()
            .withSpecmaticConfigPath(openApiCoverageReport.configFilePath)
            .withApiCoverage(allTests.toCoverageEntries())
    }

    private fun List<TestResultRecord>.toCoverageEntries(): List<CoverageEntry> {
        return this.groupBy {
            CoverageGroupKey(
                sourceRepository = it.repository,
                specification = it.specification,
                sourceRepositoryBranch = it.branch,
                sourceProvider = it.sourceProvider,
                serviceType = it.operations.firstOrNull()?.protocol?.key?.uppercase()
            )
        }.map { (key, recordsOfGroup) ->
            CoverageEntry()
                .withSpecType("OPENAPI")
                .withType(key.sourceProvider)
                .withServiceType(key.serviceType)
                .withRepository(key.sourceRepository)
                .withSpecification(key.specification)
                .withBranch(key.sourceRepositoryBranch)
                .withOperations(recordsOfGroup.toOpenAPICoverageOperations())
        }
    }

    private fun List<TestResultRecord>.toOpenAPICoverageOperations(): List<OpenAPICoverageOperation> {
        return this.groupBy { Triple(it.path, it.method, it.responseStatus) }.map { (operationGroup, operationRows) ->
            OpenAPICoverageOperation()
                .withPath(operationGroup.first)
                .withMethod(operationGroup.second)
                .withResponseCode(operationGroup.third)
                .withCoverageStatus(operationRows.getCoverageStatus())
                .withCount(operationRows.count { it.isExercised })
        }
    }

    private fun addTestResultsForMissingEndpoints(testResults: List<TestResultRecord>): List<TestResultRecord> {
        if (!openApiCoverageReport.deprecatedData.endpointsApiSet) {
            return testResults
        }

        val projectedFilterExpression = ExpressionStandardizer.filterToEvalExForSupportedKeys(openApiCoverageReport.deprecatedData.filterExpression) {
            TestRecordFilter.supportsFilterKey(it)
        }

        val filteredMissingApiResults = missingInSpecTestResultRecords().filter { missingTestResult ->
            projectedFilterExpression.with("context", TestRecordFilter(missingTestResult)).evaluate().booleanValue
        }

        return testResults + filteredMissingApiResults
    }

    private fun missingInSpecTestResultRecords(): List<TestResultRecord> {
        return openApiCoverageReport.deprecatedData.applicationAPIs.filter { api ->
            val noTestResultFoundForThisAPI = openApiCoverageReport.deprecatedData.allSpecEndpoints.none { it.path == api.path && it.method == api.method }
            val isNotExcluded = api.path !in openApiCoverageReport.deprecatedData.excludedAPIs
            noTestResultFoundForThisAPI && isNotExcluded
        }.map { api ->
            val closestMatchingEndpoint = closestMatchingEndpointFor(api.path, api.method)
            TestResultRecord(
                request = null,
                response = null,
                path = api.path,
                responseStatus = 0,
                method = api.method,
                specType = SpecType.OPENAPI,
                result = TestResult.MissingInSpec,
                repository = closestMatchingEndpoint?.sourceRepository,
                specification = closestMatchingEndpoint?.specification,
                sourceProvider = closestMatchingEndpoint?.sourceProvider,
                branch = closestMatchingEndpoint?.sourceRepositoryBranch,
                operations = setOf(OpenAPIOperation(path = api.path, method = api.method, contentType = null, responseCode = 0, protocol = SpecmaticProtocol.HTTP)),
            )
        }
    }

    private fun closestMatchingEndpointFor(path: String, method: String): Endpoint? {
        val endpointsWithSpecs = openApiCoverageReport.deprecatedData.allSpecEndpoints.filter { it.specification != null }
        if (endpointsWithSpecs.isEmpty()) {
            return null
        }

        val methodMatchedEndpoints = endpointsWithSpecs.filter { it.method == method }
        val candidateEndpoints = methodMatchedEndpoints.ifEmpty { endpointsWithSpecs }
        return candidateEndpoints.maxWithOrNull(
            compareBy<Endpoint> { commonPathPrefixSegments(path, it.path) }.thenBy { normalizedPathSegments(it.path).size }
        ) ?: endpointsWithSpecs.first()
    }

    private fun commonPathPrefixSegments(leftPath: String, rightPath: String): Int {
        val leftSegments = normalizedPathSegments(leftPath)
        val rightSegments = normalizedPathSegments(rightPath)
        return leftSegments.zip(rightSegments).takeWhile { (left, right) -> left == right }.count()
    }

    private fun normalizedPathSegments(path: String): List<String> {
        return path.trim('/').split('/').filter { it.isNotBlank() }
    }

    private fun identifyFailedTestsDueToUnimplementedEndpointsAddMissingTests(testResults: List<TestResultRecord>): List<TestResultRecord> {
        return testResults.flatMap { testResult ->
            val updated = if (testResult.hasFailedAndEndpointIsNotImplemented()) {
                testResult.copy(result = TestResult.NotImplemented)
            } else {
                testResult
            }

            if (updated.testedEndpointIsMissingInSpec()) {
                createMissingInSpecRecordAndIncludeOriginalRecordIfApplicable(updated)
            } else {
                listOf(updated)
            }
        }
    }

    private fun createMissingInSpecRecordAndIncludeOriginalRecordIfApplicable(testResult: TestResultRecord): List<TestResultRecord> = listOfNotNull(
        testResult.copy(
            result = TestResult.MissingInSpec,
            responseStatus = testResult.actualResponseStatus,
            actualResponseStatus = testResult.actualResponseStatus,
            operations = setOf(
                OpenAPIOperation(
                    path = testResult.path,
                    method = testResult.method,
                    protocol = SpecmaticProtocol.HTTP,
                    contentType = testResult.requestContentType,
                    responseCode = testResult.actualResponseStatus,
                )
            ),
        ),
        testResult.takeIf {
            it.sourceEndpointIsPresentInSpec()
        }
    )

    private fun TestResultRecord.hasFailedAndEndpointIsNotImplemented(): Boolean {
        return this.result == TestResult.Failed && openApiCoverageReport.deprecatedData.endpointsApiSet && openApiCoverageReport.deprecatedData.applicationAPIs.isNotEmpty() && openApiCoverageReport.deprecatedData.applicationAPIs.none {
            it.path == this.path && it.method == this.method
        }
    }

    private fun TestResultRecord.testedEndpointIsMissingInSpec(): Boolean {
        if (this.result == TestResult.NotImplemented) {
            return false
        }

        val endpointExistsInSpecification = openApiCoverageReport.deprecatedData.allSpecEndpoints.any {
            it.path == this.path && it.method == this.method && it.responseStatus == this.actualResponseStatus
        }

        return (!this.isConnectionRefused() && !endpointExistsInSpecification)
    }

    private fun TestResultRecord.sourceEndpointIsPresentInSpec(): Boolean {
        return openApiCoverageReport.deprecatedData.allSpecEndpoints.any {
            it.path == this.path && it.method == this.method && it.responseStatus == this.responseStatus
        }
    }

    private data class CoverageGroupKey(
        val sourceProvider: String?,
        val sourceRepository: String?,
        val sourceRepositoryBranch: String?,
        val specification: String?,
        val serviceType: String?
    )
}
