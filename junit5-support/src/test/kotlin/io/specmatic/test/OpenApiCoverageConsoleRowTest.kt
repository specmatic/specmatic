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
            ReportColumn("method", 6),
            ReportColumn("requestContentType", 18),
            ReportColumn("response", 8),
            ReportColumn("responseContentType", 20),
            ReportColumn("remarks", 14),
            ReportColumn("result", 10),
        )
    }

    @Test
    fun `test renders coverage percentage, path, method, response, count for top level row of covered endpoint`() {
        val coverageRowString = OpenApiCoverageConsoleRow("GET", "/route1", 200, 1, 100, CoverageStatus.COVERED).toRowString(reportColumns)
        println(coverageRowString)
        assertThat(coverageRowString).isEqualTo("| 100%     | /route1 | GET    | NA                 | 200      | NA                   | covered        | 1p         |")
    }

    @Test
    fun `test renders path, method, response, count with coverage percentage blank for sub level row of covered endpoint`() {
        val coverageRowString = OpenApiCoverageConsoleRow("POST", "", 200, 1, 0, CoverageStatus.MISSING_IN_SPEC, showPath = false).toRowString(reportColumns)
        println(coverageRowString)
        assertThat(coverageRowString).isEqualTo("|          |         | POST   | NA                 | 200      | NA                   | missing in spec | 1p         |")
    }

    @Test
    fun `test renders coverage percentage, path, method, with response, count for top level row of missed endpoint`() {
        val coverageRowString = OpenApiCoverageConsoleRow("GET", "/route1", 0, 0, 0, CoverageStatus.MISSING_IN_SPEC).toRowString(reportColumns)
        println(coverageRowString)
        assertThat(coverageRowString).isEqualTo("| 0%       | /route1 | GET    | NA                 | 0        | NA                   | missing in spec |            |")
    }


    @Test
    fun `test renders coverage percentage, path, method, with response, count for sub level row of missed endpoint`() {
        val coverageRowString = OpenApiCoverageConsoleRow("POST","/route1", 0, 1, 0, CoverageStatus.MISSING_IN_SPEC, showPath = false).toRowString(reportColumns)
        println(coverageRowString)
        assertThat(coverageRowString).isEqualTo("|          |         | POST   | NA                 | 0        | NA                   | missing in spec | 1p         |")
    }

    @Test
    fun `test renders coverage percentage, path, method, with response, count for top level row of not implemented endpoint`() {
        val coverageRowString = OpenApiCoverageConsoleRow("GET", "/route1", 0, 0, 0, CoverageStatus.NOT_IMPLEMENTED).toRowString(reportColumns)
        println(coverageRowString)
        assertThat(coverageRowString).isEqualTo("| 0%       | /route1 | GET    | NA                 | 0        | NA                   | not implemented |            |")
    }

    @Test
    fun `test renders coverage percentage, path, method, with response, count for sub level row of not implemented endpoint`() {
        val coverageRowString = OpenApiCoverageConsoleRow("GET", "/route1", 200, 0, 0, CoverageStatus.NOT_IMPLEMENTED, showPath = false).toRowString(reportColumns)
        println(coverageRowString)
        assertThat(coverageRowString).isEqualTo("|          |         | GET    | NA                 | 200      | NA                   | not implemented |            |")
    }

    @Test
    fun `renders request and response content types when visible`() {
        val coverageRowString = OpenApiCoverageConsoleRow(
            method = "POST",
            path = "/orders",
            responseStatus = 201,
            count = 1,
            coveragePercentage = 100,
            remarks = CoverageStatus.COVERED,
            requestContentType = "application/json",
            responseContentType = "application/problem+json"
        ).toRowString(reportColumns)
        assertThat(coverageRowString).isEqualTo("| 100%     | /orders | POST   | application/json   | 201      | application/problem+json | covered        | 1p         |")
    }

    @Test
    fun `hides request content type for grouped sub rows when configured`() {
        val coverageRowString = OpenApiCoverageConsoleRow(
            method = "POST",
            path = "/orders",
            responseStatus = 400,
            count = 1,
            coveragePercentage = 100,
            remarks = CoverageStatus.COVERED,
            showPath = false,
            showMethod = false,
            showRequestContentType = false,
            requestContentType = "application/json",
            responseContentType = "application/problem+json"
        ).toRowString(reportColumns)
        assertThat(coverageRowString).isEqualTo("|          |         |        |                    | 400      | application/problem+json | covered        | 1p         |")
    }

    @Test
    fun `renders NA when request content type is not available`() {
        val coverageRowString = OpenApiCoverageConsoleRow(
            method = "GET",
            path = "/orders",
            responseStatus = 200,
            count = 1,
            coveragePercentage = 100,
            remarks = CoverageStatus.COVERED,
            requestContentType = null,
            responseContentType = "application/json"
        ).toRowString(reportColumns)
        assertThat(coverageRowString).isEqualTo("| 100%     | /orders | GET    | NA                 | 200      | application/json     | covered        | 1p         |")
    }

    @Test
    fun `renders NA when response content type is not available`() {
        val coverageRowString = OpenApiCoverageConsoleRow(
            method = "GET",
            path = "/orders",
            responseStatus = 200,
            count = 1,
            coveragePercentage = 100,
            remarks = CoverageStatus.COVERED,
            requestContentType = "application/json",
            responseContentType = null
        ).toRowString(reportColumns)
        assertThat(coverageRowString).isEqualTo("| 100%     | /orders | GET    | application/json   | 200      | NA                   | covered        | 1p         |")
    }
}
