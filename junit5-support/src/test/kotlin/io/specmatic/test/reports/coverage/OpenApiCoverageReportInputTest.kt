package io.specmatic.test.reports.coverage

import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.OpenAPIOperation
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
            request = null,
            response = null,
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
            request = null,
            response = null,
            result = TestResult.Success
        )

        val previousRecord = TestResultRecord(
            path = "/previous",
            method = "POST",
            responseStatus = 201,
            request = null,
            response = null,
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
            request = null,
            response = null,
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
            request = null,
            response = null,
            result = TestResult.Success
        )

        val previousRecord = TestResultRecord(
            path = "/previous",
            method = "POST",
            responseStatus = 201,
            request = null,
            response = null,
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
        assertThat(report.totalCoveragePercentage).isEqualTo(67)
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
            request = null,
            response = null,
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
            request = null,
            response = null,
            result = TestResult.Success
        )

        val previousRecord = TestResultRecord(
            path = "/resource",
            method = "POST",
            responseStatus = 201,
            request = null,
            response = null,
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
        assertThat(report.totalCoveragePercentage).isEqualTo(67)
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
            TestResultRecord("/test", "POST", 200,request = null, response = null, result =  TestResult.Failed, actualResponseStatus = 200),
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
            TestResultRecord("/test", "POST", 200,request = null, response = null, result =  TestResult.Failed, actualResponseStatus = 200),
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
        val testResultRecords = mutableListOf(TestResultRecord("/test", "POST", 200,request = null, response = null, result =  TestResult.Failed, actualResponseStatus = 0))

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

    @Test
    fun `should report coverage status as 'missing in spec' for failed tests whose operations do not exit in the spec and actualResponseStatus`(){
        val allEndpoints = mutableListOf(Endpoint(path = "/current", method = "GET", responseStatus = 200))

        val testResultRecords = mutableListOf(
            TestResultRecord(
                "/current",
                "GET",
                200,
                request = null,
                response = null,
                result = TestResult.Success,
                actualResponseStatus = 200
            ),
            TestResultRecord(
                "/current",
                "GET",
                400,
                request = null,
                response = null,
                result = TestResult.Failed,
                actualResponseStatus = 400
            ),
        )

        val reportInput = OpenApiCoverageReportInput(
            testResultRecords = testResultRecords, configFilePath = "", endpointsAPISet = true,
            allEndpoints = allEndpoints
        )
        val report = reportInput.generate()

        assertThat(report.coverageRows).size().isEqualTo(2)
        val coveredRows = report.coverageRows.filter { it.remarks == CoverageStatus.COVERED }
        assertThat(coveredRows).size().isEqualTo(1)
        val coveredRow = coveredRows.first()
        assertThat(coveredRow.path).isEqualTo("/current")
        assertThat(coveredRow.method).isEqualTo("GET")
        assertThat(coveredRow.responseStatus).isEqualTo("200")

        val missingInSpecRows = report.coverageRows.filter { it.remarks == CoverageStatus.MISSING_IN_SPEC }
        assertThat(missingInSpecRows).size().isEqualTo(1)
        val missingInSpecRow = missingInSpecRows.first()
        assertThat(missingInSpecRow.path).isEqualTo("/current")
        assertThat(missingInSpecRow.method).isEqualTo("GET")
        assertThat(missingInSpecRow.responseStatus).isEqualTo("400")

        // Assert that the responseStatus of testResultRecord's operation is updated
        val missingInSpecTestResult = report.testResultRecords.single { it.result == TestResult.MissingInSpec }
        assertThat(missingInSpecTestResult.operations.single()).isEqualTo(OpenAPIOperation("/current", "GET", "", 400))
    }

    @Test
    fun `should report coverage status as 'missing in spec' for successful tests whose operations do not exit in the spec and actualResponseStatus`(){
        val allEndpoints = mutableListOf(Endpoint(path = "/current", method = "GET", responseStatus = 200))

        val testResultRecords = mutableListOf(
            TestResultRecord(
                "/current",
                "GET",
                200,
                request = null,
                response = null,
                result = TestResult.Success,
                actualResponseStatus = 200
            ),
            TestResultRecord(
                "/current",
                "GET",
                400,
                request = null,
                response = null,
                result = TestResult.Success,
                actualResponseStatus = 400
            ),
        )

        val reportInput = OpenApiCoverageReportInput(
            testResultRecords = testResultRecords, configFilePath = "", endpointsAPISet = true,
            allEndpoints = allEndpoints
        )
        val report = reportInput.generate()

        assertThat(report.coverageRows).size().isEqualTo(2)
        val coveredRows = report.coverageRows.filter { it.remarks == CoverageStatus.COVERED }
        assertThat(coveredRows).size().isEqualTo(1)
        val coveredRow = coveredRows.first()
        assertThat(coveredRow.path).isEqualTo("/current")
        assertThat(coveredRow.method).isEqualTo("GET")
        assertThat(coveredRow.responseStatus).isEqualTo("200")

        val missingInSpecRows = report.coverageRows.filter { it.remarks == CoverageStatus.MISSING_IN_SPEC }
        assertThat(missingInSpecRows).size().isEqualTo(1)
        val missingInSpecRow = missingInSpecRows.first()
        assertThat(missingInSpecRow.path).isEqualTo("/current")
        assertThat(missingInSpecRow.method).isEqualTo("GET")
        assertThat(missingInSpecRow.responseStatus).isEqualTo("400")
    }

    @Test
    fun `should retain requestContentTypes for not covered endpoints`() {
        val endpoint1 = Endpoint(path = "/current", method = "GET", responseStatus = 200)
        val endpoint2 = Endpoint(path = "/previous", method = "POST", requestContentType = "application/json", responseStatus = 201)

        val allEndpoints = mutableListOf(endpoint1, endpoint2)
        val currentRecord = TestResultRecord(
            path = "/current",
            method = "GET",
            responseStatus = 200,
            request = null,
            response = null,
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

        assertThat(report.testResultRecords.count()).isEqualTo(2)
        val notCoveredRecord = report.testResultRecords.first { it.path == "/previous" }
        assertThat(notCoveredRecord.requestContentType).isEqualTo("application/json")
    }

    @Test
    fun `should report requestContentTypes as null wherever it is not applicable or available`() {
        val endpoint1 = Endpoint(path = "/current", method = "GET", responseStatus = 200)

        val allEndpoints = mutableListOf(endpoint1)
        val currentRecord = TestResultRecord(
            path = "/current",
            method = "GET",
            responseStatus = 200,
            request = null,
            response = null,
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

        assertThat(report.testResultRecords.count()).isEqualTo(1)
        val notCoveredRecord = report.testResultRecords.first { it.path == "/current" }
        assertThat(notCoveredRecord.requestContentType).isNull()
    }

}
