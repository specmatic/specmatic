package io.specmatic.test.asserts

import io.ktor.http.*
import io.specmatic.core.Result
import io.specmatic.core.StandardRuleViolation
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

enum class ExistenceCheckType(val value: String, val errorMessageSuffix: String) {
    EXISTS("exists", "exist"),
    NOT_EXISTS("not_exists", "not exist"),
    IS_NULL("is_null", "be ${NullValue.displayableValue()}"),
    NOT_NULL("is_not_null", "not be ${NullValue.displayableValue()}"),;

    companion object {
        fun fromString(value: String): ExistenceCheckType? {
            return entries.find { it.value == value }
        }
    }
}

class AssertExistence(override val keys: List<String>, val checkType: ExistenceCheckType): Assert {
    override fun execute(currentFactStore: Map<String, Value>, actualFactStore: Map<String, Value>): Result {
        val actualValue = currentFactStore[combinedKey]

        val success = when (checkType) {
            ExistenceCheckType.EXISTS -> actualValue != null
            ExistenceCheckType.NOT_EXISTS -> actualValue == null
            ExistenceCheckType.NOT_NULL -> actualValue !is NullValue
            ExistenceCheckType.IS_NULL -> actualValue is NullValue
        }

        return if (success) { Result.Success() } else Result.Failure(
            breadCrumb = combinedKey,
            message = "Expected ${combinedKey.quote()} to ${checkType.errorMessageSuffix}",
            ruleViolation = StandardRuleViolation.VALUE_MISMATCH
        )
    }

    override fun dynamicAsserts(currentFactStore: Map<String, Value>, ifNotExists: (String) -> Value): List<AssertExistence> {
        return generateDynamicPaths(remainingKeys = keys, store = currentFactStore, ifNotExists = { NullValue }).map { keys ->
            AssertExistence(keys, checkType)
        }
    }

    companion object {
        fun parse(keys: List<String>, value: Value): AssertExistence? {
            if (value !is StringValue) return null
            val match = ASSERT_PATTERN.find(value.nativeValue) ?: return null
            val assertType = ExistenceCheckType.fromString(match.groupValues[1]) ?: return null
            return AssertExistence(keys, assertType)
        }
    }
}