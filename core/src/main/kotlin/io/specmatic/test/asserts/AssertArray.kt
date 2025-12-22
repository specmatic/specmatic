package io.specmatic.test.asserts

import io.ktor.http.*
import io.specmatic.core.Result
import io.specmatic.core.RuleViolationReport
import io.specmatic.core.StandardRuleViolation
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

enum class ArrayAssertType { ARRAY_HAS }

class AssertArray(override val keys: List<String>, val lookupKey: String, val arrayAssertType: ArrayAssertType): Assert {

    init {
        require(keys[keys.lastIndex - 1].matches("\\[\\*]|\\[\\d+]".toRegex())) {
            throw ContractException(
                breadCrumb = combinedKey,
                errorMessage = "Array Asserts can only be used on arrays",
                ruleViolationReport = RuleViolationReport().withViolation(StandardRuleViolation.TYPE_MISMATCH)
            )
        }
    }

    override fun execute(currentFactStore: Map<String, Value>, actualFactStore: Map<String, Value>): Result {
        val comparisonAssert = AssertComparison(keys, lookupKey, isEqualityCheck = true)
        val asserts = runCatching { comparisonAssert.dynamicAsserts(currentFactStore) }.getOrElse { e -> return e.toFailure() }
        val results = asserts.map { it.execute(currentFactStore, actualFactStore) }
        return results.toResultIfAny(currentFactStore, actualFactStore)
    }

    private fun List<Result>.toResultIfAny(currentFactStore: Map<String, Value>, actualFactStore: Map<String, Value>): Result {
        val expectedValue = actualFactStore[lookupKey] ?: return Result.Failure(
            breadCrumb = lookupKey,
            message = "Could not resolve ${lookupKey.quote()} in store"
        )

        val indexedKeys = generateDynamicPaths(keys, currentFactStore) { NullValue }.firstOrNull() ?: keys
        return this.firstOrNull { it is Result.Success } ?: Result.Failure(
            breadCrumb = indexedKeys.withWildCard().combinedKey(),
            message = "None of the values matched ${lookupKey.quote()} of value ${expectedValue.displayableValue()}",
            ruleViolation = StandardRuleViolation.VALUE_MISMATCH
        )
    }

    override fun dynamicAsserts(currentFactStore: Map<String, Value>, ifNotExists: (String) -> Value): List<AssertArray> {
        val keysWithoutAssertingArray = keys.subList(0, keys.size - 2)
        val assertingArrayKeys= keys.subList(keys.size - 2, keys.size)
        return this.generateDynamicPaths(keysWithoutAssertingArray, currentFactStore, ifNotExists = ifNotExists).map { keys ->
            AssertArray(keys + assertingArrayKeys, lookupKey, arrayAssertType)
        }
    }

    companion object {
        fun parse(keys: List<String>, value: Value): AssertArray? {
            if (value !is StringValue) return null
            val match = ASSERT_PATTERN.find(value.nativeValue) ?: return null
            return when (match.groupValues[1]) {
                "array_has" -> AssertArray(keys, match.groupValues[2], ArrayAssertType.ARRAY_HAS)
                else -> null
            }
        }
    }
}