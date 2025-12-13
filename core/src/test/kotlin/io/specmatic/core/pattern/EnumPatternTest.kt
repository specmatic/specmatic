package io.specmatic.core.pattern

import io.specmatic.GENERATION
import io.specmatic.core.*
import io.specmatic.core.utilities.toValue
import io.specmatic.core.value.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EnumPatternTest {
    @Nested
    inner class Construction {
        @Test
        fun `it should accept values of the same type`() {
            assertDoesNotThrow {
                EnumPattern(listOf(StringValue("01"), StringValue("02")))
            }
        }

        @Test
        fun `it should not accept values of the different types when multiValue is set to false or default`() {
            assertThrows<ContractException> {
                EnumPattern(listOf(StringValue("01"), NumberValue(2)))
            }
        }

        @Test
        fun `it should accept values of the different types when multiValue is set to true`() {
            assertDoesNotThrow { toMultiValueEnum("01", 2) }
        }

        @Test
        fun `it should accept null alongside other homogenous value when nullable is true`() {
            assertDoesNotThrow {
                EnumPattern(listOf(StringValue("01"), StringValue("02"), NullValue), nullable = true)
            }

            assertDoesNotThrow { toMultiValueEnum("01", 2, null, nullable = true) }
        }

        @Test
        fun `it should not accept null alongside other homogenous value when nullable is false`() {
            assertThrows<ContractException> {
                EnumPattern(listOf(StringValue("01"), StringValue("02"), NullValue), nullable = false)
            }

            assertThrows<ContractException> { toMultiValueEnum("01", 2, null, nullable = false) }
        }
    }

    @Nested
    inner class GettingValues {
        @Test
        fun `it should generate a value from the given values`() {
            val enum = toMultiValueEnum("01", 10)
            val generatedValue = enum.generate(Resolver())
            assertThat(generatedValue).isIn(listOf(StringValue("01"), NumberValue(10)))
        }

        @Test
        fun `it should parse a new value to the enum type`() {
            val enum = toMultiValueEnum("01", 10, true)
            assertThat(listOf("Three" to "Three", "03" to 3, "false" to false)).allSatisfy { (string, expectation) ->
                val parsedValue = enum.parse(string, Resolver()); parsedValue as ScalarValue
                assertThat(parsedValue.nativeValue).isEqualTo(expectation)
            }
        }

        @Test
        fun `it should fail to parse a new value NOT matching the enum type`() {
            val enum = toMultiValueEnum( 10, true)
            assertThrows<ContractException> { enum.parse("not a number", Resolver()) }
        }
    }

    @Nested
    @Tag(GENERATION)
    inner class TestGeneration {
        @Test
        fun `it should generate new patterns for all enum values when the row is empty`() {
            val enum = EnumPattern(listOf(StringValue("01"), StringValue("02")))

            val newPatterns = enum.newBasedOn(Row(), Resolver()).map { it.value }.toList()

            assertThat(newPatterns).containsExactlyInAnyOrder(
                ExactValuePattern(StringValue("01")),
                ExactValuePattern(StringValue("02"))
            )
        }

        @Test
        fun `it should generate new patterns for all enum values when the row is empty with multi-value`() {
            val enum = toMultiValueEnum("Three", 3, true)
            val newPatterns = enum.newBasedOn(Row(), Resolver()).map { it.value }.toList()

            assertThat(newPatterns).containsExactlyInAnyOrder(
                ExactValuePattern(StringValue("Three")),
                ExactValuePattern(NumberValue(3)),
                ExactValuePattern(BooleanValue(true)),
            )
        }

        @Test
        fun `it should only pick the value in the row when the row is NOT empty`() {
            val jsonPattern =
                JSONObjectPattern(mapOf("type" to EnumPattern(listOf(StringValue("01"), StringValue("02")))))

            val newPatterns = jsonPattern.newBasedOn(Row(listOf("type"), values = listOf("01")), Resolver())
                .map { it.value as JSONObjectPattern }

            val values = newPatterns.map { it.generate(Resolver()) }.toList()

            val strings = values.map { it.jsonObject.getValue("type") as StringValue }

            assertThat(strings).containsExactly(
                StringValue("01")
            )
        }

        @Test
        fun `it should only pick the value in the row when the row is NOT empty with multiValue`() {
            val enumPattern = toMultiValueEnum("Four", 4, false)
            val jsonPattern = JSONObjectPattern(mapOf("type" to enumPattern))
            val row = Row(listOf("type"), values = listOf("Four"))

            val newPatterns = jsonPattern.newBasedOn(row, Resolver()).map { it.value as JSONObjectPattern }
            val values = newPatterns.map { it.generate(Resolver()) }.toList()
            val typeValues = values.map { it.jsonObject.getValue("type") }

            assertThat(typeValues).containsExactly(StringValue("Four"))
        }

        @Test
        fun `it should use the inline example if present when generating a value`() {
            val enum = EnumPattern(listOf("01", "02", "03", "04", "05").map { StringValue(it) }, example = "01")
            val value = enum.generate(Resolver())
            assertThat(value).isEqualTo(StringValue("01"))
        }

        @Test
        fun `it should use the inline example if present with multiValue`() {
            val enum = toMultiValueEnum("One", true, "Three", 3, true, example = "3")
            val value = enum.generate(Resolver())
            assertThat(value).isEqualTo(NumberValue(3))
        }

        @Test
        @Tag(GENERATION)
        fun `it should generate negative values for what is in the row`() {
            val jsonPattern = EnumPattern(listOf(StringValue("01"), StringValue("02")))
            val newPatterns = jsonPattern.negativeBasedOn(Row(), Resolver()).map { it.value }.toList()
            val negativeTypes = newPatterns.map { it.typeName }

            assertThat(negativeTypes).containsExactlyInAnyOrder("\"01_\"", "null", "number", "boolean")
        }

        @Test
        @Tag(GENERATION)
        fun `it should generate negative values for multiValues appropriately`() {
            val jsonPattern = toMultiValueEnum("Three", 3, true)
            val newPatterns = jsonPattern.negativeBasedOn(Row(), Resolver()).map { it.value }.toList()
            val negativeTypes = newPatterns.map { it.typeName }

            assertThat(negativeTypes).containsExactlyInAnyOrder("4", "null", "false", "\"Three_\"")
        }

        @Test
        @Tag(GENERATION)
        fun `should not duplicate altered-value for the same data types and skip overlaps`() {
            val jsonPattern = toMultiValueEnum("One", "Two", 1, 2, true, false, null, nullable = true)
            val newPatterns = jsonPattern.negativeBasedOn(Row(), Resolver()).map { it.value }.toList()
            val negativeTypes = newPatterns.map { it.typeName }

            assertThat(negativeTypes).containsExactlyInAnyOrder("3", "\"One_\"")
        }

        @Test
        @Tag(GENERATION)
        fun `should be able to generate patterns from const enum null`() {
            val enum = EnumPattern(listOf(NullValue), nullable = true)
            val newBasedOn = enum.newBasedOn(Resolver()).toList()
            val negativeBasedOn = enum.negativeBasedOn(Row(), Resolver()).map { it.value.typeName }.toList()

            assertThat(newBasedOn).containsExactlyInAnyOrder(ExactValuePattern(NullValue))
            assertThat(negativeBasedOn).containsExactlyInAnyOrder("string", "number", "boolean")
        }

        @Test
        @Tag(GENERATION)
        fun `should retain the correct enum newBasedOn return value details`() {
            val pattern = toMultiValueEnum("One", 1)
            val newPatterns = pattern.newBasedOn(Row(), Resolver()).toList()

            assertThat(newPatterns).allSatisfy {
                assertThat(it).isInstanceOf(HasValue::class.java); it as HasValue<Pattern>
                val details = it.valueDetails.singleLineDescription()
                assertThat(it.value).satisfiesAnyOf(
                    { pattern ->
                        assertThat(pattern).isEqualTo(ExactValuePattern(StringValue("One")))
                        assertThat(details).isEqualToIgnoringWhitespace("is set to 'One' from enum")
                    },
                    { pattern ->
                        assertThat(pattern).isEqualTo(ExactValuePattern(NumberValue(1)))
                        assertThat(details).isEqualToIgnoringWhitespace("is set to '1' from enum")
                    }
                )
            }
        }

        @Test
        @Tag(GENERATION)
        fun `should retain the correct enum negativeBasedOn return value details`() {
            val pattern = toMultiValueEnum("One", 1)
            val newPatterns = pattern.negativeBasedOn(Row(), Resolver()).toList()

            assertThat(newPatterns).allSatisfy {
                assertThat(it).isInstanceOf(HasValue::class.java); it as HasValue<Pattern>
                val details = it.valueDetails.singleLineDescription()
                assertThat(it.value).satisfiesAnyOf(
                    { pattern ->
                        assertThat(pattern).isEqualTo(ExactValuePattern(StringValue("One_")))
                        assertThat(details).isEqualToIgnoringWhitespace("is mutated from (1 or \"One\") to \"One_\"")
                    },
                    { pattern ->
                        assertThat(pattern).isEqualTo(ExactValuePattern(NumberValue(2)))
                        assertThat(details).isEqualToIgnoringWhitespace("is mutated from (1 or \"One\") to 2")
                    },
                    { pattern ->
                        assertThat(pattern).isEqualTo(BooleanPattern())
                        assertThat(details).isEqualToIgnoringWhitespace("is mutated from (1 or \"One\") to boolean")
                    },
                    { pattern ->
                        assertThat(pattern).isEqualTo(NullPattern)
                        assertThat(details).isEqualToIgnoringWhitespace("is mutated from (1 or \"One\") to null")
                    }
                )
            }
        }
    }

    private fun toStringEnum(vararg items: String): EnumPattern {
        return EnumPattern(items.map { StringValue(it) })
    }

    private fun toMultiValueEnum(vararg items: Any?, nullable: Boolean = false, example: String? = null, typeAlias: String? = null): EnumPattern {
        return EnumPattern(items.map { toValue(it) }, nullable = nullable, multiType = true, example = example, typeAlias = typeAlias)
    }

    @Nested
    inner class BackwardCompatibility {
        private val enum = toStringEnum("sold", "available")

        @Test
        fun `enums with more are not backward compatible than enums with less`() {
            val enumWithMore = toStringEnum("sold", "available", "reserved")
            assertThat(enum.encompasses(enumWithMore, Resolver(), Resolver())).isInstanceOf(Result.Failure::class.java)
        }

        @Test
        fun `enums with less are backward compatible than enums with more`() {
            val enumWithLess = toStringEnum("sold")
            assertThat(enum.encompasses(enumWithLess, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `enums with a new data-type is not backward-compatible with existing`() {
            val original = toMultiValueEnum("One", "Two", "Three")
            val new = toMultiValueEnum("One", "Two", "Three", 1, 2, 3)
            val result = original.encompasses(new, Resolver(), Resolver())
            assertThat(result).isInstanceOf(Result.Failure::class.java)
        }

        @Test
        fun `enums with a dropped data-type is backward-compatible with existing`() {
            val original = toMultiValueEnum("One", "Two", "Three", 1, 2, 3)
            val new = toMultiValueEnum("One", "Two", "Three")
            val result = original.encompasses(new, Resolver(), Resolver())
            assertThat(result).withFailMessage(result.reportString()).isInstanceOf(Result.Success::class.java)
        }
    }

    @Nested
    inner class ErrorMessage {
        @Test
        fun `enum error message should feature the expected values`() {
            val result: Result = EnumPattern(
                listOf(
                    StringValue("01"),
                    StringValue("02")
                )
            ).encompasses(
                StringPattern(), Resolver(), Resolver()
            )

            val resultText = result.reportString()

            println(resultText)

            assertThat(resultText).contains("01")
            assertThat(resultText).contains("02")
        }

        @Test
        fun `enum error message should feature the expected values with multiType enumerable`() {
            val result = toMultiValueEnum("One", "Two", 1, 2).encompasses(StringPattern(), Resolver(), Resolver())
            val resultText = result.reportString()
            assertThat(resultText).isEqualToIgnoringWhitespace("""Expected (1 or 2 or "One" or "Two"), actual was string""")
        }
    }

    @Test
    fun `should be able to generate a value when pattern token of itself is supplied`() {
        val enumPattern = toMultiValueEnum("Cat", "Dog", "Fish", 1, 2, true, false, typeAlias = "(Test)")
        val resolver = Resolver(newPatterns = mapOf("(Test)" to enumPattern))
        val value = StringValue("(Test)")
        val filledInValue = enumPattern.fillInTheBlanks(value, resolver).value as ScalarValue

        assertThat(filledInValue.nativeValue).isIn("Cat", "Dog", "Fish", 1, 2, true, false)
    }

    @Test
    fun `should use dictionary value when filling in from pattern token`() {
        val enumValues: List<Value> = listOf("Cat", "Dog", "Fish").map { StringValue(it) }
        val enumPattern = EnumPattern(enumValues, typeAlias = "(AnimalType)")
        val jsonPattern = JSONObjectPattern(mapOf("type" to enumPattern), typeAlias = "(Test)")

        val dictionary = "AnimalType: Dog".let(Dictionary::fromYaml)
        val resolver = Resolver(newPatterns = mapOf("(AnimalType)" to enumPattern), dictionary = dictionary)
        val value = JSONObjectValue(mapOf("type" to StringValue("(AnimalType)")))
        val filledInValue = jsonPattern.fillInTheBlanks(value, resolver).value

        assertThat(filledInValue).isEqualTo(JSONObjectValue(mapOf("type" to StringValue("Dog"))))
    }

    @Test
    fun `should use matching dictionary value for all types in multi-type enum`() {
        val enumPattern = toMultiValueEnum("Cat", "Dog", "Fish", 1, 2, true, false, typeAlias = "(AnimalType)")
        val jsonPattern = JSONObjectPattern(mapOf("type" to enumPattern), typeAlias = "(Test)")
        val testCases = listOf(
            "Dog" to StringValue("Dog"),
            "2" to NumberValue(2),
            "true" to BooleanValue(true)
        )

        assertThat(testCases).allSatisfy { (dictValue, expectedValue) ->
            val dictionaryYaml = "AnimalType: $dictValue"
            val dictionary = Dictionary.fromYaml(dictionaryYaml)
            val resolver = Resolver(newPatterns = mapOf("(AnimalType)" to enumPattern), dictionary = dictionary)
            val value = JSONObjectValue(mapOf("type" to StringValue("(AnimalType)")))
            val filledInValue = jsonPattern.fillInTheBlanks(value, resolver).value

            assertThat(filledInValue)
                .withFailMessage("Dictionary value $dictValue should fill as $expectedValue")
                .isEqualTo(JSONObjectValue(mapOf("type" to expectedValue)))
        }
    }

    @Test
    fun `should be able to fix invalid values`() {
        val enumValues: List<Value> = listOf("Cat", "Dog", "Fish").map { StringValue(it) }
        val enumPattern = EnumPattern(enumValues, typeAlias = "(AnimalType)")
        val pattern = JSONObjectPattern(mapOf("type" to enumPattern), typeAlias = "(Test)")
        val dictionary = "Test: { type: Dog }".let(Dictionary::fromYaml)
        val resolver = Resolver(dictionary = dictionary)
        val invalidValues = listOf(
            StringValue("Unknown"),
            NumberValue(999),
            NullValue
        )

        assertThat(invalidValues).allSatisfy {
            val fixedValue = pattern.fixValue(JSONObjectValue(mapOf("type" to it)), resolver)
            fixedValue as JSONObjectValue
            assertThat(fixedValue.jsonObject["type"]).isIn(enumValues)
        }
    }

    @Test
    fun `should be able to fix invalid values for multi-type enum`() {
        val enumPattern = toMultiValueEnum("Cat", "Dog", "Fish", 1, 2, true, typeAlias = "(AnimalType)")
        val pattern = JSONObjectPattern(mapOf("type" to enumPattern), typeAlias = "(Test)")
        val dictionary = "Test: { type: Dog }".let(Dictionary::fromYaml)
        val resolver = Resolver(dictionary = dictionary)
        val invalidValues = listOf(StringValue("Unknown"), NumberValue(999.0), NullValue)

        assertThat(invalidValues).allSatisfy {
            val fixedValue = pattern.fixValue(JSONObjectValue(mapOf("type" to it)), resolver); fixedValue as JSONObjectValue
            val scalarValue = fixedValue.jsonObject["type"] as ScalarValue
            assertThat(scalarValue.nativeValue).isIn("Cat", "Dog", "Fish", 1, 2, true)
        }
    }

    @Test
    fun `resolveSubstitutions should return value when no substitution needed`() {
        val enumValues = listOf(StringValue("active"), StringValue("inactive"))
        val pattern = EnumPattern(enumValues)
        val resolver = Resolver()
        val substitution = Substitution(
            HttpRequest("GET", "/", mapOf(), EmptyString),
            HttpRequest("GET", "/", mapOf(), EmptyString),
            HttpPathPattern(emptyList(), ""),
            HttpHeadersPattern(mapOf()),
            EmptyStringPattern,
            resolver,
            JSONObjectValue(mapOf())
        )
        val validValue = StringValue("active")

        val result = pattern.resolveSubstitutions(substitution, validValue, resolver, null)

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue).value).isEqualTo(validValue)
    }

    @Test
    fun `resolveSubstitutions should handle data lookup substitution with valid enum value`() {
        val enumValues = listOf(StringValue("active"), StringValue("inactive"), StringValue("pending"))
        val pattern = EnumPattern(enumValues)
        val originalRequest = HttpRequest("POST", "/person", body = JSONObjectValue(mapOf("department" to StringValue("(DEPARTMENT:string)"))))
        val runningRequest = HttpRequest("POST", "/person", body = JSONObjectValue(mapOf("department" to StringValue("engineering"))))
        val resolver = Resolver()
        val dataLookup = JSONObjectValue(mapOf(
            "dataLookup" to JSONObjectValue(mapOf(
                "dept" to JSONObjectValue(mapOf(
                    "engineering" to JSONObjectValue(mapOf(
                        "status" to StringValue("active")
                    )),
                    "sales" to JSONObjectValue(mapOf(
                        "status" to StringValue("pending")
                    ))
                ))
            ))
        ))
        val substitution = Substitution(
            runningRequest,
            originalRequest,
            HttpPathPattern(emptyList(), ""),
            HttpHeadersPattern(mapOf()),
            JSONObjectPattern(mapOf("department" to StringPattern())),
            resolver,
            dataLookup
        )
        val valueExpression = StringValue("$(dataLookup.dept[DEPARTMENT].status)")

        val result = pattern.resolveSubstitutions(substitution, valueExpression, resolver, null)

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue).value).isEqualTo(StringValue("active"))
    }

    @Test
    fun `resolveSubstitutions should handle data lookup substitution with valid multi-type enum value`() {
        val enumPattern = toMultiValueEnum("active", "inactive", "pending", 1, true)
        val originalRequest = HttpRequest("POST", "/person", body = JSONObjectValue(mapOf("department" to StringValue("(DEPARTMENT:string)"))))
        val runningRequest = HttpRequest("POST", "/person", body = JSONObjectValue(mapOf("department" to StringValue("engineering"))))
        val resolver = Resolver()
        val dataLookup = JSONObjectValue(mapOf(
            "dataLookup" to JSONObjectValue(mapOf(
                "dept" to JSONObjectValue(mapOf(
                    "engineering" to JSONObjectValue(mapOf("status" to StringValue("active"))),
                    "sales" to JSONObjectValue(mapOf("status" to StringValue("pending"))),
                    "ops" to JSONObjectValue(mapOf("status" to NumberValue(1))),
                    "qa" to JSONObjectValue(mapOf("status" to BooleanValue(true)))
                ))
            ))
        ))

        val substitution = Substitution(
            runningRequest,
            originalRequest,
            HttpPathPattern(emptyList(), ""),
            HttpHeadersPattern(mapOf()),
            JSONObjectPattern(mapOf("department" to StringPattern())),
            resolver,
            dataLookup
        )
        val valueExpression = StringValue("$(dataLookup.dept[DEPARTMENT].status)")
        val result = enumPattern.resolveSubstitutions(substitution, valueExpression, resolver, null)

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat(((result as HasValue).value as ScalarValue).nativeValue).isIn("active", "inactive", "pending", 1, true)
    }

    @Test
    fun `resolveSubstitutions should fail when substituted value not in enum`() {
        val enumValues = listOf(StringValue("active"), StringValue("inactive"))
        val pattern = EnumPattern(enumValues)
        val originalRequest = HttpRequest("POST", "/person", body = JSONObjectValue(mapOf("department" to StringValue("(DEPARTMENT:string)"))))
        val runningRequest = HttpRequest("POST", "/person", body = JSONObjectValue(mapOf("department" to StringValue("engineering"))))
        val resolver = Resolver()
        val dataLookup = JSONObjectValue(mapOf(
            "dataLookup" to JSONObjectValue(mapOf(
                "dept" to JSONObjectValue(mapOf(
                    "engineering" to JSONObjectValue(mapOf(
                        "status" to StringValue("suspended")
                    ))
                ))
            ))
        ))
        val substitution = Substitution(
            runningRequest,
            originalRequest,
            HttpPathPattern(emptyList(), ""),
            HttpHeadersPattern(mapOf()),
            JSONObjectPattern(mapOf("department" to StringPattern())),
            resolver,
            dataLookup
        )
        val valueExpression = StringValue("$(dataLookup.dept[DEPARTMENT].status)")

        val result = pattern.resolveSubstitutions(substitution, valueExpression, resolver, null)

        assertThat(result).isInstanceOf(HasFailure::class.java)
    }

    @Test
    fun `resolveSubstitutions should fail when substituted value not in multi-type enum`() {
        val enumPattern = toMultiValueEnum("active", "inactive")
        val originalRequest = HttpRequest("POST", "/person", body = JSONObjectValue(mapOf("department" to StringValue("(DEPARTMENT:string)"))))
        val runningRequest = HttpRequest("POST", "/person", body = JSONObjectValue(mapOf("department" to StringValue("engineering"))))
        val resolver = Resolver()
        val dataLookup = JSONObjectValue(mapOf(
            "dataLookup" to JSONObjectValue(mapOf(
                "dept" to JSONObjectValue(mapOf(
                    "engineering" to JSONObjectValue(mapOf("status" to StringValue("suspended")))
                ))
            ))
        ))

        val substitution = Substitution(
            runningRequest,
            originalRequest,
            HttpPathPattern(emptyList(), ""),
            HttpHeadersPattern(mapOf()),
            JSONObjectPattern(mapOf("department" to StringPattern())),
            resolver,
            dataLookup
        )
        val valueExpression = StringValue("$(dataLookup.dept[DEPARTMENT].status)")
        val result = enumPattern.resolveSubstitutions(substitution, valueExpression, resolver, null)

        assertThat(result).isInstanceOf(HasFailure::class.java)
    }

    @Test
    fun `toNullable should return self with no modifications if already nullable`() {
        val patterns = listOf(
            EnumPattern(listOf("A", "B").map(::StringValue).plus(NullValue), nullable = true),
            toMultiValueEnum("One", 1, true, null, nullable = true),
        )

        assertThat(patterns).allSatisfy { pattern ->
            assertThat(pattern.toNullable(null)).isEqualTo(pattern)
        }
    }

    @Test
    fun `toNullable should add NullValue and set nullable if pattern wasn't already nullable`() {
        val patterns = listOf(
            EnumPattern(listOf("A", "B").map(::StringValue)),
            toMultiValueEnum("One", 1, true),
        )

        assertThat(patterns).allSatisfy { pattern ->
            val nullablePattern = pattern.toNullable(null)
            assertThat(nullablePattern.nullable).isTrue
            assertThat(nullablePattern.pattern.pattern).contains(ExactValuePattern(NullValue))
        }
    }
}
