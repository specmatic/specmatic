package io.specmatic.test

import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.test.reports.coverage.console.OpenApiCoverageConsoleRow
import io.specmatic.test.reports.coverage.console.ReportColumn
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenApiCoverageConsoleRowTest {

    companion object {
        val reportColumns = listOf(
            ReportColumn("coverage", 8),
            ReportColumn("path", 7),
            ReportColumn("method", 3),
            ReportColumn("response", 3),
            ReportColumn("#exercised", 10),
            ReportColumn("result", 7)
        )
    }

    @Test
    fun `test renders coverage percentage, path, method, response, count for top level row of covered endpoint`() {
        val path  = "/route1"
        val coverageRowString = OpenApiCoverageConsoleRow("GET", path, 200, 1, 100, CoverageStatus.COVERED).toRowString(
            reportColumns
        )
        println(coverageRowString)
        assertThat(coverageRowString).isEqualTo("| 100%     | /route1 | GET    | 200      | 1          | covered |")
    }

    @Test
    fun `test renders path, method, response, count with coverage percentage blank for sub level row of covered endpoint`() {
        val path  = "/route1"
        val coverageRowString = OpenApiCoverageConsoleRow("POST", "", 200, 1, 0, CoverageStatus.MISSING_IN_SPEC, showPath = false).toRowString(
            reportColumns
        )
        println(coverageRowString)
        assertThat(coverageRowString).isEqualTo("|          |         | POST   | 200      | 1          | missing in spec |")
    }

    @Test
    fun `test renders coverage percentage, path, method, with response, count for top level row of missed endpoint`() {
        val path  = "/route1"
        val coverageRowString = OpenApiCoverageConsoleRow("GET", path, 0, 0, 0, CoverageStatus.MISSING_IN_SPEC).toRowString(
            reportColumns
        )
        println(coverageRowString)
        assertThat(coverageRowString).isEqualTo("| 0%       | /route1 | GET    | 0        | 0          | missing in spec |")
    }


    @Test
    fun `test renders coverage percentage, path, method, with response, count for sub level row of missed endpoint`() {
        val path  = "/route1"
        val coverageRowString = OpenApiCoverageConsoleRow("POST", "/route1", 0, 1, 0, CoverageStatus.MISSING_IN_SPEC, showPath = false).toRowString(
            reportColumns
        )
        println(coverageRowString)
        assertThat(coverageRowString).isEqualTo("|          |         | POST   | 0        | 1          | missing in spec |")
    }

    @Test
    fun `test renders coverage percentage, path, method, with response, count for top level row of not implemented endpoint`() {
        val path  = "/route1"
        val coverageRowString = OpenApiCoverageConsoleRow("GET", path, 0, 0, 0, CoverageStatus.NOT_IMPLEMENTED).toRowString(
            reportColumns
        )
        println(coverageRowString)
        assertThat(coverageRowString).isEqualTo("| 0%       | /route1 | GET    | 0        | 0          | not implemented |")
    }

    @Test
    fun `test renders coverage percentage, path, method, with response, count for sub level row of not implemented endpoint`() {
        val path  = "/route1"
        val coverageRowString = OpenApiCoverageConsoleRow("GET", "/route1", 200, 0, 0, CoverageStatus.NOT_IMPLEMENTED, showPath = false).toRowString(
            reportColumns
        )
        println(coverageRowString)
        assertThat(coverageRowString).isEqualTo("|          |         | GET    | 200      | 0          | not implemented |")
    }
}
