package io.specmatic.test.reports.coverage.console

import kotlin.math.max

class ConsoleReport(
    private val coverageRows: List<CoverageRow>,
    private val reportColumns: List<ReportColumn>,
    private val footerText: String,
    private val tableTitleText: String = "SPECMATIC API COVERAGE SUMMARY"
) {
    constructor(
        coverageRows: List<CoverageRow>,
        reportColumns: List<ReportColumn>,
        footerText: List<String>,
        tableTitleText: String = "SPECMATIC API COVERAGE SUMMARY"
    ) : this(
        coverageRows = coverageRows,
        reportColumns = reportColumns,
        footerText = footerText.joinToString(System.lineSeparator()),
        tableTitleText = tableTitleText
    )

    fun generate(): String {
        return listOf(
            getTableHeader(),
            getTableRows(),
            getTableFooter()
        ).joinToString(separator = System.lineSeparator()).plus(System.lineSeparator())
    }

    private fun getTableHeader(): String {
        val tableColumnHeader = getTableColumnHeader()
        val headerTitleSize = tableColumnHeader.length - 4
        val header = listOf(
            getTitleSeparator(headerTitleSize),
            getTableTitle(headerTitleSize),
            getTitleSeparator(headerTitleSize),
            tableColumnHeader,
            getTableHeaderSeparator()
        )
        return header.joinToString(separator = System.lineSeparator())
    }

    private fun getTableTitle(headerTitleSize: Int): String {
        return "| ${"%-${headerTitleSize}s".format(tableTitleText)} |"
    }

    private fun getTableColumnHeader(): String {
        return reportColumns.joinToString(prefix = "| ", postfix = " |", separator = " | ") {
            it.columnFormat.format(it.name)
        }
    }

    private fun getTableHeaderSeparator(): String {
        return reportColumns.joinToString(prefix = "|-", postfix = "-|", separator = "-|-") {
            "-".repeat(it.maxSizeOfRowInThisColumn)
        }
    }

    private fun getTitleSeparator(headerTitleSize: Int): String {
        return "|-${"-".repeat(headerTitleSize)}-|"
    }

    private fun getTableRows(): String {
        return coverageRows.joinToString(separator = System.lineSeparator()) {
            it.toRowString(reportColumns)
        }
    }

    private fun getTableFooter(): String {
        val headerTitleSize = getTableColumnHeader().length - 4
        val footerRows = footerText.lines().map { line ->
            "| ${"%-${headerTitleSize}s".format(line)} |"
        }
        val titleSeparator = getTitleSeparator(headerTitleSize)
        return listOf(titleSeparator)
            .plus(footerRows)
            .plus(titleSeparator)
            .joinToString(System.lineSeparator())
    }
}

data class ReportColumn(
    val name: String,
    val columnFormat: String,
    val maxSizeOfRowInThisColumn: Int
) {
    constructor(name: String, maxSizeInThisColumn: Int) : this(
        name = name,
        columnFormat = "%-${max(maxSizeInThisColumn, name.length)}s",
        maxSizeOfRowInThisColumn = max(maxSizeInThisColumn, name.length)
    )
}
