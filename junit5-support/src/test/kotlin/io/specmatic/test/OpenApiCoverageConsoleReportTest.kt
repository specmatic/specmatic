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
            OpenApiCoverageConsoleRow("POST", "/route1", 200, 0, 0, CoverageStatus.NOT_COVERED),
            OpenApiCoverageConsoleRow("GET", "/route2", 200, 1, 25, CoverageStatus.COVERED),
            OpenApiCoverageConsoleRow("POST", "/route2", 200, 0, 0, CoverageStatus.NOT_COVERED),
            OpenApiCoverageConsoleRow("POST", "/route2", 400, 0, 0, CoverageStatus.NOT_COVERED),
            OpenApiCoverageConsoleRow("POST", "/route2", 500, 0, 0, CoverageStatus.NOT_COVERED),
            OpenApiCoverageConsoleRow("GET", "/route3", 200, 0, 0, CoverageStatus.NOT_COVERED),
        )

        val coverageReport = OpenAPICoverageConsoleReport(rows, emptyList(), totalOperations = 3, missedOperations = 0, notImplementedOperations = 0)

        assertThat(coverageReport.totalCoveragePercentage).isEqualTo(29)
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

        val coverageReport = OpenAPICoverageConsoleReport(rows, emptyList(), totalOperations = 3, missedOperations = 0, notImplementedOperations = 0)

        assertThat(coverageReport.totalCoveragePercentage).isEqualTo(82)
    }

    @Test
    fun `test does not include endpoints missing in spec when calculating coverage percentage`() {
        val rows = listOf(
            OpenApiCoverageConsoleRow("GET", "/route1", 200, 1, 100, CoverageStatus.COVERED),
            OpenApiCoverageConsoleRow("POST", "/route1", 200, 1, 100, CoverageStatus.COVERED),
            OpenApiCoverageConsoleRow("GET", "/route2", 200, 1, 100, CoverageStatus.COVERED),
            OpenApiCoverageConsoleRow("POST", "/route2", 200, 1, 100, CoverageStatus.COVERED),
            OpenApiCoverageConsoleRow("GET", "/route3", 200, 1, 0, CoverageStatus.MISSING_IN_SPEC),
        )

        val coverageReport = OpenAPICoverageConsoleReport(rows, emptyList(), totalOperations = 3, missedOperations = 0, notImplementedOperations = 0)

        assertThat(coverageReport.totalCoveragePercentage).isEqualTo(100)
    }

    @Test
    fun `test does not count not implemented endpoints as covered when calculating total percentage`() {
        val rows = listOf(
            OpenApiCoverageConsoleRow("GET", "/route1", 200, 1, 100, CoverageStatus.NOT_IMPLEMENTED),
            OpenApiCoverageConsoleRow("GET", "/route2", 200, 1, 100, CoverageStatus.COVERED),
        )

        val coverageReport = OpenAPICoverageConsoleReport(
            rows,
            emptyList(),
            totalOperations = 2,
            missedOperations = 0,
            notImplementedOperations = 1
        )

        assertThat(coverageReport.totalCoveragePercentage).isEqualTo(50)
    }

    @Test
    fun `test calculates zero total percentage when all exercised endpoints are not implemented`() {
        val rows = listOf(
            OpenApiCoverageConsoleRow("GET", "/route1", 200, 1, 0, CoverageStatus.NOT_IMPLEMENTED),
            OpenApiCoverageConsoleRow("POST", "/route1", 400, 1, 0, CoverageStatus.NOT_IMPLEMENTED, showPath = false),
        )

        val coverageReport = OpenAPICoverageConsoleReport(
            rows,
            emptyList(),
            totalOperations = 1,
            missedOperations = 0,
            notImplementedOperations = 1
        )

        assertThat(coverageReport.totalCoveragePercentage).isEqualTo(0)
    }
}
