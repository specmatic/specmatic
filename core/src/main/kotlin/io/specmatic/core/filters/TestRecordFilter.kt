package io.specmatic.core.filters

import io.specmatic.test.TestResultRecord

class TestRecordFilter(private val eachTestResult: TestResultRecord) : FilterContext {
    override fun includes(key: String, values: List<String>): Boolean {
        return when (SupportedKey.from(key)) {
            SupportedKey.PATH -> {
                values.any(eachTestResult.path::contains)
            }
            SupportedKey.METHOD -> {
                values.any { it.equals(eachTestResult.method, ignoreCase = true) }
            }
            SupportedKey.STATUS -> {
                values.any { it.toIntOrNull() == eachTestResult.responseStatus }
            }
            else -> true
        }
    }

    override fun compare(
        filterKey: String,
        operator: String,
        filterValue: String
    ): Boolean = when (SupportedKey.from(filterKey)) {
        SupportedKey.STATUS -> {
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

    companion object {
        private enum class SupportedKey {
            PATH,
            METHOD,
            STATUS;

            companion object {
                fun from(key: String): SupportedKey? =
                    runCatching { enumValueOf<SupportedKey>(key) }.getOrNull()
            }
        }

        fun supportsFilterKey(key: String): Boolean = SupportedKey.from(key) != null
    }
}
