package io.specmatic.test.reports

import io.specmatic.core.ReportConfiguration
import io.specmatic.core.Result
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.log.logger
import io.specmatic.test.reports.coverage.OpenApiCoverageReport
import io.specmatic.test.reports.coverage.console.OpenAPICoverageConsoleReport
import io.specmatic.test.reports.renderers.CoverageReportTextRenderer
import org.assertj.core.api.Assertions.assertThat

class OpenApiCoverageReportProcessor(private val openApiCoverageReport: OpenApiCoverageReport): ReportProcessor<OpenAPICoverageConsoleReport> {
    override fun process(specmaticConfig: SpecmaticConfig) {
        val reportConfiguration = specmaticConfig.getReport()!!
        val openApiConsoleReport = openApiCoverageReport.toConsoleReport()
        if (openApiConsoleReport.coverageRows.isEmpty()) {
            logger.log("The Open API coverage report generated is blank.\nThis can happen if your open api specification does not have any paths documented.")
        } else {
            logger.log(CoverageReportTextRenderer().render(openApiConsoleReport, specmaticConfig))
        }
        assertSuccessCriteria(reportConfiguration, openApiConsoleReport)
    }

    override fun assertSuccessCriteria(
        reportConfiguration: ReportConfiguration,
        report: OpenAPICoverageConsoleReport
    ) {
        val successCriteria = reportConfiguration.getSuccessCriteria()
        if (successCriteria.getEnforceOrDefault()) {
            val coverageThresholdNotMetMessage =
                "Total API coverage: ${report.coveragePercentage}% is less than the specified minimum threshold of ${successCriteria.getMinThresholdPercentageOrDefault()}%."
            val missedOperationsExceededMessage =
                "Total missed operations: ${report.missedOperations} is greater than the maximum threshold of ${successCriteria.getMaxMissedEndpointsInSpecOrDefault()}."

            val minCoverageThresholdCriteriaMet =
                report.coveragePercentage >= successCriteria.getMinThresholdPercentageOrDefault()
            val maxMissingOperationsExceededCriteriaMet =
                report.missedOperations <= successCriteria.getMaxMissedEndpointsInSpecOrDefault()
            val coverageReportSuccessCriteriaMet = minCoverageThresholdCriteriaMet && maxMissingOperationsExceededCriteriaMet
            if(!coverageReportSuccessCriteriaMet){
                logger.newLine()
                logger.log("Failed the following API Coverage Report success criteria:")
                if(!minCoverageThresholdCriteriaMet) {
                    logger.log(coverageThresholdNotMetMessage)
                }
                if(!maxMissingOperationsExceededCriteriaMet) {
                    logger.log(missedOperationsExceededMessage)
                }
                logger.newLine()
            }

            val results = buildList {
                if (!minCoverageThresholdCriteriaMet) add(Result.Failure(coverageThresholdNotMetMessage))
                if (!maxMissingOperationsExceededCriteriaMet) add(Result.Failure(missedOperationsExceededMessage))
            }

            openApiCoverageReport.onGovernanceResult(Result.fromResults(results))
            assertThat(coverageReportSuccessCriteriaMet).withFailMessage("One or more API Coverage report's success criteria were not met.").isTrue
        }
    }
}
