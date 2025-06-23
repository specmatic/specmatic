package io.specmatic.test.reports.renderers

import io.specmatic.core.SpecmaticConfig
import io.specmatic.test.reports.coverage.console.ConsoleReport
import io.specmatic.test.reports.coverage.console.OpenAPICoverageConsoleReport
import io.specmatic.test.reports.coverage.console.ReportColumn

class CoverageReportTextRenderer: ReportRenderer<OpenAPICoverageConsoleReport> {
    override fun render(report: OpenAPICoverageConsoleReport, specmaticConfig: SpecmaticConfig): String {
        val textReportGenerator = ConsoleReport(report.coverageRows, makeReportColumns(report), makeFooter(report))
        val coveredAPIsTable = "${System.lineSeparator()}${textReportGenerator.generate()}"

        val missingAndNotImplementedAPIsMessageRows:MutableList<String> = mutableListOf()

        if(report.missedEndpointsCount > 0) {
            val missedPaths = pluralisePath(report.missedEndpointsCount)
            missingAndNotImplementedAPIsMessageRows.add("$missedPaths found in the app ${isOrAre(report.missedEndpointsCount)} not documented in the spec.")
        }

        if(report.partiallyMissedEndpointsCount > 0) {
            val partiallyMissedPaths = pluralisePath(report.partiallyMissedEndpointsCount)
            missingAndNotImplementedAPIsMessageRows.add("$partiallyMissedPaths found in the app ${isOrAre(report.partiallyMissedEndpointsCount)} partially documented in the spec.")
        }

        if(report.notImplementedAPICount > 0) {
            val notImplementedPaths = pluralisePath(report.notImplementedAPICount)
            missingAndNotImplementedAPIsMessageRows.add("$notImplementedPaths found in the spec ${isOrAre(report.notImplementedAPICount)} not implemented.")
        }

        if(report.partiallyNotImplementedAPICount > 0) {
            val partiallyNotImplementedPaths = pluralisePath(report.partiallyNotImplementedAPICount)
            missingAndNotImplementedAPIsMessageRows.add("$partiallyNotImplementedPaths found in the spec ${isOrAre(report.partiallyNotImplementedAPICount)} partially implemented.")
        }

        return coveredAPIsTable + System.lineSeparator()  + missingAndNotImplementedAPIsMessageRows.joinToString(System.lineSeparator()) + System.lineSeparator()
    }

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