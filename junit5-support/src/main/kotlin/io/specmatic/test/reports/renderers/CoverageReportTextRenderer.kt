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

        if(report.missedOperations > 0) {
            val missedOperations = pluraliseOperation(report.missedOperations)
            missingAndNotImplementedAPIsMessageRows.add("$missedOperations found in the service ${isOrAre(report.missedOperations)} not documented in the spec.")
        }

        if(report.notImplementedOperations > 0) {
            val notImplementedOperations = pluraliseOperation(report.notImplementedOperations)
            missingAndNotImplementedAPIsMessageRows.add("$notImplementedOperations found in the spec ${isOrAre(report.notImplementedOperations)} not implemented.")
        }

        return coveredAPIsTable + System.lineSeparator()  + missingAndNotImplementedAPIsMessageRows.joinToString(System.lineSeparator()) + System.lineSeparator()
    }
    private fun pluraliseOperation(count: Int): String =
        "$count operation${if (count == 1) "" else "s"}"

    private fun isOrAre(count: Int): String = if (count > 1) "are" else "is"

    private fun makeReportColumns(report: OpenAPICoverageConsoleReport): List<ReportColumn> {
        val maxCoveragePercentageLength = "coverage".length
        val maxPathLength = report.coverageRows.maxOf { it.path.length }
        val maxMethodLength = report.coverageRows.maxOf { it.method.length }
        val maxReqContentTypeLength = report.coverageRows.maxOf { (it.requestContentType ?: "NA").length }
        val maxStatusLength = report.coverageRows.maxOf { it.responseStatus.length }
        val maxResContentTypeLength = report.coverageRows.maxOf { (it.responseContentType ?: "NA").length }
        val maxRemarkLength = report.coverageRows.maxOf {
            when {
                !it.eligibleForCoverage && it.excludedFromRun -> "${it.remarks}I".length
                !it.eligibleForCoverage -> "${it.remarks}*".length
                else -> it.remarks.toString().length
            }
        }
        val maxResultLength = maxOf("result".length, report.coverageRows.maxOf { it.result.length })

        return buildList {
            add(ReportColumn("coverage", maxCoveragePercentageLength))

            if (report.isGherkinReport) {
                add(ReportColumn("port", maxPathLength))
                add(ReportColumn("soapAction", maxMethodLength))
            } else {
                add(ReportColumn("path", maxPathLength))
                add(ReportColumn("method", maxMethodLength))
                add(ReportColumn("requestContentType", maxReqContentTypeLength))
                add(ReportColumn("response", maxStatusLength))
                add(ReportColumn("responseContentType", maxResContentTypeLength))
            }

            add(ReportColumn("remarks", maxRemarkLength))
            add(ReportColumn("result", maxResultLength))
        }
    }

    private fun makeFooter(report: OpenAPICoverageConsoleReport): List<String> {
        return listOf(
            "* = Operation not eligible for coverage",
            "I = Operation excluded from run by the filter expression",
            "p = passed tests",
            "f = failed tests",
            "",
            "${report.coveragePercentage}% API Coverage reported from ${report.operationsEligibleForCoverage} operations eligible for coverage",
            "${report.absoluteCoveragePercentage}% Absolute Coverage (includes excluded operations that were not tested)"
        )
    }
}
