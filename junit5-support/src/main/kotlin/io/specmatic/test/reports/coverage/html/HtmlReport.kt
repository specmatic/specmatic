package io.specmatic.test.reports.coverage.html

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import io.specmatic.core.ReportFormatter
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.SuccessCriteria
import io.specmatic.core.TestResult
import io.specmatic.test.reports.coverage.console.Remarks
import io.specmatic.test.reports.coverage.html.HtmlTemplateConfiguration.Companion.configureTemplateEngine
import io.specmatic.test.reports.renderers.GroupedScenarioData
import org.thymeleaf.context.Context
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

class HtmlReport(private val htmlReportInformation: HtmlReportInformation) {
    private val outputDirectory = htmlReportInformation.reportFormat.getOutputDirectoryOrDefault()
    private val apiSuccessCriteria = htmlReportInformation.successCriteria
    private val reportFormat = htmlReportInformation.reportFormat
    private val reportData = htmlReportInformation.reportData

    private var totalTests = 0
    private var totalErrors = 0
    private var totalFailures = 0
    private var totalSkipped = 0
    private var totalSuccess = 0
    private var totalMissing = 0

    fun generate() {
        createAssetsDir(outputDirectory)
        calculateTestGroupCounts(htmlReportInformation.reportData.scenarioData)

        val outFile = File(outputDirectory, "index.html")
        val htmlText = generateHtmlReportText()
        if (!outFile.parentFile.exists()) outFile.mkdirs()
        outFile.writer().use { it.write(htmlText) }
    }

    private fun generateHtmlReportText(): String {
        val testCriteria = testCriteriaPassed()
        val successCriteria = successCriteriaPassed(reportData.totalCoveragePercentage)
        // NOTE: Scenarios should be updated before updating TableRows
        val updatedScenarios = updateScenarioData(reportData.scenarioData)
        val updatedTableRows = updateTableRows(reportData.tableRows)

        val templateVariables = mapOf(
            "lite" to reportFormat.getLiteOrDefault(),
            "pageTitle" to reportFormat.getTitleOrDefault(),
            "reportHeading" to reportFormat.getHeadingOrDefault(),
            "logo" to reportFormat.getLogoOrDefault(),
            "logoAltText" to reportFormat.getLogoAltTextOrDefault(),
            "summaryResult" to if (testCriteria && successCriteria) "approved" else "rejected",
            "totalCoverage" to reportData.totalCoveragePercentage,
            "totalSuccess" to totalSuccess,
            "totalFailures" to totalFailures,
            "totalErrors" to totalErrors,
            "totalSkipped" to totalSkipped,
            "totalTests" to totalTests,
            "totalDuration" to reportData.totalTestDuration,
            "actuatorEnabled" to reportData.actuatorEnabled,
            "minimumCoverage" to apiSuccessCriteria.getMinThresholdPercentageOrDefault(),
            "successCriteriaPassed" to successCriteria,
            "testCriteriaPassed" to testCriteria,
            "tableConfig" to htmlReportInformation.tableConfig,
            "tableRows" to updatedTableRows,
            "specmaticImplementation" to htmlReportInformation.specmaticImplementation,
            "specmaticVersion" to htmlReportInformation.specmaticVersion,
            "generatedOn" to generatedOnTimestamp(),
            "isGherkinReport" to htmlReportInformation.isGherkinReport,
            "jsonTestData" to dumpTestData(updatedScenarios)
        )

        return configureTemplateEngine().process(
            "report",
            Context().apply { setVariables(templateVariables) }
        )
    }

    private fun updateTableRows(tableRows: List<TableRow>): List<TableRow> {
        tableRows.forEach {
            val (htmlResult, badgeColor) = getHtmlResultAndBadgeColor(it)
            it.htmlResult = htmlResult
            it.badgeColor = badgeColor
        }

        return tableRows
    }

    private fun updateScenarioData(scenarioData: GroupedScenarioData): GroupedScenarioData {
        scenarioData.forEach { (_, firstGroup) ->
            firstGroup.forEach { (_, secondGroup) ->
                secondGroup.forEach { (_, thirdGroup) ->
                    thirdGroup.forEach { (_, scenariosList) ->
                        scenariosList.forEach {
                            val htmlResult = categorizeResult(it)
                            val scenarioDetail = "${it.name} ${htmlResultToDetailPostFix(htmlResult)}"

                            it.htmlResult = htmlResult
                            it.details = if (it.details.isBlank()) scenarioDetail else "$scenarioDetail\n${it.details}"
                        }
                    }
                }
            }
        }

        return scenarioData
    }

    private fun testCriteriaPassed(): Boolean {
        // NOTE: Ignoring Errors, they'll only contain failing WIP Tests
        return totalFailures == 0
    }

    private fun successCriteriaPassed(totalCoveragePercentage: Int): Boolean {
        return totalCoveragePercentage >= apiSuccessCriteria.getMinThresholdPercentageOrDefault() || !apiSuccessCriteria.getEnforceOrDefault()
    }

