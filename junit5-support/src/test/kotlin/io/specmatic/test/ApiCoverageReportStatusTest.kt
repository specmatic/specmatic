package io.specmatic.test

import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.reports.coverage.Endpoint
import io.specmatic.test.reports.coverage.OpenApiCoverageReportInput
import io.specmatic.test.reports.coverage.console.OpenApiCoverageConsoleRow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ApiCoverageReportStatusTest {

    companion object {
        const val CONFIG_FILE_PATH = "./specmatic.json"
    }

    @Test
    fun `identifies endpoint as 'covered' when contract test passes and route+method is present in actuator`() {
        val endpointsInSpec = mutableListOf(
            Endpoint("/route1", "GET", 200)
        )

        val applicationAPIs = mutableListOf(
            API("GET", "/route1")
        )

        val contractTestResults = mutableListOf(
            TestResultRecord("/route1", "GET", 200, request = null, response = null, result = TestResult.Success),
        )

        val apiCoverageReport = OpenApiCoverageReportInput(
            CONFIG_FILE_PATH,
            contractTestResults,
            applicationAPIs,
            allEndpoints = endpointsInSpec,
            endpointsAPISet = true
        ).generate()
        assertThat(apiCoverageReport.coverageRows).isEqualTo(
            listOf(
                OpenApiCoverageConsoleRow("GET", "/route1", 200, 1, 100, CoverageStatus.COVERED)
            )
        )
    }

    @Test
    fun `identifies endpoint as 'covered' when contract test passes and route+method is not present in actuator`() {
        val endpointsInSpec = mutableListOf(
            Endpoint("/route1", "GET", 200),
        )

        val applicationAPIs = mutableListOf<API>()

        val contractTestResults = mutableListOf(
            TestResultRecord("/route1", "GET", 200, request = null, response = null, result = TestResult.Success),
        )

        val apiCoverageReport = OpenApiCoverageReportInput(
            CONFIG_FILE_PATH,
            contractTestResults,
            applicationAPIs,
            allEndpoints = endpointsInSpec,
            endpointsAPISet = true
        ).generate()
        assertThat(apiCoverageReport.coverageRows).isEqualTo(
            listOf(
                OpenApiCoverageConsoleRow("GET", "/route1", 200, 1, 100, CoverageStatus.COVERED)
            )
        )
    }

    @Test
    fun `identifies endpoint as 'covered' when contract test fails and route+method is present in actuator`() {
        val endpointsInSpec = mutableListOf(
            Endpoint("/route1", "GET", 200),
        )

        val applicationAPIs = mutableListOf(
            API("GET", "/route1")
        )

        val contractTestResults = mutableListOf(
            TestResultRecord("/route1", "GET", 200, request = null, response = null, result = TestResult.Failed, actualResponseStatus = 400)
        )

        val apiCoverageReport = OpenApiCoverageReportInput(
            CONFIG_FILE_PATH,
            contractTestResults,
            applicationAPIs,
            allEndpoints = endpointsInSpec,
            endpointsAPISet = true
        ).generate()
        assertThat(apiCoverageReport.coverageRows).isEqualTo(
            listOf(
                OpenApiCoverageConsoleRow("GET", "/route1", 400, 0, 50, CoverageStatus.MISSING_IN_SPEC),
                OpenApiCoverageConsoleRow("GET", "/route1", 200, 1, 50, CoverageStatus.COVERED, showPath = false, showMethod = false)
            )
        )
    }

    @Test
    fun `identifies endpoint as 'covered' when contract test fails and actuator is not available`() {
        val endpointsInSpec = mutableListOf(
            Endpoint("/route1", "GET", 200)
        )

        val applicationAPIs = mutableListOf<API>()

        val contractTestResults = mutableListOf(
            TestResultRecord("/route1", "GET", 200, request = null, response = null, result = TestResult.Failed, actualResponseStatus = 400),
        )

        val apiCoverageReport = OpenApiCoverageReportInput(
            CONFIG_FILE_PATH,
            contractTestResults,
            applicationAPIs,
            allEndpoints = endpointsInSpec,
            endpointsAPISet = false
        ).generate()
        assertThat(apiCoverageReport.coverageRows).isEqualTo(
            listOf(
                OpenApiCoverageConsoleRow("GET", "/route1", 400, 0, 50, CoverageStatus.MISSING_IN_SPEC),
                OpenApiCoverageConsoleRow("GET", "/route1", 200, 1, 50, CoverageStatus.COVERED, showPath = false, showMethod = false)
            )
        )
    }

    @Test
    fun `identifies endpoint as 'not implemented' when contract test fails, and route+method is not present in actuator`() {
        val endpointsInSpec = mutableListOf(
            Endpoint("/route1", "GET", 200),
            Endpoint("/route2", "GET", 200)
        )

        val applicationAPIs = mutableListOf(
            API("GET", "/route1")
        )

        val contractTestResults = mutableListOf(
            TestResultRecord("/route1", "GET", 200, request = null, response = null, result = TestResult.Success, actualResponseStatus = 200),
            TestResultRecord("/route2", "GET", 200, request = null, response = null, result = TestResult.Failed, actualResponseStatus = 404),
        )

        val apiCoverageReport = OpenApiCoverageReportInput(
            CONFIG_FILE_PATH,
            contractTestResults,
            applicationAPIs,
            allEndpoints = endpointsInSpec,
            endpointsAPISet = true
        ).generate()
        assertThat(apiCoverageReport.coverageRows).isEqualTo(
            listOf(
                OpenApiCoverageConsoleRow("GET", "/route1", 200, 1, 100, CoverageStatus.COVERED),
                OpenApiCoverageConsoleRow("GET", "/route2", 200, 1, 50, CoverageStatus.NOT_IMPLEMENTED),
                OpenApiCoverageConsoleRow("GET", "/route2", 404, 0, 50, CoverageStatus.MISSING_IN_SPEC, showPath = false, showMethod = false)
            )
        )
    }

    @Test
    fun `identifies endpoint as 'missing in spec' when route+method is present in actuator but not present in the spec`() {
        val endpointsInSpec = mutableListOf(
            Endpoint("/route1", "GET", 200)
        )

        val applicationAPIs = mutableListOf(
            API("GET", "/route1"),
            API("GET", "/route2")
        )

        val contractTestResults = mutableListOf(
            TestResultRecord("/route1", "GET", 200, request = null, response = null, result = TestResult.Success)
        )

        val apiCoverageReport = OpenApiCoverageReportInput(
            CONFIG_FILE_PATH,
            contractTestResults,
            applicationAPIs,
            allEndpoints = endpointsInSpec,
            endpointsAPISet = true
        ).generate()
        assertThat(apiCoverageReport.coverageRows).isEqualTo(
            listOf(
                OpenApiCoverageConsoleRow("GET", "/route1", 200, 1, 100, CoverageStatus.COVERED),
                OpenApiCoverageConsoleRow("GET", "/route2", 0, 0, 0, CoverageStatus.MISSING_IN_SPEC)
            )
        )
    }

    @Test
    fun `identifies endpoint as 'Not Covered' when contract test is not generated for an endpoint present in the spec`() {
        val endpointsInSpec = mutableListOf(
            Endpoint("/route1", "GET", 200),
            Endpoint("/route1", "GET", 400),
        )

        val applicationAPIs = mutableListOf(
            API("GET", "/route1")
        )

        val contractTestResults = mutableListOf(
            TestResultRecord("/route1", "GET", 200, request = null, response = null, result = TestResult.Success)
        )

        val apiCoverageReport = OpenApiCoverageReportInput(
            CONFIG_FILE_PATH,
            contractTestResults,
            applicationAPIs,
            allEndpoints = endpointsInSpec,
            filteredEndpoints = endpointsInSpec,
            endpointsAPISet = true
        ).generate()
        assertThat(apiCoverageReport.coverageRows).isEqualTo(
            listOf(
                OpenApiCoverageConsoleRow("GET", "/route1", 200, 1, 50, CoverageStatus.COVERED),
                OpenApiCoverageConsoleRow("GET", "/route1", 400, 0, 50, CoverageStatus.NOT_COVERED, showPath = false, showMethod = false)
            )
        )
    }
}