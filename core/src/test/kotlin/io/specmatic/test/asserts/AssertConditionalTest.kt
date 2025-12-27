package io.specmatic.test.asserts

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.StandardRuleViolation
import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.test.asserts.AssertComparisonTest.Companion.toFactStore
import io.specmatic.toViolationReportString
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AssertConditionalTest {

    @Nested
    inner class JsonObjectPrefixValue {
        @Test
        fun `should execute then asserts when conditional asserts are met`() {
            val assert = AssertConditional(
                keys = listOf("BODY"),
                conditionalAsserts = listOf(AssertComparison(listOf("BODY", "name"), lookupKey = "ENTITY.name", isEqualityCheck = true)),
                thenAsserts = listOf(AssertComparison(listOf("BODY", "address"), lookupKey = "ENTITY.address", isEqualityCheck = true)),
                elseAsserts = listOf(AssertComparison(listOf("BODY", "address"), lookupKey = "ENTITY.address", isEqualityCheck = false))
            )

            val actualStore = mapOf("ENTITY.name" to StringValue("John"), "ENTITY.address" to StringValue("123 Main St"))
            val bodyValue = JSONObjectValue(mapOf("name" to StringValue("John"), "address" to StringValue("456 Main St")))
            val currentStore = bodyValue.toFactStore("BODY")

            val result = assert.assert(currentStore, actualStore)
            println(result.reportString())

            assertThat(result).isInstanceOf(Result.Failure::class.java)
            assertThat(toViolationReportString(
                breadCrumb = "BODY.address",
                details = "Expected \"BODY.address\" to equal \"123 Main St\"",
                StandardRuleViolation.VALUE_MISMATCH,
            ))
        }

        @Test
        fun `should execute else asserts when conditional asserts are not met`() {
            val assert = AssertConditional(
                keys = listOf("BODY"),
                conditionalAsserts = listOf(AssertComparison(listOf("BODY", "name"), lookupKey = "ENTITY.name", isEqualityCheck = true)),
                thenAsserts = listOf(AssertComparison(listOf("BODY", "address"), lookupKey = "ENTITY.address", isEqualityCheck = true)),
                elseAsserts = listOf(AssertComparison(listOf("BODY", "address"), lookupKey = "ENTITY.address", isEqualityCheck = false))
            )

            val actualStore = mapOf("ENTITY.name" to StringValue("John"), "ENTITY.address" to StringValue("123 Main St"))
            val bodyValue = JSONObjectValue(mapOf("name" to StringValue("Jane"), "address" to StringValue("123 Main St")))
            val currentStore = bodyValue.toFactStore("BODY")

            val result = assert.assert(currentStore, actualStore)
            println(result.reportString())

            assertThat(result).isInstanceOf(Result.Failure::class.java)
            assertThat(toViolationReportString(
                breadCrumb = "BODY.address",
                details = "Expected \"BODY.address\" to not equal \"123 Main St\"",
                StandardRuleViolation.VALUE_MISMATCH,
            ))
        }
    }

    @Nested
    inner class JsonArrayPrefixValue {
        @Test
        fun `should execute then asserts when conditional asserts are met`() {
            val assert = AssertConditional(
                keys = listOf("BODY", "[*]"),
                conditionalAsserts = listOf(AssertComparison(listOf("BODY", "[*]", "name"), lookupKey = "ENTITY.name", isEqualityCheck = true)),
                thenAsserts = listOf(AssertComparison(listOf("BODY", "[*]", "address"), lookupKey = "ENTITY.address", isEqualityCheck = true)),
                elseAsserts = listOf(AssertComparison(listOf("BODY", "[*]", "address"), lookupKey = "ENTITY.address", isEqualityCheck = false))
            )

            val bodyValue = JSONArrayValue(
                listOf(
                    JSONObjectValue(mapOf("name" to StringValue("Jane"), "address" to StringValue("123 Main St"))),
                    JSONObjectValue(mapOf("name" to StringValue("John"), "address" to StringValue("123 Main St"))),
                    JSONObjectValue(mapOf("name" to StringValue("May"), "address" to StringValue("123 Main St")))
                )
            )
            val currentStore = bodyValue.toFactStore("BODY")
            val actualStore = mapOf("ENTITY.name" to StringValue("John"), "ENTITY.address" to StringValue("456 Main St"))

            val result = assert.assert(currentStore, actualStore)
            println(result.reportString())

            assertThat(result).isInstanceOf(Result.Failure::class.java)
            assertThat(result.reportString()).isEqualToNormalizingWhitespace(toViolationReportString(
                breadCrumb = "BODY[1].address",
                details = "Expected \"123 Main St\" to equal \"456 Main St\"",
                StandardRuleViolation.VALUE_MISMATCH,
            ))
        }

        @Test
        fun `should execute else asserts when conditional asserts are not met`() {
            val assert = AssertConditional(
                keys = listOf("BODY", "[*]"),
                conditionalAsserts = listOf(AssertComparison(listOf("BODY", "[*]", "name"), lookupKey = "ENTITY.name", isEqualityCheck = true)),
                thenAsserts = listOf(AssertComparison(listOf("BODY", "[*]", "address"), lookupKey = "ENTITY.address", isEqualityCheck = true)),
                elseAsserts = listOf(AssertComparison(listOf("BODY", "[*]", "address"), lookupKey = "ENTITY.address", isEqualityCheck = false))
            )

            val bodyValue = JSONArrayValue(
                listOf(
                    JSONObjectValue(mapOf("name" to StringValue("Jane"), "address" to StringValue("123 Main St"))),
                    JSONObjectValue(mapOf("name" to StringValue("John"), "address" to StringValue("123 Main St"))),
                    JSONObjectValue(mapOf("name" to StringValue("May"), "address" to StringValue("123 Main St")))
                )
            )
            val currentStore = bodyValue.toFactStore("BODY")
            val actualStore = mapOf("ENTITY.name" to StringValue("John"), "ENTITY.address" to StringValue("123 Main St"))

            val result = assert.assert(currentStore, actualStore)
            println(result.reportString())

            assertThat(result).isInstanceOf(Result.Failure::class.java)
            assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
            ${
                toViolationReportString(
                    breadCrumb = "BODY[0].address",
                    details = "Expected \"123 Main St\" to not equal \"123 Main St\"",
                    StandardRuleViolation.VALUE_MISMATCH,
                )
            }
            ${
                toViolationReportString(
                    breadCrumb = "BODY[2].address",
                    details = "Expected \"123 Main St\" to not equal \"123 Main St\"",
                    StandardRuleViolation.VALUE_MISMATCH,
                )
            }
            """.trimIndent())
        }
    }

    @Test
    fun `should be able to create dynamic asserts based on prefix value`() {
        val arrayAssert = AssertConditional(
            keys = listOf("BODY", "[*]"),
            conditionalAsserts = listOf(AssertComparison(listOf("BODY", "[*]", "name"), lookupKey = "ENTITY.name", isEqualityCheck = true)),
            thenAsserts = listOf(AssertComparison(listOf("BODY", "[*]", "address"), lookupKey = "ENTITY.address", isEqualityCheck = true)),
            elseAsserts = listOf(AssertComparison(listOf("BODY", "[*]", "address"), lookupKey = "ENTITY.address", isEqualityCheck = false))
        )

        val jsonValue = JSONObjectValue(mapOf("name" to StringValue("Jane")))
        val arrayValue = JSONArrayValue(List(3) { jsonValue })

        val arrayBasedAsserts = arrayAssert.dynamicAsserts(arrayValue.toFactStore("BODY"))
        assertThat(arrayBasedAsserts.size).isEqualTo(3)
        arrayBasedAsserts.forEachIndexed { index, it ->

            assertThat(it.conditionalAsserts.size).isEqualTo(1)
            assertThat(it.conditionalAsserts).allSatisfy { assert ->
                assertThat(assert).isInstanceOf(AssertComparison::class.java); assert as AssertComparison
                assertThat(assert.keys).containsExactly("BODY", "[$index]", "name")
                assertThat(assert.lookupKey).isEqualTo("ENTITY.name")
                assertThat(assert.isEqualityCheck).isTrue
            }

            assertThat(it.thenAsserts.size).isEqualTo(1)
            assertThat(it.thenAsserts).allSatisfy { assert ->
                assertThat(assert).isInstanceOf(AssertComparison::class.java); assert as AssertComparison
                assertThat(assert.keys).containsExactly("BODY", "[$index]", "address")
                assertThat(assert.lookupKey).isEqualTo("ENTITY.address")
                assertThat(assert.isEqualityCheck).isTrue
            }

            assertThat(it.elseAsserts.size).isEqualTo(1)
            assertThat(it.elseAsserts).allSatisfy { assert ->
                assertThat(assert).isInstanceOf(AssertComparison::class.java); assert as AssertComparison
                assertThat(assert.keys).containsExactly("BODY", "[$index]", "address")
                assertThat(assert.lookupKey).isEqualTo("ENTITY.address")
                assertThat(assert.isEqualityCheck).isFalse
            }
        }

        val objectAssert = AssertConditional(
            keys = listOf("BODY"),
            conditionalAsserts = listOf(AssertComparison(listOf("BODY", "name"), lookupKey = "ENTITY.name", isEqualityCheck = true)),
            thenAsserts = listOf(AssertComparison(listOf("BODY", "address"), lookupKey = "ENTITY.address", isEqualityCheck = true)),
            elseAsserts = listOf(AssertComparison(listOf("BODY", "address"), lookupKey = "ENTITY.address", isEqualityCheck = false))
        )

        val jsonBasedAsserts = objectAssert.dynamicAsserts(jsonValue.toFactStore("BODY"))
        assertThat(jsonBasedAsserts.size).isEqualTo(1)
        assertThat(jsonBasedAsserts).allSatisfy {

            assertThat(it.conditionalAsserts.size).isEqualTo(1)
            assertThat(it.conditionalAsserts).allSatisfy { assert ->
                assertThat(assert).isInstanceOf(AssertComparison::class.java);assert as AssertComparison
                assertThat(assert.keys).containsExactly("BODY", "name")
                assertThat(assert.lookupKey).isEqualTo("ENTITY.name")
                assertThat(assert.isEqualityCheck).isTrue
            }

            assertThat(it.thenAsserts.size).isEqualTo(1)
            assertThat(it.thenAsserts).allSatisfy { assert ->
                assertThat(assert).isInstanceOf(AssertComparison::class.java); assert as AssertComparison
                assertThat(assert.keys).containsExactly("BODY", "address")
                assertThat(assert.lookupKey).isEqualTo("ENTITY.address")
                assertThat(assert.isEqualityCheck).isTrue
            }

            assertThat(it.elseAsserts.size).isEqualTo(1)
            assertThat(it.elseAsserts).allSatisfy { assert ->
                assertThat(assert).isInstanceOf(AssertComparison::class.java); assert as AssertComparison
                assertThat(assert.keys).containsExactly("BODY", "address")
                assertThat(assert.lookupKey).isEqualTo("ENTITY.address")
                assertThat(assert.isEqualityCheck).isFalse
            }
        }
    }

    @Test
    fun `should correctly align dynamic asserts when few generate more dynamic asserts than others`() {
        val assert = AssertConditional(
            keys = listOf("BODY", "[*]"),
            conditionalAsserts = listOf(
                AssertComparison(listOf("BODY", "[*]", "name"), lookupKey = "ENTITY.name", isEqualityCheck = true),
                AssertComparison(listOf("BODY", "[*]", "id"), lookupKey = "ENTITY.id", isEqualityCheck = true)
            ),
            thenAsserts = listOf(AssertComparison(listOf("BODY", "[*]", "orders", "[*]", "items", "[*]", "name"), lookupKey = "ENTITY.name", isEqualityCheck = true)),
            elseAsserts = listOf(AssertComparison(listOf("BODY", "[*]", "orders", "[*]", "items", "[*]", "price"), lookupKey = "ENTITY.price", isEqualityCheck = true))
        )

        val jsonValue = parsedJSON("""
        [
          {
            "name": "Alice",
            "id": "101",
            "orders": [
              {
                "items": [
                  {
                    "name": "Laptop",
                    "price": "1200"
                  },
                  {
                    "name": "Mouse",
                    "price": "50"
                  }
                ]
              },
              {
                "items": [
                  {
                    "name": "Keyboard",
                    "price": "80"
                  }
                ]
              }
            ]
          },
          {
            "name": "Bob",
            "id": "102",
            "orders": [
              {
                "items": [
                  {
                    "name": "Monitor",
                    "price": "300"
                  },
                  {
                    "name": "Headset",
                    "price": "100"
                  }
                ]
              }
            ]
          }
        ]
        """.trimIndent())
        val arrayBasedAsserts = assert.dynamicAsserts(jsonValue.toFactStore("BODY"))
        assertThat(arrayBasedAsserts.size).isEqualTo(2)

        arrayBasedAsserts.forEachIndexed { index, it ->
            assertThat(it.conditionalAsserts.size).isEqualTo(2)
            assertThat(it.conditionalAsserts).allSatisfy { assert ->
                assertThat(assert.keys).startsWith("BODY", "[$index]")
            }

            val expectedThenSize = if (index == 0) 3 else 2
            assertThat(it.thenAsserts.size).isEqualTo(expectedThenSize)
            assertThat(it.thenAsserts).allSatisfy { assert ->
                assertThat(assert.keys).startsWith("BODY", "[$index]")
            }

            val expectedElseSize = if (index == 0) 3 else 2
            assertThat(it.elseAsserts.size).isEqualTo(expectedElseSize)
            assertThat(it.elseAsserts).allSatisfy { assert ->
                assertThat(assert.keys).startsWith("BODY", "[$index]")
            }
        }
    }

    @Test
    fun `should be able to parse conditions and then and else asserts`() {
        val assertSyntax = JSONObjectValue(mapOf(
            "\$conditions" to JSONObjectValue(mapOf(
                "name" to StringValue("\$eq(ENTITY.name)"),
            )),
            "\$then" to JSONObjectValue(mapOf(
                "address" to StringValue("\$eq(ENTITY.address)")
            )),
            "\$else" to JSONObjectValue(mapOf(
                "address" to StringValue("\$neq(ENTITY.address)")
            ))
        ))

        val assert = parsedAssert("REQUEST.BODY", "\$if", assertSyntax, Resolver())

        assertThat(assert).isInstanceOf(AssertConditional::class.java); assert as AssertConditional
        assertThat(assert.keys).containsExactly("REQUEST", "BODY")

        assertThat(assert.conditionalAsserts.size).isEqualTo(1)
        assertThat(assert.conditionalAsserts).allSatisfy {
            assertThat(it).isInstanceOf(AssertComparison::class.java); it as AssertComparison
            assertThat(it.keys).containsExactly("REQUEST", "BODY", "name")
            assertThat(it.lookupKey).isEqualTo("ENTITY.name")
            assertThat(it.isEqualityCheck).isTrue
        }

        assertThat(assert.thenAsserts.size).isEqualTo(1)
        assertThat(assert.thenAsserts).allSatisfy {
            assertThat(it).isInstanceOf(AssertComparison::class.java); it as AssertComparison
            assertThat(it.keys).containsExactly("REQUEST", "BODY", "address")
            assertThat(it.lookupKey).isEqualTo("ENTITY.address")
            assertThat(it.isEqualityCheck).isTrue
        }

        assertThat(assert.elseAsserts.size).isEqualTo(1)
        assertThat(assert.elseAsserts).allSatisfy {
            assertThat(it).isInstanceOf(AssertComparison::class.java); it as AssertComparison
            assertThat(it.keys).containsExactly("REQUEST", "BODY", "address")
            assertThat(it.lookupKey).isEqualTo("ENTITY.address")
            assertThat(it.isEqualityCheck).isFalse
        }
    }
}