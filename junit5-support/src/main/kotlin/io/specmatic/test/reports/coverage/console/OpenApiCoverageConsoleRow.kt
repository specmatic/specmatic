package io.specmatic.test.reports.coverage.console

data class OpenApiCoverageConsoleRow(
    val method: String,
    val path: String,
    val responseStatus: String,
    val count: String,
    val coveragePercentage: Int = 0,
    val remarks: Remarks,
    val showPath: Boolean = true,
    val showMethod: Boolean = true,
): CoverageRow {
    constructor(
        method: String,
        path: String,
        responseStatus: Int,
        count: Int,
        coveragePercentage: Int,
        remarks: Remarks,
        showPath: Boolean = true,
        showMethod: Boolean = true,
    ) : this(method, path, responseStatus.toString(), count.toString(), coveragePercentage, remarks, showPath, showMethod)

    private val formattedCoveragePercentage: String
        get() = if (showPath) "$coveragePercentage%" else ""

    private val formattedPathName: String
        get() = if (showPath) path else ""

    private val formattedMethodName: String
        get() = if (showMethod) method else ""

    private val formattedResponseStatus: String
        get() = if (responseStatus.toInt() != 0) responseStatus else ""

    override fun toRowString(tableColumns: List<ReportColumn>): String {
        return tableColumns.joinToString(separator = " | ", postfix = " |", prefix = "| ") { column ->
            val value = when (column.name) {
                "coverage" -> formattedCoveragePercentage
                "path" -> formattedPathName
                "method" -> formattedMethodName
                "response" -> formattedResponseStatus
                "#exercised" -> count
                "result" -> remarks.toString()
                else -> throw Exception("Unknown column name: ${column.name}")
            }
            column.columnFormat.format(value)
        }
    }
}

