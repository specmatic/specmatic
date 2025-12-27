package io.specmatic.test.asserts

import io.specmatic.core.Result
import io.specmatic.core.StandardRuleViolation
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.StringValue
import io.specmatic.test.asserts.AssertComparisonTest.Companion.toFactStore
import io.specmatic.toViolationReportString
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class AssertExistenceTest {
    @ParameterizedTest
    @CsvSource(
        "REQUEST.BODY, name, exists",
        "REQUEST.BODY, name, not_exists",
        "REQUEST.BODY, name, is_null",
        "REQUEST.BODY, name, is_not_null",
    )
    fun `should be able to parse assertions`(prefix: String, key: String, checkTypeString: String) {
        val checkType = ExistenceCheckType.fromString(checkTypeString)!!
        val value = StringValue("\$${checkType.value}()")

        println("value: $value, prefix: $prefix, key: $key, checkType: $checkType")
        val assert = parsedAssert(prefix, key, value)
        assertThat(assert).isNotNull.isInstanceOf(AssertExistence::class.java); assert as AssertExistence
        assertThat(assert.keys).containsExactly("REQUEST", "BODY", "name")
        assertThat(assert.checkType).isEqualTo(checkType)
    }

    @Test
    fun `should return failure when actual value does not exist and check type is exists`() {
        val assert = AssertExistence(keys = listOf("BODY", "name"), checkType = ExistenceCheckType.EXISTS)

        val bodyValue = JSONObjectValue(emptyMap())
        val currentStore = bodyValue.toFactStore("BODY")

        val result = assert.assert(currentStore, emptyMap())
        println(result.reportString())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(toViolationReportString(
            breadCrumb = "BODY.name",
            details = "Expected \"BODY.name\" to exist",
            StandardRuleViolation.VALUE_MISMATCH,
        ))
    }

    @Test
    fun `should return failure when actual value exists and check type is not_exists`() {
        val assert = AssertExistence(keys = listOf("BODY", "name"), checkType = ExistenceCheckType.NOT_EXISTS)

        val bodyValue = JSONObjectValue(mapOf("name" to StringValue("John")))
        val currentStore = bodyValue.toFactStore("BODY")

        val result = assert.assert(currentStore, emptyMap())
        println(result.reportString())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(toViolationReportString(
            breadCrumb = "BODY.name",
            details = "Expected \"BODY.name\" to not exist",
            StandardRuleViolation.VALUE_MISMATCH,
        ))
    }

    @Test
    fun `should return failure when actual value is null and check type is is_not_null`() {
        val assert = AssertExistence(keys = listOf("BODY", "name"), checkType = ExistenceCheckType.NOT_NULL)

        val bodyValue = JSONObjectValue(mapOf("name" to NullValue))
        val currentStore = bodyValue.toFactStore("BODY")

        val result = assert.assert(currentStore, emptyMap())
        println(result.reportString())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(toViolationReportString(
            breadCrumb = "BODY.name",
            details = "Expected \"BODY.name\" to not be null",
            StandardRuleViolation.VALUE_MISMATCH,
        ))
    }

    @Test
    fun `should return failure when actual value is not null check type is is_null`() {
        val assert = AssertExistence(keys = listOf("BODY", "name"), checkType = ExistenceCheckType.IS_NULL)

        val bodyValue = JSONObjectValue(mapOf("name" to StringValue("John")))
        val currentStore = bodyValue.toFactStore("BODY")

        val result = assert.assert(currentStore, emptyMap())
        println(result.reportString())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(toViolationReportString(
            breadCrumb = "BODY.name",
            details = "Expected \"BODY.name\" to be null",
            StandardRuleViolation.VALUE_MISMATCH,
        ))
    }

    @Test
    fun `should not combine key if key is empty with prefix`() {
        val assert = AssertExistence(keys = listOf("BODY", ""), checkType = ExistenceCheckType.EXISTS)
        val actualStore = mapOf("ENTITY.name" to StringValue("John"))
        val bodyValue = StringValue("John")
        val currentStore = bodyValue.toFactStore("BODY")

        val result = assert.assert(currentStore, actualStore)
        println(result.reportString())
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }
}