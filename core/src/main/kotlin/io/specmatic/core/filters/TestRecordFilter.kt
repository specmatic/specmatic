package io.specmatic.core.filters

import io.specmatic.test.TestResultRecord

class TestRecordFilter(private val eachTestResult: TestResultRecord) : FilterContext {
    override fun includes(key: String, values: List<String>): Boolean {
        return when (key) {
            "PATH" -> {
                values.any(eachTestResult.path::contains)
            }
            "METHOD" -> {
                values.any { it.equals(eachTestResult.method, ignoreCase = true) }
            }
            "STATUS" -> {
                values.any { it.toIntOrNull() == eachTestResult.responseStatus }
            }
            else -> true
        }
    }

    override fun compare(
        filterKey: String,
        operator: String,
        filterValue: String
    ): Boolean = when (filterKey) {
        "STATUS" -> {
            evaluateCondition(
                eachTestResult.responseStatus,
                operator,
                filterValue.toIntOrNull() ?: 0
            )
        }
        else -> {
            true
        }
    }
}
