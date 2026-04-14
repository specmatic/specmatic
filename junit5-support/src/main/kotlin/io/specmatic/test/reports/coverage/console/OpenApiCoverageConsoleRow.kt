package io.specmatic.test.reports.coverage.console

import io.specmatic.core.report.OpenApiCoverageReportOperation
import io.specmatic.reporter.ctrf.model.CoverageReportOperation
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.test.TestResultRecord

data class OpenApiCoverageConsoleRow(
    val method: String,
    val path: String,
    val responseStatus: String,
    val count: String,
    val coveragePercentage: Int = 0,
    val remarks: CoverageStatus,
    val showPath: Boolean = true,
    val showMethod: Boolean = true,
    val requestContentType: String? = null,
    val showRequestContentType: Boolean = true,
    val responseContentType: String? = null,
): CoverageRow {
    constructor(
        report: OpenApiCoverageReportOperation,
        coveragePercentage: Int,
        showPath: Boolean,
        showMethod: Boolean,
        showRequestContentType: Boolean
    ) : this(
        method = report.operation.method,
        path = report.operation.path,
        responseStatus = report.operation.responseCode.toString(),
        count = report.tests.size.toString(),
        coveragePercentage = coveragePercentage,
        remarks = report.coverageStatus,
        showPath = showPath,
        showMethod = showMethod,
        showRequestContentType = showRequestContentType,
        requestContentType = report.operation.contentType,
        responseContentType = report.operation.responseContentType
    )

    constructor(
        method: String,
        path: String,
        responseStatus: Int,
        count: Int,
        coveragePercentage: Int,
        remarks: CoverageStatus,
        showPath: Boolean = true,
        showMethod: Boolean = true,
        requestContentType: String? = null,
        showRequestContentType: Boolean = true,
        responseContentType: String? = null,
    ) : this(method, path, responseStatus.toString(), count.toString(), coveragePercentage, remarks, showPath, showMethod, requestContentType, showRequestContentType, responseContentType)

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

    override fun toRowString(tableColumns: List<ReportColumn>): String {
        return tableColumns.joinToString(separator = " | ", postfix = " |", prefix = "| ") { column ->
            val value = when (column.name) {
                "coverage" -> formattedCoveragePercentage
                "path", "port" -> formattedPathName
                "method", "soapAction" -> formattedMethodName
                "requestContentType" -> formattedRequestContentType
                "response" -> responseStatus
                "responseContentType" -> formattedResponseContentType
                "#exercised" -> count
                "remark" -> remarks.toString()
                else -> throw Exception("Unknown column name: ${column.name}")
            }
            column.columnFormat.format(value)
        }
    }
}

