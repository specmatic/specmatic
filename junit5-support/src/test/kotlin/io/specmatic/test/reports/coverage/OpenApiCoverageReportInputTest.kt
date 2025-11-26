package io.specmatic.test.reports.coverage

import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.API
import io.specmatic.test.TestResultRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenApiCoverageReportInputTest {
    @Test
    fun `should generate report with only current records when no previous results are provided`() {
        val currentRecord = TestResultRecord(
            path = "/current",
            method = "GET",
            responseStatus = 200,
            result = TestResult.Success
        )

        val input = OpenApiCoverageReportInput(
            configFilePath = "specmatic.yaml",
            testResultRecords = mutableListOf(currentRecord),
            previousTestResultRecord = emptyList()
        )

        val report = input.generate()
        assertThat(report.coverageRows).anyMatch { row -> row.path == "/current" && row.method == "GET" && row.responseStatus == "200" }
        assertThat(report.coverageRows).noneMatch { row -> row.path == "/previous" }
    }

    @Test
    fun `should include previous test results in the coverage report generation`() {
        val currentRecord = TestResultRecord(
            path = "/current",
            method = "GET",
            responseStatus = 200,
            result = TestResult.Success
        )

        val previousRecord = TestResultRecord(
            path = "/previous",
            method = "POST",
            responseStatus = 201,
            result = TestResult.Success
        )

        val input = OpenApiCoverageReportInput(
            configFilePath = "specmatic.yaml",
            testResultRecords = mutableListOf(currentRecord),
            previousTestResultRecord = listOf(previousRecord)
        )

        val report = input.generate()
        assertThat(report.coverageRows).anyMatch { row -> row.path == "/current" && row.method == "GET" && row.responseStatus == "200" }
        assertThat(report.coverageRows).anyMatch { row -> row.path == "/previous" && row.method == "POST" && row.responseStatus == "201" }
    }

    @Test
    fun `should calculate coverage percentage with only current results`() {
        val endpoint1 = Endpoint(path = "/current", method = "GET", responseStatus = 200)
        val endpoint2 = Endpoint(path = "/previous", method = "POST", responseStatus = 201)
        val endpoint3 = Endpoint(path = "/uncovered", method = "GET", responseStatus = 404)

        val allEndpoints = mutableListOf(endpoint1, endpoint2, endpoint3)
        val currentRecord = TestResultRecord(
            path = "/current",
            method = "GET",
            responseStatus = 200,
            result = TestResult.Success
        )

        val input = OpenApiCoverageReportInput(
            configFilePath = "specmatic.yaml",
            testResultRecords = mutableListOf(currentRecord),
            previousTestResultRecord = emptyList(),
            allEndpoints = allEndpoints,
            filteredEndpoints = allEndpoints
        )

        val report = input.generate()
        assertThat(report.totalCoveragePercentage).isEqualTo(33)
        assertThat(report.coverageRows).anyMatch { it.path == "/current" && it.remarks.toString() == "covered" && it.coveragePercentage == 100 }
        assertThat(report.coverageRows).anyMatch { it.path == "/previous" && it.remarks.toString() == "not covered" && it.coveragePercentage == 0 }
        assertThat(report.coverageRows).anyMatch { it.path == "/uncovered" && it.remarks.toString() == "invalid" && it.coveragePercentage == 0 }
    }

    @Test
    fun `should calculate coverage percentage including previous test results and uncovered endpoints`() {
        val endpoint1 = Endpoint(path = "/current", method = "GET", responseStatus = 200)
        val endpoint2 = Endpoint(path = "/previous", method = "POST", responseStatus = 201)
        val endpoint3 = Endpoint(path = "/uncovered", method = "GET", responseStatus = 404)

        val allEndpoints = mutableListOf(endpoint1, endpoint2, endpoint3)
        val currentRecord = TestResultRecord(
            path = "/current",
            method = "GET",
            responseStatus = 200,
            result = TestResult.Success
        )

        val previousRecord = TestResultRecord(
            path = "/previous",
            method = "POST",
            responseStatus = 201,
            result = TestResult.Success
        )

        val input = OpenApiCoverageReportInput(
            configFilePath = "specmatic.yaml",
            testResultRecords = mutableListOf(currentRecord),
            previousTestResultRecord = listOf(previousRecord),
            allEndpoints = allEndpoints,
            filteredEndpoints = allEndpoints
        )

        val report = input.generate()
        assertThat(report.totalCoveragePercentage).isEqualTo(66)
        assertThat(report.coverageRows).anyMatch { it.path == "/current" && it.remarks.toString() == "covered" && it.coveragePercentage == 100 }
        assertThat(report.coverageRows).anyMatch { it.path == "/previous" && it.remarks.toString() == "covered" && it.coveragePercentage == 100 }
        assertThat(report.coverageRows).anyMatch { it.path == "/uncovered" && it.remarks.toString() == "invalid" && it.coveragePercentage == 0 }
    }

    @Test
    fun `should calculate coverage for a single path with mixed results when no previous runs`() {
        val endpoint1 = Endpoint(path = "/resource", method = "GET", responseStatus = 200)
        val endpoint2 = Endpoint(path = "/resource", method = "POST", responseStatus = 201)
        val endpoint3 = Endpoint(path = "/resource", method = "DELETE", responseStatus = 204)

        val allEndpoints = mutableListOf(endpoint1, endpoint2, endpoint3)
        val currentRecord = TestResultRecord(
            path = "/resource",
            method = "GET",
            responseStatus = 200,
            result = TestResult.Success
        )

        val input = OpenApiCoverageReportInput(
            configFilePath = "specmatic.yaml",
            testResultRecords = mutableListOf(currentRecord),
            previousTestResultRecord = emptyList(),
            allEndpoints = allEndpoints,
            filteredEndpoints = allEndpoints
        )

        val report = input.generate()
        assertThat(report.totalCoveragePercentage).isEqualTo(33)
        assertThat(report.coverageRows).anyMatch {
            it.path == "/resource" && it.method == "GET" &&  it.remarks.toString() == "covered" &&  it.coveragePercentage == 33
        }
        assertThat(report.coverageRows).anyMatch {
            it.path == "/resource" && it.method == "POST" && it.remarks.toString() == "not covered" && it.coveragePercentage == 33
        }
        assertThat(report.coverageRows).anyMatch {
            it.path == "/resource" && it.method == "DELETE" && it.remarks.toString() == "not covered" && it.coveragePercentage == 33
        }
    }

    @Test
    fun `should calculate coverage for a single path with mixed results including previous runs when provided`() {
        val endpoint1 = Endpoint(path = "/resource", method = "GET", responseStatus = 200)
        val endpoint2 = Endpoint(path = "/resource", method = "POST", responseStatus = 201)
        val endpoint3 = Endpoint(path = "/resource", method = "DELETE", responseStatus = 204)

        val allEndpoints = mutableListOf(endpoint1, endpoint2, endpoint3)
        val currentRecord = TestResultRecord(
            path = "/resource",
            method = "GET",
            responseStatus = 200,
            result = TestResult.Success
        )

        val previousRecord = TestResultRecord(
            path = "/resource",
            method = "POST",
            responseStatus = 201,
            result = TestResult.Success
        )

        val input = OpenApiCoverageReportInput(
            configFilePath = "specmatic.yaml",
            testResultRecords = mutableListOf(currentRecord),
            previousTestResultRecord = listOf(previousRecord),
            allEndpoints = allEndpoints,
            filteredEndpoints = allEndpoints
        )

        val report = input.generate()
        assertThat(report.totalCoveragePercentage).isEqualTo(66)
        assertThat(report.coverageRows).anyMatch {
            it.path == "/resource" && it.method == "GET" &&  it.remarks.toString() == "covered" &&  it.coveragePercentage == 67
        }
        assertThat(report.coverageRows).anyMatch {
            it.path == "/resource" && it.method == "POST" && it.remarks.toString() == "covered" && it.coveragePercentage == 67
        }
        assertThat(report.coverageRows).anyMatch {
            it.path == "/resource" && it.method == "DELETE" && it.remarks.toString() == "not covered" && it.coveragePercentage == 67
        }
    }

    @Test
    fun `endpoint should not be counted towards the final report if it has been filtered out`() {
        val allEndpoints = mutableListOf(Endpoint("/test", "POST", 200), Endpoint("/filtered", "POST", 200))
        val filtered = mutableListOf(Endpoint("/test", "POST", 200))
        val testResultRecords = mutableListOf(
            TestResultRecord("/test", "POST", 200, TestResult.Failed, actualResponseStatus = 200),
        )

        val reportInput = OpenApiCoverageReportInput(
            testResultRecords = testResultRecords, configFilePath = "",
            allEndpoints = allEndpoints, filteredEndpoints = filtered
        )
        val report = reportInput.generate()

        assertThat(report.testResultRecords).noneMatch { it.path == "/filtered" }
        assertThat(report.coverageRows).noneMatch { it.path == "/filtered" }
        assertThat(report.totalCoveragePercentage).isEqualTo(100)
    }

    @Test
    fun `should not be marked missing-in-spec if the endpoint is filtered out but actuator shows it`() {
        val allEndpoints = mutableListOf(Endpoint("/test", "POST", 200), Endpoint("/filtered", "POST", 200))
        val filtered = mutableListOf(Endpoint("/test", "POST", 200))
        val applicationAPIs = mutableListOf(API("POST", "/test"), API("POST", "/filtered"))
        val testResultRecords = mutableListOf(
            TestResultRecord("/test", "POST", 200, TestResult.Failed, actualResponseStatus = 200),
        )

        val reportInput = OpenApiCoverageReportInput(
            testResultRecords = testResultRecords, applicationAPIs = applicationAPIs,
            allEndpoints = allEndpoints, filteredEndpoints = filtered, configFilePath = ""
        )
        val report = reportInput.generate()

        assertThat(report.testResultRecords).noneMatch { it.path == "/filtered" }
        assertThat(report.coverageRows).noneMatch { it.path == "/filtered" }
    }

    @Test
    fun `not-implemented endpoints should be identified using filtered endpoints instead of all endpoints`() {
        val allEndpoints = mutableListOf(Endpoint("/test", "POST", 200), Endpoint("/filtered", "POST", 200))
        val filtered = mutableListOf(Endpoint("/test", "POST", 200))
        val testResultRecords = mutableListOf(TestResultRecord("/test", "POST", 200, TestResult.Failed, actualResponseStatus = 0))

        val reportInput = OpenApiCoverageReportInput(
            testResultRecords = testResultRecords, configFilePath = "", endpointsAPISet = true,
            allEndpoints = allEndpoints, filteredEndpoints = filtered
        )
        val report = reportInput.generate()

        assertThat(report.coverageRows).anyMatch { it.path == "/test" && it.remarks == CoverageStatus.NOT_IMPLEMENTED }
        assertThat(report.testResultRecords).noneMatch { it.path == "/filtered" }
        assertThat(report.coverageRows).noneMatch { it.path == "/filtered" }
        assertThat(report.totalCoveragePercentage).isEqualTo(100)
    }
}
