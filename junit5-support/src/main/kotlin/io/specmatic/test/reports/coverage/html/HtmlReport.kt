package io.specmatic.test.reports.coverage.html

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.SuccessCriteria
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.reports.renderers.GroupedScenarioData
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

class HtmlReport(private val htmlReportInformation: HtmlReportInformation, private val baseDir: String? = null) {
    private val apiSuccessCriteria = htmlReportInformation.successCriteria
    private var totalErrors = 0
    private var totalFailures = 0
    private var totalSkipped = 0
    private var totalSuccess = 0
    private var totalMissing = 0

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

    }

    private fun generatedOnTimestamp(): String {
        val currentDateTime = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("EEE, MMM dd yyyy h:mma", Locale.ENGLISH)
        return currentDateTime.format(formatter)
    }

    private fun getHtmlResultAndBadgeColor(tableRow: TableRow): Pair<HtmlResult, String> {
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

    private fun dumpTestData(testData: GroupedScenarioData) {
        val mapper = ObjectMapper()
        mapper.enable(SerializationFeature.INDENT_OUTPUT)
    }
}

data class HtmlReportInformation(
    val specmaticConfig: SpecmaticConfig,
    val successCriteria: SuccessCriteria,
    val specmaticImplementation: String,
    val specmaticVersion: String,
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
    val result: CoverageStatus,
    var htmlResult: HtmlResult? = null,
    var badgeColor: String? = null
)

enum class HtmlResult {
    Success,
    Failed,
    Error,
    Skipped
}