    private fun calculateTestGroupCounts(scenarioData: GroupedScenarioData) {
        scenarioData.forEach { (_, firstGroup) ->
            firstGroup.forEach { (_, secondGroup) ->
                secondGroup.forEach { (_, thirdGroup) ->
                    thirdGroup.forEach { (_, scenariosList) ->
                        scenariosList.forEach {
                            when (it.testResult) {
                                TestResult.MissingInSpec -> totalMissing++
                                TestResult.NotCovered -> totalSkipped++
                                TestResult.Success -> totalSuccess++
                                TestResult.Error -> totalErrors++
                                else -> if (it.wip) totalErrors++ else totalFailures++
                            }
                        }
                    }
                }
            }
        }

        totalTests = when (reportFormat.getLiteOrDefault()) {
            true ->  totalSuccess + totalFailures + totalErrors
            else ->  totalSuccess + totalFailures + totalErrors + totalSkipped
        }
    }

    private fun generatedOnTimestamp(): String {
        val currentDateTime = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("EEE, MMM dd yyyy h:mma", Locale.ENGLISH)
        return currentDateTime.format(formatter)
    }

    private fun getHtmlResultAndBadgeColor(tableRow: TableRow): Pair<HtmlResult, String> {
        val scenarioList = reportData.scenarioData[tableRow.firstGroupValue]
            ?.get(tableRow.secondGroupValue)
            ?.values?.fold(emptyList<ScenarioData>()) { acc, rows -> acc + rows[tableRow.response].orEmpty() }
            .orEmpty()

        scenarioList.forEach {
            if (!it.valid) return Pair(it.htmlResult!!, "red")

            when (it.htmlResult) {
                HtmlResult.Failed -> return Pair(HtmlResult.Failed, "red")
                HtmlResult.Error -> return Pair(HtmlResult.Error, "yellow")
                HtmlResult.Skipped -> return Pair(HtmlResult.Skipped, "yellow")
                else -> {}
            }
        }
        return Pair(HtmlResult.Success, "green")
    }

    private fun htmlResultToDetailPostFix(htmlResult: HtmlResult): String {
        return when (htmlResult) {
            HtmlResult.Skipped -> "has been SKIPPED"
            HtmlResult.Error -> "has ERROR-ED"
            HtmlResult.Success -> "has SUCCEEDED"
            else -> "has FAILED"
        }
    }

    private fun categorizeResult(scenarioData: ScenarioData): HtmlResult {
        return when (scenarioData.testResult) {
            TestResult.Success -> HtmlResult.Success
            TestResult.NotCovered -> HtmlResult.Skipped
            TestResult.Error -> HtmlResult.Error
            else -> if (scenarioData.wip) HtmlResult.Error else HtmlResult.Failed
        }
    }

    private fun dumpTestData(testData: GroupedScenarioData): String {
        val mapper = ObjectMapper()
        val json = mapper.writeValueAsString(testData)
        mapper.enable(SerializationFeature.INDENT_OUTPUT)
        writeToFileToAssets(outputDirectory, "test_data.json", mapper.writeValueAsString(testData))
        return json
    }
}

data class HtmlReportInformation(
    val reportFormat: ReportFormatter,
    val specmaticConfig: SpecmaticConfig,
    val successCriteria: SuccessCriteria,
    val specmaticImplementation: String,
    val specmaticVersion: String,
    val tableConfig: HtmlTableConfig,
    val reportData: HtmlReportData,
    val isGherkinReport: Boolean
)

data class HtmlReportData(
    val totalCoveragePercentage: Int,
    val actuatorEnabled: Boolean,
    val totalTestDuration: Long,
    val tableRows: List<TableRow>,
    val scenarioData: GroupedScenarioData
)

data class HtmlTableConfig(
    val firstGroupName: String,
    val firstGroupColSpan: Int,
    val secondGroupName: String,
    val secondGroupColSpan: Int,
    val thirdGroupName: String,
    val thirdGroupColSpan: Int
)

data class ScenarioData(
    val name: String,
    val baseUrl: String,
    val duration: Long,
    val testResult: TestResult,
    val valid: Boolean,
    val wip: Boolean,
    val request: String,
    val requestTime: Long,
    val response: String,
    val responseTime: Long,
    val specFileName: String,
    var details: String,
    var htmlResult: HtmlResult? = null
)

data class TableRow(
    val coveragePercentage: Int,
    val firstGroupValue: String,
    val showFirstGroup: Boolean,
    val firstGroupRowSpan: Int,
    val secondGroupValue: String,
    val showSecondGroup: Boolean,
    val secondGroupRowSpan: Int,
    val requestContentType: String,
    val response: String,
    val exercised: Int,
    val result: Remarks,
    var htmlResult: HtmlResult? = null,
    var badgeColor: String? = null
)

enum class HtmlResult {
    Success,
    Failed,
    Error,
    Skipped
}
