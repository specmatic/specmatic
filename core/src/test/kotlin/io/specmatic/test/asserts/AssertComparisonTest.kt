package io.specmatic.test.asserts

import io.specmatic.core.Result
import io.specmatic.core.StandardRuleViolation
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.test.traverse
import io.specmatic.toViolationReportString
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class AssertComparisonTest {
    companion object {
        fun Value.toFactStore(prefix: String): Map<String, Value> {
            return this.traverse(
                prefix = prefix,
                onScalar = { value, key -> mapOf(key to value) },
                onComposite = { value, key -> mapOf(key to value) }
            )
        }
    }

    @ParameterizedTest
    @CsvSource(
        "REQUEST.BODY, name, ENTITY.name, true",
        "REQUEST.BODY, name, ENTITY.name, false"
    )
    fun `should be able to parse equal and not equal assertions`(prefix: String, key: String, lookupKey: String, isEqualityCheck: Boolean) {
        val assertType = if (isEqualityCheck) "eq" else "neq"
        val value = StringValue("\$${assertType}($lookupKey)")

        println("value: $value, prefix: $prefix, key: $key, lookupKey: $lookupKey, isEqualityCheck: $isEqualityCheck")
        val assert = parsedAssert(prefix, key, value)
        assertThat(assert).isNotNull.isInstanceOf(AssertComparison::class.java); assert as AssertComparison
        assertThat(assert.keys).containsExactly("REQUEST", "BODY", "name")
        assertThat(assert.lookupKey).isEqualTo(lookupKey)
        assertThat(assert.isEqualityCheck).isEqualTo(isEqualityCheck)
    }

    @Test
    fun `should return failure when actual value is not expected value and equality check is True`() {
        val assert = AssertComparison(keys = listOf("BODY", "name"), lookupKey = "ENTITY.name", isEqualityCheck = true)

        val actualStore = mapOf("ENTITY.name" to StringValue("John"))
        val bodyValue = JSONObjectValue(mapOf("name" to StringValue("Jane")))
        val currentStore = bodyValue.toFactStore("BODY")

        val result = assert.assert(currentStore, actualStore)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToIgnoringWhitespace("""
        ${
            toViolationReportString(
                breadCrumb = "BODY.name",
                details = "Expected \"Jane\" to equal \"John\"",
                StandardRuleViolation.VALUE_MISMATCH
            )
        }
        """.trimIndent())
    }

    @Test
    fun `should return success when actual value is expected value and equality check is True`() {
        val assert = AssertComparison(keys = listOf("BODY", "name"), lookupKey = "ENTITY.name", isEqualityCheck = true)

        val actualStore = mapOf("ENTITY.name" to StringValue("John"))
        val bodyValue = JSONObjectValue(mapOf("name" to StringValue("John")))
        val currentStore = bodyValue.toFactStore("BODY")

        val result = assert.assert(currentStore, actualStore)

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should return failure when actual value is expected value and equality check is False`() {
        val assert = AssertComparison(keys = listOf("BODY", "name"), lookupKey = "ENTITY.name", isEqualityCheck = false)

        val actualStore = mapOf("ENTITY.name" to StringValue("John"))
        val bodyValue = JSONObjectValue(mapOf("name" to StringValue("John")))
        val currentStore = bodyValue.toFactStore("BODY")

        val result = assert.assert(currentStore, actualStore)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToNormalizingWhitespace(
            toViolationReportString(
                breadCrumb = "BODY.name",
                details = "Expected \"John\" to not equal \"John\"",
                StandardRuleViolation.VALUE_MISMATCH
            )
        )
    }

    @Test
    fun `should return success when actual value is not expected value and equality check is False`() {
        val assert = AssertComparison(keys = listOf("BODY", "name"), lookupKey = "ENTITY.name", isEqualityCheck = false)

        val actualStore = mapOf("ENTITY.name" to StringValue("John"))
        val bodyValue = JSONObjectValue(mapOf("name" to StringValue("Jane")))
        val currentStore = bodyValue.toFactStore("BODY")

        val result = assert.assert(currentStore, actualStore)

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should be able to create dynamic asserts based on prefix value`() {
        val arrayAssert = AssertComparison(keys = listOf("BODY", "[*]", "name"), lookupKey = "ENTITY.name", isEqualityCheck = true)
        val jsonValue = JSONObjectValue(mapOf("name" to StringValue("Jane")))
        val arrayValue = JSONArrayValue(List(3) { jsonValue })

        val arrayBasedAsserts = arrayAssert.dynamicAsserts(arrayValue.toFactStore("BODY"))
        assertThat(arrayBasedAsserts.size).isEqualTo(3)
        arrayBasedAsserts.forEachIndexed { index, it ->
            assertThat(it).isInstanceOf(AssertComparison::class.java)
            assertThat(it.keys).containsExactly("BODY", "[$index]", "name")
            assertThat(it.lookupKey).isEqualTo("ENTITY.name")
            assertThat(it.isEqualityCheck).isTrue
        }

        val objectAssert = AssertComparison(keys = listOf("BODY", "name"), lookupKey = "ENTITY.name", isEqualityCheck = true)
        val jsonBasedAsserts = objectAssert.dynamicAsserts(jsonValue.toFactStore("BODY"))
        assertThat(jsonBasedAsserts.size).isEqualTo(1)
        assertThat(jsonBasedAsserts).allSatisfy {
            assertThat(it).isInstanceOf(AssertComparison::class.java)
            it as AssertComparison
            assertThat(it.keys).containsExactly("BODY", "name")
            assertThat(it.lookupKey).isEqualTo("ENTITY.name")
            assertThat(it.isEqualityCheck).isTrue
        }
    }

    @Test
    fun `should return failure when lookup key is not present in actual store`() {
        val assert = AssertComparison(keys = listOf("BODY", "name"), lookupKey = "ENTITY.name", isEqualityCheck = true)

        val actualStore = emptyMap<String, Value>()
        val bodyValue = JSONObjectValue(mapOf("name" to StringValue("Jane")))
        val currentStore = bodyValue.toFactStore("BODY")

        val result = assert.assert(currentStore, actualStore)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
        >> ENTITY.name
        Could not resolve "ENTITY.name" in store
        """.trimIndent())
    }

    @Test
    fun `should return failure when lookup key is not present in current store`() {
        val assert = AssertComparison(keys = listOf("BODY", "name"), lookupKey = "ENTITY.name", isEqualityCheck = true)

        val actualStore = mapOf("ENTITY.name" to StringValue("John"))
        val currentStore = emptyMap<String, Value>()

        val result = assert.assert(currentStore, actualStore)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
        >> BODY
        Could not resolve "BODY" in response
        """.trimIndent())
    }

    @Test
    fun `should not combine key if key is empty with prefix`() {
        val assert = AssertComparison(keys = listOf("BODY", ""), lookupKey = "ENTITY.name", isEqualityCheck = true)
        val actualStore = mapOf("ENTITY.name" to StringValue("John"))
        val bodyValue = StringValue("John")
        val currentStore = bodyValue.toFactStore("BODY")

        val result = assert.assert(currentStore, actualStore)

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }
}
