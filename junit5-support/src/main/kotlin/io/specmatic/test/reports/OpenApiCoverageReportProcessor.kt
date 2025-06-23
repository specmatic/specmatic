package io.specmatic.test.reports

import io.specmatic.core.ReportConfiguration
import io.specmatic.core.ReportFormatterType
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.log.logger
import io.specmatic.test.reports.coverage.OpenApiCoverageReportInput
import io.specmatic.test.reports.coverage.console.OpenAPICoverageConsoleReport
import io.specmatic.test.reports.coverage.json.OpenApiCoverageJsonReport
import io.specmatic.test.reports.renderers.CoverageReportHtmlRenderer
import io.specmatic.test.reports.renderers.CoverageReportTextRenderer
import io.specmatic.test.reports.renderers.ReportRenderer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import java.io.File

class OpenApiCoverageReportProcessor (private val openApiCoverageReportInput: OpenApiCoverageReportInput ): ReportProcessor<OpenAPICoverageConsoleReport> {
    companion object {
        const val JSON_REPORT_PATH = "./build/reports/specmatic"
        const val JSON_REPORT_FILE_NAME = "coverage_report.json"
    }

    override fun process(specmaticConfig: SpecmaticConfig) {
        val reportConfiguration = specmaticConfig.getReport()!!

        openApiCoverageReportInput.addExcludedAPIs(reportConfiguration.excludedOpenAPIEndpoints() + excludedEndpointsFromEnv())
        val openAPICoverageReport = openApiCoverageReportInput.generate()

        if (openAPICoverageReport.coverageRows.isEmpty()) {
            logger.log("The Open API coverage report generated is blank.\nThis can happen if your open api specification does not have any paths documented.")
        } else {
            val renderers = configureReportRenderers(reportConfiguration)
            renderers.forEach { renderer ->
                logger.log(renderer.render(openAPICoverageReport, specmaticConfig))
            }
            saveAsJson(openApiCoverageReportInput.generateJsonReport())
        }
        assertSuccessCriteria(reportConfiguration, openAPICoverageReport)
    }

    override fun configureReportRenderers(reportConfiguration: ReportConfiguration): List<ReportRenderer<OpenAPICoverageConsoleReport>> {
        return reportConfiguration.mapRenderers { formatterType: ReportFormatterType ->
            when (formatterType) {
                ReportFormatterType.TEXT -> CoverageReportTextRenderer()
                ReportFormatterType.HTML -> CoverageReportHtmlRenderer()
                else -> throw Exception("Report formatter type: $formatterType is not supported")
            }
        }
    }

    private fun excludedEndpointsFromEnv() = System.getenv("SPECMATIC_EXCLUDED_ENDPOINTS")?.let { excludedEndpoints ->
        excludedEndpoints.split(",").map { it.trim() }
    } ?: emptyList()

    private fun saveAsJson(openApiCoverageJsonReport: OpenApiCoverageJsonReport) {
        println("Saving Coverage Report json to $JSON_REPORT_PATH ...")
        val json = Json {
            encodeDefaults = false
        }
        val reportJson = json.encodeToString(openApiCoverageJsonReport)
        val directory = File(JSON_REPORT_PATH)
        directory.mkdirs()
        val file = File(directory, JSON_REPORT_FILE_NAME)
        file.writeText(reportJson)
    }

    override fun assertSuccessCriteria(
        reportConfiguration: ReportConfiguration,
        report: OpenAPICoverageConsoleReport
    ) {
        val successCriteria = reportConfiguration.getSuccessCriteria()
        if (successCriteria.getEnforceOrDefault()) {
            val coverageThresholdNotMetMessage =
                "Total API coverage: ${report.totalCoveragePercentage}% is less than the specified minimum threshold of ${successCriteria.getMinThresholdPercentageOrDefault()}%. "
            val missedEndpointsCountExceededMessage =
                "Total missed endpoints count: ${report.missedEndpointsCount} is greater than the maximum threshold of ${successCriteria.getMaxMissedEndpointsInSpecOrDefault()}.\n(Note: Specmatic will consider an endpoint as 'covered' only if it is documented in the open api spec with at least one example for each operation and response code.\nIf it is present in the spec, but does not have an example, Specmatic will still report the particular operation and response code as 'missing in spec'.)"

            val minCoverageThresholdCriteriaMet =
                report.totalCoveragePercentage >= successCriteria.getMinThresholdPercentageOrDefault()
            val maxMissingEndpointsExceededCriteriaMet =
                report.missedEndpointsCount <= successCriteria.getMaxMissedEndpointsInSpecOrDefault()
            val coverageReportSuccessCriteriaMet = minCoverageThresholdCriteriaMet && maxMissingEndpointsExceededCriteriaMet
            if(!coverageReportSuccessCriteriaMet){
                logger.newLine()
                logger.log("Failed the following API Coverage Report success criteria:")
                if(!minCoverageThresholdCriteriaMet) {
                    logger.log(coverageThresholdNotMetMessage)
                }
                if(!maxMissingEndpointsExceededCriteriaMet) {
                    logger.log(missedEndpointsCountExceededMessage)
                }
                logger.newLine()
            }
            assertThat(coverageReportSuccessCriteriaMet).withFailMessage("One or more API Coverage report's success criteria were not met.").isTrue
        }
    }
}