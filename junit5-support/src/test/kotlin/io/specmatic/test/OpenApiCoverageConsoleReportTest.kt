package io.specmatic.test

import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.test.reports.coverage.console.OpenAPICoverageConsoleReport
import io.specmatic.test.reports.coverage.console.OpenApiCoverageConsoleRow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenApiCoverageConsoleReportTest {

    @Test
    fun `test calculates total percentage based on number of exercised endpoints`() {
        val rows = listOf(
            OpenApiCoverageConsoleRow("GET", "/route1", 200, 1, 50, CoverageStatus.COVERED),
            OpenApiCoverageConsoleRow("POST", "/route1", 200, 0, 0, CoverageStatus.MISSING_IN_SPEC),
            OpenApiCoverageConsoleRow("GET", "/route2", 200, 1, 25, CoverageStatus.COVERED),
            OpenApiCoverageConsoleRow("POST", "/route2", 200, 0, 0, CoverageStatus.NOT_COVERED),
            OpenApiCoverageConsoleRow("POST", "/route2", 400, 0, 0, CoverageStatus.NOT_COVERED),
            OpenApiCoverageConsoleRow("POST", "/route2", 500, 0, 0, CoverageStatus.NOT_COVERED),
            OpenApiCoverageConsoleRow("GET", "/route3", 200, 0, 0, CoverageStatus.NOT_COVERED),
        )

        val coverageReport = OpenAPICoverageConsoleReport(rows, emptyList(), totalEndpointsCount = 3, missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 1, partiallyNotImplementedAPICount = 0)

        assertThat(coverageReport.totalCoveragePercentage).isEqualTo(28)
    }

    @Test
    fun `should calculate overall coverage percentage based on exercised endpoints with WIP`() {
        val rows = listOf(
            OpenApiCoverageConsoleRow("GET", "/route1", 200, 2, 100, CoverageStatus.COVERED),
            OpenApiCoverageConsoleRow("GET", "/route1", 400, 2, 100, CoverageStatus.COVERED),
            OpenApiCoverageConsoleRow("GET", "/route1", 503, 1, 100, CoverageStatus.COVERED),
            OpenApiCoverageConsoleRow("GET", "/route2", 200, 2, 80, CoverageStatus.WIP),
            OpenApiCoverageConsoleRow("GET", "/route2", 400, 2, 80, CoverageStatus.WIP),
            OpenApiCoverageConsoleRow("POST", "/route2", 201, 1, 80, CoverageStatus.COVERED),
            OpenApiCoverageConsoleRow("POST", "/route2", 400, 6, 80, CoverageStatus.COVERED),
            OpenApiCoverageConsoleRow("POST", "/route2", 503, 0, 80, CoverageStatus.NOT_COVERED),
            OpenApiCoverageConsoleRow("POST", "/route3", 210, 12, 67, CoverageStatus.COVERED),
            OpenApiCoverageConsoleRow("POST", "/route3", 400, 65, 67, CoverageStatus.COVERED),
            OpenApiCoverageConsoleRow("POST", "/route3", 503, 0, 67, CoverageStatus.NOT_COVERED),
        )

        val coverageReport = OpenAPICoverageConsoleReport(rows, emptyList(), totalEndpointsCount = 3, missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 0, partiallyNotImplementedAPICount = 0)

        assertThat(coverageReport.totalCoveragePercentage).isEqualTo(81)
    }
}