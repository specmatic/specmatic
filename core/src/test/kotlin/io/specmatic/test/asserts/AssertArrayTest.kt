package io.specmatic.test.asserts

import io.specmatic.core.Result
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.StandardRuleViolation
import io.specmatic.toViolationReportString
import io.specmatic.test.asserts.AssertComparisonTest.Companion.toFactStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class AssertArrayTest {
    @ParameterizedTest
    @CsvSource(
        "REQUEST.BODY, [*]name, ENTITY.name, array_has",
        "REQUEST.BODY[*], name, ENTITY.name, array_has",
    )
    fun `should be able to parse equal and not equal assertions`(prefix: String, key: String, lookupKey: String, assertType: String) {
        val value = StringValue("\$${assertType}($lookupKey)")

        println("value: $value, prefix: $prefix, key: $key, lookupKey: $lookupKey, assertType: $assertType")
        val assert = parsedAssert(prefix, key, value)
        assertThat(assert).isNotNull.isInstanceOf(AssertArray::class.java); assert as AssertArray
        assertThat(assert.keys).containsExactly("REQUEST", "BODY", "[*]", "name")
        assertThat(assert.lookupKey).isEqualTo(lookupKey)
        assertThat(assert.arrayAssertType).isEqualTo(ArrayAssertType.ARRAY_HAS)
    }

    @Test
    fun `should return success when array contains expected value`() {
        val assert = AssertArray(keys = listOf("BODY", "[*]", "name"), lookupKey = "ENTITY.name", arrayAssertType = ArrayAssertType.ARRAY_HAS)

        val actualStore = mapOf("ENTITY.name" to StringValue("John"))
        val bodyValue = JSONArrayValue(
            listOf(
                JSONObjectValue(mapOf("name" to StringValue("Jane"))),
                JSONObjectValue(mapOf("name" to StringValue("John"))),
                JSONObjectValue(mapOf("name" to StringValue("May")))
            )
        )
        val currentStore = bodyValue.toFactStore("BODY")
        val result = assert.assert(currentStore, actualStore)

        assertThat(result).withFailMessage(result.reportString()).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should return failure when array does not contain expected value`() {
        val assert = AssertArray(keys = listOf("BODY", "[*]", "name"), lookupKey = "ENTITY.name", arrayAssertType = ArrayAssertType.ARRAY_HAS)

        val actualStore = mapOf("ENTITY.name" to StringValue("John"))
        val bodyValue = JSONArrayValue(
            listOf(
                JSONObjectValue(mapOf("name" to StringValue("Jane"))),
                JSONObjectValue(mapOf("name" to StringValue("May")))
            )
        )
        val currentStore = bodyValue.toFactStore("BODY")

        val result = assert.assert(currentStore, actualStore)
        println(result.reportString())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
        ${
            toViolationReportString(
                breadCrumb = "BODY[*].name",
                details = "None of the values matched \"ENTITY.name\" of value \"John\"",
                StandardRuleViolation.VALUE_MISMATCH
            )
        }
        """.trimIndent())
    }

    @Test
    fun `should return failure when the value is not an array`() {
        val assert = AssertArray(keys = listOf("BODY", "[*]", "name"), lookupKey = "ENTITY.name", arrayAssertType = ArrayAssertType.ARRAY_HAS)

        val actualStore = mapOf("ENTITY.name" to StringValue("John"))
        val bodyValue = JSONObjectValue(mapOf("name" to StringValue("Jane")))
        val currentStore = bodyValue.toFactStore("BODY")

        val result = assert.assert(currentStore, actualStore)
        println(result.reportString())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
        >> BODY
        Expected json array but found json object
        """.trimIndent())
    }

    @Test
    fun `should throw exception when used on non-array base`() {
        val exception = assertThrows<ContractException> {
            AssertArray(keys = listOf("BODY", "name"), lookupKey = "ENTITY.name", arrayAssertType = ArrayAssertType.ARRAY_HAS)
        }

        assertThat(exception.report()).isEqualToNormalizingWhitespace("""
        ${
            toViolationReportString(
                breadCrumb = "BODY.name",
                details = "Array Asserts can only be used on arrays",
                StandardRuleViolation.TYPE_MISMATCH
            )
        }
        """.trimIndent())
    }

    @Test
    fun `should be able to create dynamic asserts based on keys`() {
        val arrayAssert = AssertArray(
            keys = listOf("BODY", "[*]", "details", "[*]", "name"),
            lookupKey = "ENTITY.name",
            arrayAssertType = ArrayAssertType.ARRAY_HAS
        )

        val value = JSONArrayValue(List(3) {
            JSONObjectValue(mapOf(
                "details" to JSONArrayValue(List(2) {
                    JSONObjectValue(mapOf("name" to StringValue("John")))
                })
            ))
        })
        val dynamicAsserts = arrayAssert.dynamicAsserts(value.toFactStore("BODY"))

        assertThat(dynamicAsserts.size).isEqualTo(3)
        dynamicAsserts.forEachIndexed { index, it ->
            assertThat(it).isInstanceOf(AssertArray::class.java)
            assertThat(it.keys).containsExactly("BODY", "[$index]", "details", "[*]", "name")
            assertThat(it.lookupKey).isEqualTo("ENTITY.name")
            assertThat(it.arrayAssertType).isEqualTo(ArrayAssertType.ARRAY_HAS)
        }
    }

    @Test
    fun `when nested in conditional assert should assert on array and not items in array`() {
        val arrayAssert = AssertArray(
            keys = listOf("BODY", "[*]", "details", "[*]", "name"),
            lookupKey = "ENTITY.name",
            arrayAssertType = ArrayAssertType.ARRAY_HAS
        )
        val conditionalAssert = AssertConditional(
            keys = listOf("BODY", "[*]", "details", "[*]"),
            conditionalAsserts = listOf(arrayAssert),
            thenAsserts = listOf(AssertComparison(listOf("BODY", "[*]", "details", "[*]", "age"), lookupKey = "ENTITY.age", isEqualityCheck = true)),
            elseAsserts = emptyList()
        )
        val value = JSONArrayValue(List(3) {
            JSONObjectValue(mapOf(
                "details" to JSONArrayValue(List(2) { index ->
                    if (index == 0) {
                        JSONObjectValue(mapOf("name" to StringValue("John"), "age" to NumberValue(2)))
                    } else {
                        JSONObjectValue(mapOf("name" to StringValue("Jane"), "age" to NumberValue(2)))
                    }
                })
            ))
        })
        val currentStore = value.toFactStore("BODY")

        val dynamicAsserts = conditionalAssert.dynamicAsserts(currentStore)
        val expectedKeys = listOf(
            listOf("BODY", "[0]", "details", "[0]"),
            listOf("BODY", "[0]", "details", "[1]"),
            listOf("BODY", "[1]", "details", "[0]"),
            listOf("BODY", "[1]", "details", "[1]"),
            listOf("BODY", "[2]", "details", "[0]"),
            listOf("BODY", "[2]", "details", "[1]")
        )
        assertThat(dynamicAsserts.size).isEqualTo(6)
        dynamicAsserts.forEachIndexed { i, it ->
            assertThat(it).isInstanceOf(AssertConditional::class.java)
            assertThat(it.keys).containsExactlyElementsOf(expectedKeys[i])

            assertThat(it.conditionalAsserts).hasSize(1)
            assertThat(it.conditionalAsserts).allSatisfy { assert ->
                assertThat(assert).isInstanceOf(AssertArray::class.java); assert as AssertArray
                assertThat(assert.keys).containsExactly("BODY", expectedKeys[i][1], "details", "[*]", "name")
                assertThat(assert.lookupKey).isEqualTo("ENTITY.name")
                assertThat(assert.arrayAssertType).isEqualTo(ArrayAssertType.ARRAY_HAS)
            }

            assertThat(it.thenAsserts).hasSize(1)
            assertThat(it.thenAsserts).allSatisfy { assert ->
                assertThat(assert).isInstanceOf(AssertComparison::class.java); assert as AssertComparison
                assertThat(assert.keys).containsExactlyElementsOf(expectedKeys[i] + "age")
                assertThat(assert.lookupKey).isEqualTo("ENTITY.age")
                assertThat(assert.isEqualityCheck).isTrue
            }

            assertThat(it.elseAsserts).isEmpty()
        }

        val actualStore = mapOf("ENTITY.name" to StringValue("John"), "ENTITY.age" to NumberValue(2))
        val result = conditionalAssert.assert(currentStore, actualStore)
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }
}