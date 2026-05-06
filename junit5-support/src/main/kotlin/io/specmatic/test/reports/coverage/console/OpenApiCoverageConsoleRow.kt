package io.specmatic.test.reports.coverage.console

import io.specmatic.test.reports.coverage.OpenApiCoverageReportOperation
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.internal.dto.coverage.OmittedStatus
import io.specmatic.reporter.model.TestResult

data class OpenApiCoverageConsoleRow(
    val method: String,
    val path: String,
    val responseStatus: String,
    val exercisedCount: Int,
    val result: String,
    val coveragePercentage: Int = 0,
    val remarks: CoverageStatus,
    val eligibleForCoverage: Boolean,
    val omittedStatus: OmittedStatus = OmittedStatus.NONE,
    val showPath: Boolean = true,
    val showMethod: Boolean = true,
    val requestContentType: String? = null,
    val showRequestContentType: Boolean = true,
    val responseContentType: String? = null,
): CoverageRow {
    constructor(
        coverageReportOperation: OpenApiCoverageReportOperation,
        coveragePercentage: Int,
        showPath: Boolean,
        showMethod: Boolean,
        showRequestContentType: Boolean
    ) : this(
        method = coverageReportOperation.operation.method,
        path = coverageReportOperation.operation.path,
        responseStatus = coverageReportOperation.operation.responseCode.toString(),
        exercisedCount = coverageReportOperation.metrics?.attempts ?: coverageReportOperation.tests.size,
        result = formatResult(
            passedCount = coverageReportOperation.tests.count { it.result == TestResult.Success },
            failedCount = coverageReportOperation.tests.count { it.result == TestResult.Failed }
        ),
        coveragePercentage = coveragePercentage,
        remarks = coverageReportOperation.coverageStatus,
        eligibleForCoverage = coverageReportOperation.eligibleForCoverage,
        omittedStatus = coverageReportOperation.omittedStatus,
        showPath = showPath,
        showMethod = showMethod,
        showRequestContentType = showRequestContentType,
        requestContentType = coverageReportOperation.operation.contentType,
        responseContentType = coverageReportOperation.operation.responseContentType
    )

    constructor(
        method: String,
        path: String,
        responseStatus: Int,
        count: Int,
        coveragePercentage: Int,
        remarks: CoverageStatus,
        eligibleForCoverage: Boolean = true,
        omittedStatus: OmittedStatus = OmittedStatus.NONE,
        showPath: Boolean = true,
        showMethod: Boolean = true,
        requestContentType: String? = null,
        showRequestContentType: Boolean = true,
        responseContentType: String? = null,
    ) : this(
        method,
        path,
        responseStatus.toString(),
        count,
        formatResult(passedCount = count, failedCount = 0),
        coveragePercentage,
        remarks,
        eligibleForCoverage,
        omittedStatus,
        showPath,
        showMethod,
        requestContentType,
        showRequestContentType,
        responseContentType
    )

    private val formattedCoveragePercentage: String
        get() = if (showPath) "$coveragePercentage%" else ""

    private val formattedPathName: String
        get() = if (showPath) path else ""

    private val formattedMethodName: String
        get() = if (showMethod) method else ""

    private val formattedRequestContentType: String
        get() = if (showRequestContentType) requestContentType ?: "NA" else ""

    private val formattedResponseContentType: String
        get() = responseContentType ?: "NA"

    private val formattedRemark: String
        get() = "${remarks}${remarkMarkers()}"

    private fun remarkMarkers(): String {
        if (eligibleForCoverage) return ""

        return buildString {
            append("*")
            if (omittedStatus == OmittedStatus.EXCLUDED) {
                append("!")
            }
        }
    }

    override fun toRowString(tableColumns: List<ReportColumn>): String {
        return tableColumns.joinToString(separator = " | ", postfix = " |", prefix = "| ") { column ->
            val value = when (column.name) {
                "coverage" -> formattedCoveragePercentage
                "path", "port" -> formattedPathName
                "method", "soapAction" -> formattedMethodName
                "requestContentType" -> formattedRequestContentType
                "response" -> responseStatus
                "responseContentType" -> formattedResponseContentType
                "result" -> result
                "remark", "remarks" -> formattedRemark
                else -> throw Exception("Unknown column name: ${column.name}")
            }
            column.columnFormat.format(value)
        }
    }

    internal fun formattedRemarkLength(): Int = formattedRemark.length

    companion object {
        private fun formatResult(passedCount: Int, failedCount: Int): String {
            return buildList {
                if (passedCount > 0) add("${passedCount}p")
                if (failedCount > 0) add("${failedCount}f")
            }.joinToString(" ")
        }
    }
}
