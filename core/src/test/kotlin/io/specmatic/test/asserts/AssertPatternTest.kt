package io.specmatic.test.asserts

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.StandardRuleViolation
import io.specmatic.core.pattern.*
import io.specmatic.core.value.*
import io.specmatic.test.asserts.AssertComparisonTest.Companion.toFactStore
import io.specmatic.toViolationReportString
import io.specmatic.core.DefaultMismatchMessages
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource


class AssertPatternTest {
    @ParameterizedTest
    @CsvSource(
        "REQUEST.BODY, name, (string)",
        "REQUEST.BODY, name, (number)",
        "REQUEST.BODY, name, (anyvalue)"
    )
    fun `should be able to parse pattern type`(prefix: String, key: String, value: String) {
        val resolver = Resolver()
        val patternType = resolvedHop(parsedValue(value).exactMatchElseType(), resolver)

        println("value: $value, prefix: $prefix, key: $key, value: $value, patternType: ${patternType.javaClass.simpleName}")
        val assert = parsedAssert(prefix, key, parsedValue(value), resolver)
        assertThat(assert).isNotNull.isInstanceOf(AssertPattern::class.java); assert as AssertPattern
        assertThat(assert.keys).containsExactly("REQUEST", "BODY", "name")
        assertThat(resolvedHop(assert.pattern, resolver)).isInstanceOf(patternType::class.java)
    }

    @Test
    fun `should not parse literal values to ExactValue Pattern`() {
        val resolver = Resolver()
        val assert = parsedAssert("REQUEST.BODY", "name", StringValue("john"), resolver)
        assertThat(assert).isNull()
    }

    @Test
    fun `should not parse composite values to AssertPattern`() {
        val resolver = Resolver()
        val compositeValue = parsedJSONObject("""
        {
            "key": "value",
            "key2": "value2"
        }
        """.trimIndent())
        val assert = parsedAssert("REQUEST.BODY", "composite", compositeValue, resolver)
        println(assert)
        assertThat(assert).isNull()
    }

    @Test
    fun `should return success when value matches the pattern`() {
        val resolver = Resolver()
        val assert = AssertPattern(keys = listOf("BODY", "name"), pattern = parsedPattern("john"), resolver)

        val bodyValue = JSONObjectValue(mapOf("name" to StringValue("john")))
        val currentStore = bodyValue.toFactStore("BODY")

        val result = assert.assert(currentStore, currentStore)
        println(result.reportString())
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should return failure when value does not match the pattern`() {
        val resolver = Resolver()
        val assert = AssertPattern(keys = listOf("BODY", "name"), pattern = parsedPattern("(string)"), resolver)

        val bodyValue = JSONObjectValue(mapOf("name" to NumberValue(100)))
        val currentStore = bodyValue.toFactStore("BODY")

        val result = assert.assert(currentStore, currentStore)
        println(result.reportString())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToIgnoringWhitespace(
            toViolationReportString(
                breadCrumb = "BODY.name",
                details = DefaultMismatchMessages.typeMismatch("string", "100", "number"),
                StandardRuleViolation.TYPE_MISMATCH
            )
        )
    }

    @Test
    fun ` should return failure when value does not exist`() {
        val resolver = Resolver()
        val assert = AssertPattern(keys = listOf("BODY", "name"), pattern = parsedPattern("(string)"), resolver)

        val bodyValue = JSONObjectValue(mapOf())
        val currentStore = bodyValue.toFactStore("BODY")

        val result = assert.assert(currentStore, currentStore)
        println(result.reportString())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
        >> BODY.name
        Could not resolve "BODY.name" in response
        """.trimIndent())
    }

    @Test
    fun `should be able to create dynamic asserts based on prefix value`() {
        val resolver = Resolver()
        val arrayAssert = AssertPattern(keys = listOf("BODY", "[*]", "name"), pattern = parsedPattern("(string)"), resolver)
        val jsonValue = JSONObjectValue(mapOf("name" to NumberValue(100)))
        val arrayValue = JSONArrayValue(List(3) { jsonValue })

        val arrayBasedAsserts = arrayAssert.dynamicAsserts(arrayValue.toFactStore("BODY"))
        assertThat(arrayBasedAsserts.size).isEqualTo(3)
        arrayBasedAsserts.forEachIndexed { index, it ->
            assertThat(it).isInstanceOf(AssertPattern::class.java); it as AssertPattern
            assertThat(it.keys).containsExactly("BODY", "[$index]", "name")
            assertThat(it.pattern).isInstanceOf(StringPattern::class.java)
        }

        val objectAssert = AssertPattern(keys = listOf("BODY", "name"), pattern = parsedPattern("(string)"), resolver)
        val jsonBasedAsserts = objectAssert.dynamicAsserts(jsonValue.toFactStore("BODY"))
        assertThat(jsonBasedAsserts.size).isEqualTo(1)
        assertThat(jsonBasedAsserts).allSatisfy {
            assertThat(it).isInstanceOf(AssertPattern::class.java); it as AssertPattern
            assertThat(it.keys).containsExactly("BODY", "name")
            assertThat(it.pattern).isInstanceOf(StringPattern::class.java)
        }
    }

    @Test
    fun `should assert all array values based on dynamic asserts when prefix value is an array`() {
        val resolver = Resolver()
        val assert = AssertPattern(keys = listOf("BODY", "[*]", "name"), pattern = parsedPattern("(string)"), resolver)

        val jsonValue = JSONObjectValue(mapOf("name" to NumberValue(100)))
        val arrayValue = JSONArrayValue(List(3) { jsonValue })
        val currentStore = arrayValue.toFactStore("BODY")

        val result = assert.assert(currentStore, currentStore)
        println(result.reportString())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToIgnoringWhitespace("""
        ${
            toViolationReportString(
                breadCrumb = "BODY[0].name",
                details = DefaultMismatchMessages.typeMismatch("string", "100", "number"),
                StandardRuleViolation.TYPE_MISMATCH
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "BODY[1].name",
                details = DefaultMismatchMessages.typeMismatch("string", "100", "number"),
                StandardRuleViolation.TYPE_MISMATCH
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "BODY[2].name",
                details = DefaultMismatchMessages.typeMismatch("string", "100", "number"),
                StandardRuleViolation.TYPE_MISMATCH
            )
        }
        """.trimIndent())
    }

    @Test
    fun `should be able to resolve pattern from resolver`() {
        val resolver = Resolver(newPatterns = mapOf("(Test)" to NumberPattern()))
        val assert = AssertPattern(keys = listOf("BODY", "name"), pattern = parsedPattern("(Test)"), resolver)
        val jsonValue = JSONObjectValue(mapOf("name" to NumberValue(100)))
        val currentStore = jsonValue.toFactStore("BODY")

        val result = assert.assert(currentStore, currentStore)
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should return failure if pattern token does not resolve`() {
        val resolver = Resolver()
        val assert = AssertPattern(keys = listOf("BODY", "name"), pattern = parsedPattern("(Test)"), resolver)
        val jsonValue = JSONObjectValue(mapOf("name" to NumberValue(100)))
        val currentStore = jsonValue.toFactStore("BODY")

        val result = assert.assert(currentStore, currentStore)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `should not combine key if key is empty with prefix`() {
        val assert = AssertPattern(keys = listOf("BODY", ""), pattern = StringPattern(), resolver = Resolver())
        val actualStore = mapOf("ENTITY.name" to StringValue("John"))
        val bodyValue = StringValue("John")
        val currentStore = bodyValue.toFactStore("BODY")

        val result = assert.assert(currentStore, actualStore)
        println(result.reportString())
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }
}
