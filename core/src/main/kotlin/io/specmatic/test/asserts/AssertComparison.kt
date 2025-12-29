package io.specmatic.test.asserts

import io.ktor.http.*
import io.specmatic.core.Result
import io.specmatic.core.StandardRuleViolation
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

val ASSERT_PATTERN = Regex("^\\$(\\w+)\\((.*)\\)$")

class AssertComparison(override val keys: List<String>, val lookupKey: String, val isEqualityCheck: Boolean): Assert {

    override fun dynamicAsserts(currentFactStore: Map<String, Value>, ifNotExists: (String) -> Value): List<AssertComparison> {
        return this.generateDynamicPaths(keys, currentFactStore, ifNotExists = ifNotExists).map { keys ->
            AssertComparison(keys = keys, lookupKey = lookupKey, isEqualityCheck = isEqualityCheck)
        }
    }

    override fun execute(currentFactStore: Map<String, Value>, actualFactStore: Map<String, Value>): Result {
        val actualValue = currentFactStore[combinedKey] ?: return Result.Failure(breadCrumb = combinedKey, message = "Could not resolve ${combinedKey.quote()} in response")
        val expectedValue = actualFactStore[lookupKey] ?: return Result.Failure(breadCrumb = lookupKey, message = "Could not resolve ${lookupKey.quote()} in store")

        val match = actualValue == expectedValue
        val success = match == isEqualityCheck
        return if (success) { Result.Success() } else {
            val message = if (isEqualityCheck) "equal" else "not equal"
            Result.Failure(
                breadCrumb = combinedKey,
                message = "Expected ${actualValue.displayableValue()} to $message ${expectedValue.displayableValue()}",
                ruleViolation = StandardRuleViolation.VALUE_MISMATCH
            )
        }
    }

    companion object {
        fun parse(keys: List<String>, value: Value): AssertComparison? {
            if (value !is StringValue) return null
            val match = ASSERT_PATTERN.find(value.nativeValue) ?: return null
            return when (match.groupValues[1]) {
                "eq" -> AssertComparison(keys, match.groupValues[2], isEqualityCheck = true)
                "neq" -> AssertComparison(keys, match.groupValues[2], isEqualityCheck = false)
                else -> null
            }
        }
    }
}