package io.specmatic.test.reports.renderers

import io.specmatic.test.reports.coverage.console.OpenAPICoverageConsoleReport
import io.specmatic.test.reports.coverage.console.ReportColumn

class CoverageReportTextRenderer: ReportRenderer<OpenAPICoverageConsoleReport> {

    private fun pluralisePath(count: Int): String =
        "$count path${if (count == 1) "" else "s"}"

    private fun isOrAre(count: Int): String = if (count > 1) "are" else "is"

    private fun makeReportColumns(report: OpenAPICoverageConsoleReport): List<ReportColumn> {
        val maxCoveragePercentageLength = "coverage".length
        val maxPathLength = report.coverageRows.maxOf { it.path.length }
        val maxMethodLength = report.coverageRows.maxOf { it.method.length }
        val maxStatusLength = report.coverageRows.maxOf { it.responseStatus.length }
        val maxExercisedLength = "#exercised".length
        val maxRemarkLength = report.coverageRows.maxOf { it.remarks.toString().length }

        return buildList {
            add(ReportColumn("coverage", maxCoveragePercentageLength))
            add(ReportColumn("path", maxPathLength))
            if (report.isGherkinReport) {
                add(ReportColumn("soapAction", maxMethodLength))
            } else {
                add(ReportColumn("method", maxMethodLength))
                add(ReportColumn("response", maxStatusLength))
            }
            add(ReportColumn("#exercised", maxExercisedLength))
            add(ReportColumn("result", maxRemarkLength))
        }
    }

    private fun makeFooter(report: OpenAPICoverageConsoleReport): String {
        return "${report.totalCoveragePercentage}% API Coverage reported from ${report.totalEndpointsCount} Paths"
    }

}