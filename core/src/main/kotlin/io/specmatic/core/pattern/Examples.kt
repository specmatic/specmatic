package io.specmatic.core.pattern

import io.cucumber.messages.types.Examples
import io.cucumber.messages.types.TableRow
import java.util.*

data class Examples(val columnNames: List<String> = emptyList(), val rows: List<Row> = listOf()) {
    val isEmpty: Boolean
        get() = rows.isEmpty()

    companion object {
        fun examplesFrom(examplesList: List<Examples>): List<io.specmatic.core.pattern.Examples> = examplesList.map { examplesFrom(it) }

        fun examplesFrom(examples: Examples): io.specmatic.core.pattern.Examples {
            val columns = getColumnNames(examples)
            val rows = examples.tableBody.map { tableRow ->
                val values = getValues(tableRow)
                val exampleFields = columns.zip(values.map { it }).toMap()
                Row(exampleFields)
            }

            return Examples(columns, rows)
        }

        private fun getColumnNames(examples: Examples) = getValues(
            row = examples.tableHeader.orElseThrow {
                IllegalStateException("Expected a TableRow in Examples, but none was present.")
            }
        )

        private fun getValues(row: TableRow): ArrayList<String> = ArrayList(row.cells.map { it.value })

    }

}