package io.specmatic.core.pattern

import io.specmatic.*
import io.specmatic.core.*
import io.specmatic.core.utilities.withNullPattern
import io.specmatic.core.value.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

internal class AnyPatternTest {
    @Test
    fun `should be able to generate for a discriminator value when it's nested inside another AnyPattern`() {
        val discriminator = Discriminator(
            property = "type",
            values = setOf("simple", "complex"),
            mapping = mapOf("simple" to "#/components/schemas/Simple", "complex" to "#/components/schemas/Complex")
        )

        val otherPattern =  AnyPattern(pattern = listOf(
                JSONObjectPattern(mapOf("type" to "simple".toDiscriminator(), "prop1" to StringPattern()), typeAlias = "(Simple)"),
                JSONObjectPattern(mapOf("type" to "complex".toDiscriminator(), "prop2" to NumberPattern()), typeAlias = "(Complex)"),
            ), discriminator = discriminator)
        val pattern = AnyPattern(pattern = listOf(otherPattern), discriminator = discriminator)

        val simpleObject = pattern.generateValue(Resolver(), "simple")
        val complexObject = pattern.generateValue(Resolver(), "complex")

        println(simpleObject)
        assertThat(simpleObject).isInstanceOf(JSONObjectValue::class.java)
        simpleObject as JSONObjectValue
        assertThat(simpleObject.keys()).containsExactlyInAnyOrder("type", "prop1")
        assertThat(simpleObject.getString("type")).isEqualTo("simple")

        println(complexObject)
        assertThat(complexObject).isInstanceOf(JSONObjectValue::class.java)
        complexObject as JSONObjectValue
        assertThat(complexObject.keys()).containsExactlyInAnyOrder("type", "prop2")
        assertThat(complexObject.getString("type")).isEqualTo("complex")
    }

    @Test
    fun `should be able to generate for a discriminator value when the discriminator key is optional in the pattern`() {
        val discriminator = Discriminator(
            property = "type",
            values = setOf("simple", "complex"),
            mapping = mapOf("simple" to "#/components/schemas/Simple", "complex" to "#/components/schemas/Complex")
        )

        val otherPattern =  AnyPattern(pattern = listOf(
            JSONObjectPattern(mapOf("type?" to "simple".toDiscriminator(), "prop1" to StringPattern()), typeAlias = "(Simple)"),
            JSONObjectPattern(mapOf("type?" to "complex".toDiscriminator(), "prop2" to NumberPattern()), typeAlias = "(Complex)"),
        ), discriminator = discriminator)
        val pattern = AnyPattern(pattern = listOf(otherPattern), discriminator = discriminator)

        val simpleObject = pattern.generateValue(Resolver(), "simple")
        val complexObject = pattern.generateValue(Resolver(), "complex")

        println(simpleObject)
        assertThat(simpleObject).isInstanceOf(JSONObjectValue::class.java)
        simpleObject as JSONObjectValue
        assertThat(simpleObject.keys()).containsExactlyInAnyOrder("type", "prop1")
        assertThat(simpleObject.getString("type")).isEqualTo("simple")

        println(complexObject)
        assertThat(complexObject).isInstanceOf(JSONObjectValue::class.java)
        complexObject as JSONObjectValue
        assertThat(complexObject.keys()).containsExactlyInAnyOrder("type", "prop2")
        assertThat(complexObject.getString("type")).isEqualTo("complex")
    }

    @Test
    fun `should match multiple patterns`() {
        val pattern = AnyPattern(listOf(NumberPattern(), StringPattern()), extensions = emptyMap())
        val string = StringValue("hello")
        val number = NumberValue(10)

        string shouldMatch pattern
        number shouldMatch pattern
    }

    @Test
    fun `should match the correct pattern and filter optional keys from value`() {
        val objectPattern = parsedPattern("""{
            "topLevelMandatoryKey": "(number)",
            "topLevelOptionalKey?": "(string)",
            "subMandatoryObject": {
                "subMandatoryKey": "(string)",
                "subOptionalKey?": "(number)"
            }
        }
        """.trimIndent())
        val listPattern = ListPattern(objectPattern)
        val pattern = AnyPattern(listOf(objectPattern, listPattern), extensions = emptyMap())

        val objectValue = parsedValue("""{
            "topLevelMandatoryKey": 10,
            "topLevelOptionalKey": "hello",
            "subMandatoryObject": {
                "subMandatoryKey": "hello",
                "subOptionalKey": 10
            }
        }
        """.trimIndent())
        val expectedObjectValue = parsedValue("""{
            "topLevelMandatoryKey": 10,
            "subMandatoryObject": {
                "subMandatoryKey": "hello"
            }
        }   
        """.trimIndent())

        val filteredObjectValue = pattern.eliminateOptionalKey(objectValue, Resolver())
        assertEquals(expectedObjectValue, filteredObjectValue)

        val filteredListValue = pattern.eliminateOptionalKey(JSONArrayValue(listOf(objectValue, objectValue)), Resolver())
        assertEquals(JSONArrayValue(listOf(expectedObjectValue, expectedObjectValue)), filteredListValue)
    }

    @Test
    fun `error message when a json object does not match nullable primitive such as string in the contract`() {
        val pattern1 = AnyPattern(listOf(NullPattern, StringPattern()), extensions = emptyMap())
        val pattern2 = AnyPattern(listOf(DeferredPattern("(empty)"), StringPattern()), extensions = emptyMap())

        val value = parsedValue("""{"firstname": "Jane", "lastname": "Doe"}""")

        val resolver = withNullPattern(Resolver())

        val result1 = pattern1.matches(value, resolver)
        val result2 = pattern2.matches(value, resolver)

        assertThat(result2.toReport().toText().trimIndent()).isEqualTo("""
        Expected string, actual was JSON object {
            "firstname": "Jane",
            "lastname": "Doe"
        }""".trimIndent())

        assertThat(result1.toReport().toText().trimIndent()).isEqualTo("""
        Expected string, actual was JSON object {
            "firstname": "Jane",
            "lastname": "Doe"
        }""".trimIndent())
    }

    @Test
    fun `should return error message when object type matches but there is another mismatch and no ambiguous pattern in AnyPattern list`() {
        val jsonPattern = JSONObjectPattern(
            mapOf(
                "firstname" to StringPattern(),
                "lastname" to NumberPattern()
            )
        )
        val pattern = AnyPattern(listOf(NullPattern, DeferredPattern(pattern = "(Person)")), extensions = emptyMap())
        val value = parsedValue("""{"firstname": "Jane", "lastname": "Doe"}""")
        val resolver = withNullPattern(Resolver(
            newPatterns = mapOf("(Person)" to jsonPattern)
        ))

        val result = pattern.matches(value, resolver)

        assertThat(result.toReport().toText().trimIndent()).doesNotContain("when Person object")
    }

    @Test
    fun `typename of a nullable type`() {
        val pattern1 = AnyPattern(listOf(NullPattern, StringPattern()), extensions = emptyMap())
        val pattern2 = AnyPattern(listOf(DeferredPattern("(empty)"), StringPattern()), extensions = emptyMap())

        assertThat(pattern1.typeName).isEqualTo("(string?)")
        assertThat(pattern2.typeName).isEqualTo("(string?)")
    }

    @Test
    @Tag(GENERATION)
    fun `should generate new patterns for all available types`() {
        AnyPattern(
            listOf(
                NumberPattern(),
                EnumPattern(listOf(StringValue("one"), StringValue("two")))
            ),
            extensions = emptyMap()
        ).newBasedOn(Row(), Resolver()).map { it.value }.toList().let { patterns ->
            patterns.map { it.typeName } shouldContainInAnyOrder listOf("number", "\"one\"", "\"two\"")
        }
    }

    @Test
    @Tag(GENERATION)
    fun `should create a new pattern based on the given row`() {
        val pattern = AnyPattern(listOf(parsedPattern("""{"id": "(number)"}""")), extensions = emptyMap())
        val row = Row(listOf("id"), listOf("10"))

        val value = pattern.newBasedOn(row, Resolver()).map { it.value }.first().generate(Resolver())

        if(value is JSONObjectValue) {
            val id = value.jsonObject.getValue("id")

            if(id is NumberValue)
                assertEquals(10, id.number)
            else fail("Expected NumberValue")
        } else fail("Expected JSONObjectValue")
    }

    @Test
    @Tag(GENERATION)
    fun `should create new patterns when the row has no values`() {
        val pattern = AnyPattern(listOf(parsedPattern("""{"id": "(number)"}""")), extensions = emptyMap())
        val value = pattern.newBasedOn(Row(), Resolver()).map { it.value }.first().generate(Resolver())

        value as JSONObjectValue

        val id = value.jsonObject.getValue("id")

        assertThat(id).isInstanceOf(NumberValue::class.java)
    }

    @Test
    fun `should generate a value based on the pattern given`() {
        NumberValue(10) shouldMatch AnyPattern(listOf(parsedPattern("(number)")), extensions = emptyMap())
    }

    @Test
    fun `AnyPattern of null and string patterns should encompass null pattern`() {
        assertThat(
            AnyPattern(listOf(NullPattern, StringPattern()), extensions = emptyMap()).encompasses(
                NullPattern,
                Resolver(),
                Resolver()
            )
        ).isInstanceOf(
            Result.Success::class.java
        )
    }

    @Test
    fun `should encompass any of the specified types`() {
        val bigger = parsedPattern("""(string?)""")
        val smallerString = StringPattern()
        val smallerNull = emptyPattern()

        assertThat(bigger.encompasses(smallerString, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
        assertThat(bigger.encompasses(smallerNull, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should encompass itself`() {
        val bigger = parsedPattern("""(string?)""")
        assertThat(bigger.encompasses(bigger, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should encompass another any with fewer types`() {
        val bigger = parsedPattern("""(string?)""")
        val anyOfString = AnyPattern(listOf(StringPattern()), extensions = emptyMap())

        assertThat(bigger.encompasses(anyOfString, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should encompass itself with a list type`() {
        val bigger = parsedPattern("""(string*?)""")
        assertThat(bigger.encompasses(bigger, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should encompass a matching string list type`() {
        val bigger = parsedPattern("""(string*?)""")
        val smallerStringList = parsedPattern("(string*)")
        assertThat(bigger.encompasses(smallerStringList, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should encompass a matching null type`() {
        val bigger = parsedPattern("""(string*?)""")
        val smallerNull = emptyPattern()
        assertThat(bigger.encompasses(smallerNull, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `typeName should show nullable when one of the types is null`() {
        val type = AnyPattern(pattern = listOf(NullPattern, NumberPattern()), extensions = emptyMap())
        assertThat(type.typeName).isEqualTo("(number?)")
    }

    private fun toEnum(items: List<Value>): AnyPattern {
        return AnyPattern(items.map { ExactValuePattern(it) }, extensions = emptyMap())
    }

    private fun toStringEnum(vararg items: String): AnyPattern {
        return toEnum(items.map { StringValue(it) })
    }

    @Nested
    inner class EnumBackwardCompatibility {
        private val enum = toStringEnum("sold", "available")

        @Test
        fun `enums with more are backward compatible than enums with less`() {
            val enumWithMore = toStringEnum("sold", "available", "reserved")
            assertThat(enum.encompasses(enumWithMore, Resolver(), Resolver())).isInstanceOf(Result.Failure::class.java)
        }

        @Test
        fun `enums with less are not backward compatible than enums with more`() {
            val enumWithLess = toStringEnum("sold")
            assertThat(enum.encompasses(enumWithLess, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
        }
    }

    @Nested
    inner class EnumErrorMessage {
        @Test
        fun `enum error message should feature the expected values`() {
            val result: Result = AnyPattern(
                listOf(
                    ExactValuePattern(StringValue("01")),
                    ExactValuePattern(StringValue("02"))
                ),
                extensions = emptyMap()
            ).encompasses(
                StringPattern(), Resolver(), Resolver()
            )

            val resultText = result.reportString()

            println(resultText)

            assertThat(resultText).contains("01")
            assertThat(resultText).contains("02")
        }
    }

    @Test
    fun `parse operation of Nullable type implemented with AnyPattern should return a string`() {
        val type = AnyPattern(listOf(NullPattern, StringPattern()), extensions = emptyMap())
        val parsedValue = type.parse("22B Baker Street", Resolver(isNegative = true))
        assertThat(parsedValue.toStringLiteral()).isEqualTo("22B Baker Street")
    }

    @Test
    @Tag(GENERATION)
    fun `values for negative tests`() {
        val negativeTypes =
            AnyPattern(listOf(NullPattern, StringPattern()), extensions = emptyMap()).negativeBasedOn(Row(), Resolver()).map { it.value }
                .toList()

        assertThat(negativeTypes).containsExactlyInAnyOrder(
            NumberPattern(),
            BooleanPattern()
        )
    }

    @Test
    fun `we should get deep errors errors with breadcrumbs for each possible type in a oneOf list`() {
        val customerType = JSONObjectPattern(mapOf("name" to StringPattern()), typeAlias = "(Customer)")
        val employeeType = JSONObjectPattern(mapOf("name" to StringPattern(), "manager" to StringPattern()), typeAlias = "(Employee)")
        val oneOfCustomerOrEmployeeType = AnyPattern(listOf(customerType, employeeType), extensions = emptyMap())

        val personType = JSONObjectPattern(mapOf("personInfo" to oneOfCustomerOrEmployeeType))

        val personData = parsedJSONObject("""{ "personInfo": { "name": "Sherlock Holmes", "salutation": "Mr" } }""")

        val personMatchResult = personType.matches(personData, Resolver()).reportString()

        assertThat(personMatchResult.trimmedLinesString()).contains("""
            >> personInfo (when Customer object).salutation
            
               Key named "salutation" was unexpected
            
            >> personInfo (when Employee object).manager
            
               Expected key named "manager" was missing
            
            >> personInfo (when Employee object).salutation
            
               Key named "salutation" was unexpected
       """.trimIndent().trimmedLinesString())
    }

    @Test
    fun `should wrap values in the relevant list type`() {
        val type = AnyPattern(listOf(NullPattern, StringPattern()), extensions = emptyMap())
        val wrappedList = type.listOf(listOf(StringValue("It's me"), StringValue("Hi"), StringValue("I'm the problem it's me")), Resolver()) as JSONArrayValue

        val wrappedValues = wrappedList.list.map { it.toStringLiteral() }
        val expectedValues = listOf("It's me", "Hi", "I'm the problem it's me")

        assertThat(wrappedValues).isEqualTo(expectedValues)
    }

    @Test
    fun `should wrap values in the relevant list type when the AnyPattern object represents an enum with 3 options`() {
        val type = AnyPattern(
            listOf(
                ExactValuePattern(StringValue("one")),
                ExactValuePattern(StringValue("two")),
                ExactValuePattern(StringValue("three"))
            ),
            extensions = emptyMap()
        )
        val listOf = type.listOf(listOf(StringValue("one"), StringValue("two"), StringValue("three")), Resolver())

        assertEquals(3, (listOf as JSONArrayValue).list.size)
    }

    @Test
    fun `error when a discriminator exists and the value is not a json object`() {
        val pattern = AnyPattern(emptyList(), discriminatorProperty = "type", discriminatorValues = setOf("current", "savings"))
        val result = pattern.matches(StringValue(""), Resolver())

        assertThat(result.reportString()).contains("Expected json object")
    }

    @Test
    fun `should include info breadcrumbs when value does not match any discriminator patterns and key check is partial`() {
        val pattern = AnyPattern(
            listOf(
                JSONObjectPattern(mapOf("type" to "sub1".toDiscriminator(), "prop" to StringPattern(), "extra" to StringPattern()), typeAlias = "(Sub1)"),
                JSONObjectPattern(mapOf("type" to "sub2".toDiscriminator(), "prop" to NumberPattern()), typeAlias = "(Sub2)")
            ), typeAlias = "(Base)",
            discriminator = Discriminator(
                property = "type",
                values = setOf("sub1", "sub2"),
                mapping = mapOf("sub1" to "#/components/schemas/Sub1", "sub2" to "#/components/schemas/Sub2")
            )
        )
        val invalidValue = JSONObjectValue(mapOf("newKey" to StringValue("value"), "prop" to BooleanValue(true)))
        val result = pattern.matches(invalidValue, Resolver().partializeKeyCheck())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""    
        >> (when Sub1 object).newKey
        Key named "newKey" was unexpected 
        >> (when Sub1 object).prop
        Expected string, actual was true (boolean)

        >> (when Sub2 object).newKey 
        Key named "newKey" was unexpected
        >> (when Sub2 object).prop
        Expected number, actual was true (boolean)
        """.trimIndent())
    }

    @Test
    fun `matches should allow missing discriminator key when keyCheck is partial`() {
        val pattern = AnyPattern(
            listOf(
                JSONObjectPattern(mapOf("type" to "sub1".toDiscriminator(), "prop" to StringPattern(), "extra" to StringPattern()), typeAlias = "(Sub1)"),
                JSONObjectPattern(mapOf("type" to "sub2".toDiscriminator(), "prop" to NumberPattern()), typeAlias = "(Sub2)")
            ), typeAlias = "(Base)",
            discriminator = Discriminator(
                property = "type",
                values = setOf("sub1", "sub2"),
                mapping = mapOf("sub1" to "#/components/schemas/Sub1", "sub2" to "#/components/schemas/Sub2")
            )
        )

        val partialResolvers = listOf(
            Resolver().partializeKeyCheck(),
            Resolver(findKeyErrorCheck = KeyCheck(noPatternKeyCheck, IgnoreUnexpectedKeys)),
            Resolver(findKeyErrorCheck = KeyCheck(noPatternKeyCheck, ValidateUnexpectedKeys)),
        )

        assertThat(partialResolvers).allSatisfy {
            val result = pattern.matches(JSONObjectValue(mapOf("prop" to StringValue("value"))), it)
            assertThat(result).isInstanceOf(Result.Success::class.java)
        }
    }

    @Test
    fun `should prioritise non-null pattern generation when its a nullable pattern`() {
        val nullableScalarPatterns = listOf(
            AnyPattern(listOf(NullPattern, StringPattern(), NullPattern), extensions = emptyMap()),
            AnyPattern(listOf(StringPattern(), NullPattern), extensions = emptyMap()),
            AnyPattern(listOf(NullPattern, StringPattern()), extensions = emptyMap())
        )

        assertThat(nullableScalarPatterns).allSatisfy {
            val generatedValue = it.generate(Resolver())
            assertThat(generatedValue).isInstanceOf(StringValue::class.java)
        }
    }

    @Test
    fun `should be able to determine if pattern is scalar based correctly`() {
        val scalarBasedPatterns = listOf(
            AnyPattern(listOf(StringPattern(), NullPattern), extensions = emptyMap()),
            AnyPattern(listOf(NullPattern, StringPattern()), extensions = emptyMap())
        )

        assertThat(scalarBasedPatterns).allSatisfy {
            assertThat(it.isNullableScalarPattern()).isTrue()
        }
    }

    @Nested
    inner class GenerateForEveryDiscriminatorDetailsValueTests {
        @Test
        fun `should generate discriminator based values for every discriminator`() {
            val savingsAccountPattern = JSONObjectPattern(
                pattern = mapOf(
                    "@type" to ExactValuePattern(StringValue("savings"), discriminator = true),
                    "accountId" to StringPattern(),
                    "accountHolderName" to StringPattern(),
                    "balance" to NumberPattern(),
                    "minimumBalance" to NumberPattern()
                )
            )

            val currentAccountPattern = JSONObjectPattern(
                pattern = mapOf(
                    "@type" to ExactValuePattern(StringValue("current"), discriminator = true),
                    "accountId" to StringPattern(),
                    "accountHolderName" to StringPattern(),
                    "balance" to NumberPattern(),
                    "overdraftLimit" to NumberPattern()
                )
            )

            val pattern = AnyPattern(
                pattern = listOf(
                    savingsAccountPattern, currentAccountPattern
                ),
                discriminatorProperty = "@type",
                discriminatorValues =  setOf("current", "savings")
            )

            val discriminatorToValues = pattern.generateForEveryDiscriminatorValue(Resolver())

            val commonKeys = setOf("@type", "accountId", "accountHolderName", "balance")
            val currentAccount = discriminatorToValues.first { it.discriminatorValue == "current" }.value as JSONObjectValue
            val savingsAccount = discriminatorToValues.first { it.discriminatorValue == "savings" }.value as JSONObjectValue

            assertThat(currentAccount.jsonObject["@type"]?.toStringLiteral()).isEqualTo("current")
            assertThat(currentAccount.jsonObject.keys).isEqualTo(commonKeys.plus("overdraftLimit"))
            assertThat(savingsAccount.jsonObject["@type"]?.toStringLiteral()).isEqualTo("savings")
            assertThat(savingsAccount.jsonObject.keys).isEqualTo(commonKeys.plus("minimumBalance"))
        }

        @Test
        fun `should generate scalar value for scalar-based pattern`() {
            val pattern = AnyPattern(
                pattern = listOf(StringPattern()),
                discriminatorProperty = "@type",
                discriminatorValues = setOf("current", "savings")
            )

            val values = pattern.generateForEveryDiscriminatorValue(Resolver())
            assertThat(values).isNotEmpty
            assertThat(values.map { it.discriminatorValue }).containsExactlyInAnyOrder("current", "savings")
            assertThat(values.map { it.value }.first()).isInstanceOf(Value::class.java)
            assertThat(values.map { it.value }.last()).isInstanceOf(Value::class.java)
        }

        @Test
        fun `should randomly select a pattern when discriminator does not match any`() {
            val fallbackPattern = JSONObjectPattern(
                pattern = mapOf(
                    "fallbackField" to StringPattern()
                )
            )

            val pattern = AnyPattern(
                pattern = listOf(fallbackPattern),
                discriminatorProperty = "@type",
                discriminatorValues = setOf("nonexistent")
            )

            val values = (pattern.generateForEveryDiscriminatorValue(Resolver()).single().value) as JSONObjectValue

            assertThat(values.jsonObject.size).isEqualTo(1)
            assertThat(values.jsonObject.keys.single()).isEqualTo("fallbackField")
        }

        // TODO - discuss with Joel
        @Test
        fun `should return empty map when no discriminator values provided`() {
            val pattern = AnyPattern(
                pattern = listOf(),
                discriminatorProperty = "@type",
                discriminatorValues = emptySet()
            )

            val values = pattern.generateForEveryDiscriminatorValue(Resolver())
            assertThat(values).isEmpty()
        }

        @Test
        fun `should handle null patterns gracefully`() {
            val pattern = AnyPattern(
                pattern = listOf(NullPattern),
                discriminatorProperty = "@type",
                discriminatorValues = setOf("current", "savings")
            )

            val values = pattern.generateForEveryDiscriminatorValue(Resolver())
            assertThat(values).isNotEmpty
        }
    }

    @Nested
    inner class AllPatternsMandatory {
        @Test
        fun `should call out when optional parameters are missing`() {
            val pattern = AnyPattern(
                listOf(
                    JSONObjectPattern(mapOf("type" to ExactValuePattern(StringValue("sub1")), "address?" to StringPattern()), typeAlias = "(Sub1)"),
                    JSONObjectPattern(mapOf("type" to ExactValuePattern(StringValue("sub2")), "age?" to NumberPattern()), typeAlias = "(Sub2)")
                ), typeAlias = "(Base)",
                discriminator = Discriminator(
                    property = "type",
                    values = setOf("sub1", "sub2"),
                    mapping = mapOf("sub1" to "#/components/schemas/Sub1", "sub2" to "#/components/schemas/Sub2")
                )
            )

            val invalidSub1Value = JSONObjectValue(mapOf("type" to StringValue("sub1")))
            val resultSub1 = pattern.matches(invalidSub1Value, Resolver().withAllPatternsAsMandatory())

            val invalidSub2Value = JSONObjectValue(mapOf("type" to StringValue("sub2")))
            val resultSub2 = pattern.matches(invalidSub2Value, Resolver().withAllPatternsAsMandatory())

            println(resultSub1.reportString())
            assertThat(resultSub1.reportString()).isEqualToNormalizingWhitespace("""
            >> address
            Expected optional key named "address" was missing
            """.trimIndent())

            println(resultSub2.reportString())
            assertThat(resultSub2.reportString()).isEqualToNormalizingWhitespace("""
            >> age
            Expected optional key named "age" was missing
            """.trimIndent())
        }

        @Test
        fun `should not complain about missing keys when pattern is cycling`() {
            val pattern = AnyPattern(
                listOf(
                    JSONObjectPattern(mapOf(
                        "type" to ExactValuePattern(StringValue("sub1")),
                        "details?" to DeferredPattern("(Base)")
                    ), typeAlias = "(Sub1)"),
                    JSONObjectPattern(mapOf(
                        "type" to ExactValuePattern(StringValue("sub2")),
                        "details?" to DeferredPattern("(Base)")
                    ), typeAlias = "(Sub2)"),
                ), typeAlias = "(Base)",
                discriminator = Discriminator(
                    property = "type",
                    values = setOf("sub1", "sub2"),
                    mapping = mapOf("sub1" to "#/components/schemas/Sub1", "sub2" to "#/components/schemas/Sub2")
                )
            )

            val validValue = JSONObjectValue(mapOf(
                "type" to StringValue("sub1"),
                "details" to JSONObjectValue(mapOf("type" to StringValue("sub2")))
            ))
            val result = pattern.matches(validValue, Resolver(
                newPatterns = mapOf("(Base)" to pattern)
            ).withAllPatternsAsMandatory())

            assertThat(result).withFailMessage(result.reportString()).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `should complain the first time when optional keys are missing even if pattern is cycling`() {
            val pattern = AnyPattern(
                listOf(
                    JSONObjectPattern(mapOf(
                        "type" to ExactValuePattern(StringValue("sub1")),
                        "details?" to DeferredPattern("(Base)")
                    ), typeAlias = "(Sub1)"),
                    JSONObjectPattern(mapOf(
                        "type" to ExactValuePattern(StringValue("sub2")),
                        "details?" to DeferredPattern("(Base)")
                    ), typeAlias = "(Sub2)"),
                ), typeAlias = "(Base)",
                discriminator = Discriminator(
                    property = "type",
                    values = setOf("sub1", "sub2"),
                    mapping = mapOf("sub1" to "#/components/schemas/Sub1", "sub2" to "#/components/schemas/Sub2")
                )
            )

            val validValue = JSONObjectValue(mapOf("type" to StringValue("sub1")))
            val result = pattern.matches(validValue, Resolver(
                newPatterns = mapOf("(Base)" to pattern)
            ).withAllPatternsAsMandatory())

            print(result.reportString())
            assertThat(result).isInstanceOf(Result.Failure::class.java)
            assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
            >> details
            Expected optional key named "details" was missing
            """.trimIndent())
        }

        @Test
        fun `should not add itself as seen as the discriminator mappings can ref back to itself`() {
            val pattern = AnyPattern(
                listOf(
                    JSONObjectPattern(mapOf("type" to ExactValuePattern(StringValue("sub")), "address?" to StringPattern()), typeAlias = "(Sub)"),
                ), typeAlias = "(Sub)",
                discriminator = Discriminator(
                    property = "type",
                    values = setOf("sub"),
                    mapping = mapOf("sub" to "#/components/schemas/Sub")
                ),
            )
            val invalidValue = JSONObjectValue(mapOf("type" to StringValue("sub")))
            val result = pattern.matches(invalidValue, Resolver().withAllPatternsAsMandatory())

            println(result.reportString())
            assertThat(result).isInstanceOf(Result.Failure::class.java)
            assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
            >> address
            Expected optional key named "address" was missing
            """.trimIndent())
        }
    }

    @Nested
    inner class FixValueTests {
        @Test
        fun `should pick the correct discriminator pattern when fixing an invalid value`() {
            val pattern = AnyPattern(
                listOf(
                    JSONObjectPattern(mapOf("type" to ExactValuePattern(StringValue("sub1")), "prop" to StringPattern()), typeAlias = "(Sub1)"),
                    JSONObjectPattern(mapOf("type" to ExactValuePattern(StringValue("sub2")), "prop" to NumberPattern()), typeAlias = "(Sub2)")
                ), typeAlias = "(Base)",
                discriminator = Discriminator(
                    property = "type",
                    values = setOf("sub1", "sub2"),
                    mapping = mapOf("sub1" to "#/components/schemas/Sub1", "sub2" to "#/components/schemas/Sub2")
                )
            )

            val dictionary = "{ Sub1: { prop: TODO }, Sub2: { prop: 999 } }".let(Dictionary::fromYaml)
            val invalidSub1Value = JSONObjectValue(mapOf("type" to StringValue("sub1")))
            val validSub1Value = pattern.fixValue(invalidSub1Value, Resolver(dictionary = dictionary))
            println(validSub1Value.toStringLiteral())

            assertThat(validSub1Value).isEqualTo(
                JSONObjectValue(mapOf(
                    "type" to StringValue("sub1"),
                    "prop" to StringValue("TODO")
                ))
            )
        }

        @Test
        fun `should pick the pattern with least failures on wrong or invalid discriminator value`() {
            val pattern = AnyPattern(
                listOf(
                    JSONObjectPattern(mapOf("type" to ExactValuePattern(StringValue("sub1")), "prop" to StringPattern(), "extra" to StringPattern()), typeAlias = "(Sub1)"),
                    JSONObjectPattern(mapOf("type" to ExactValuePattern(StringValue("sub2")), "prop" to NumberPattern()), typeAlias = "(Sub2)")
                ), typeAlias = "(Base)",
                discriminator = Discriminator(
                    property = "type",
                    values = setOf("sub1", "sub2"),
                    mapping = mapOf("sub1" to "#/components/schemas/Sub1", "sub2" to "#/components/schemas/Sub2")
                )
            )

            val dictionary = "{ Sub1: { prop: TODO }, Sub2: { prop: 999 } }".let(Dictionary::fromYaml)
            val invalidSub1Value = JSONObjectValue(mapOf("type" to StringValue("notExists")))
            val validSub1Value = pattern.fixValue(invalidSub1Value, Resolver(dictionary = dictionary))
            println(validSub1Value.toStringLiteral())

            assertThat(validSub1Value).isEqualTo(
                JSONObjectValue(mapOf(
                    "type" to StringValue("sub2"),
                    "prop" to NumberValue(999)
                ))
            )
        }

        @Test
        fun `should pick the pattern with least failures when discriminator key is missing from value`() {
            val pattern = AnyPattern(
                listOf(
                    JSONObjectPattern(mapOf("type" to ExactValuePattern(StringValue("sub1")), "prop" to StringPattern(), "extra" to StringPattern()), typeAlias = "(Sub1)"),
                    JSONObjectPattern(mapOf("type" to ExactValuePattern(StringValue("sub2")), "prop" to NumberPattern()), typeAlias = "(Sub2)")
                ), typeAlias = "(Base)",
                discriminator = Discriminator(
                    property = "type",
                    values = setOf("sub1", "sub2"),
                    mapping = mapOf("sub1" to "#/components/schemas/Sub1", "sub2" to "#/components/schemas/Sub2")
                )
            )

            val dictionary = "{ Sub1: { prop: TODO }, Sub2: { prop: 999 } }".let(Dictionary::fromYaml)
            val invalidSub1Value = JSONObjectValue(emptyMap())
            val validSub1Value = pattern.fixValue(invalidSub1Value, Resolver(dictionary = dictionary))
            println(validSub1Value.toStringLiteral())

            assertThat(validSub1Value).isEqualTo(
                JSONObjectValue(mapOf(
                    "type" to StringValue("sub2"),
                    "prop" to NumberValue(999)
                ))
            )
        }

        @Test
        fun `should pick the pattern with least failures when no discriminator is present in the pattern`() {
            val pattern = AnyPattern(
                listOf(
                    JSONObjectPattern(mapOf("type" to ExactValuePattern(StringValue("sub1")), "prop" to StringPattern(), "extra" to StringPattern()), typeAlias = "(Sub1)"),
                    JSONObjectPattern(mapOf("type" to ExactValuePattern(StringValue("sub2")), "prop" to NumberPattern()), typeAlias = "(Sub2)")
                ), typeAlias = "(Base)",
                discriminator = null
            )

            val dictionary = "{ Sub1: { prop: TODO }, Sub2: { prop: 999 } }".let(Dictionary::fromYaml)
            val invalidSub1Value = JSONObjectValue(emptyMap())
            val validSub1Value = pattern.fixValue(invalidSub1Value, Resolver(dictionary = dictionary))
            println(validSub1Value.toStringLiteral())

            assertThat(validSub1Value).isEqualTo(
                JSONObjectValue(mapOf(
                    "type" to StringValue("sub2"),
                    "prop" to NumberValue(999)
                ))
            )
        }

        @Test
        fun `should retain pattern token if it matches when resolver is in mock mode`() {
            val objectPattern = JSONObjectPattern(mapOf("type" to "object".toDiscriminator(), "number" to NumberPattern(), "string" to StringPattern()), typeAlias = "(Object)")
            val pattern = AnyPattern(
                pattern = listOf(objectPattern), typeAlias = "(AnyPattern)",
                discriminatorValues = setOf("object"), discriminatorProperty = "type"
            )
            val resolver = Resolver(newPatterns = mapOf("(Object)" to objectPattern, "(AnyPattern)" to pattern), mockMode = true)
            val validValues = listOf(StringValue("(Object)"), StringValue("(AnyPattern)"))

            assertThat(validValues).allSatisfy {
                val fixedValue = pattern.fixValue(it, resolver)
                println(fixedValue.toStringLiteral())
                assertThat(fixedValue).isEqualTo(it)
            }
        }

        @Test
        fun `should generate value when pattern token does not match when resolver is in mock mode`() {
            val objectPattern = JSONObjectPattern(mapOf("type" to "object".toDiscriminator(), "number" to NumberPattern(), "string" to StringPattern()), typeAlias = "(Object)")
            val pattern = AnyPattern(
                pattern = listOf(objectPattern), typeAlias = "(AnyPattern)",
                discriminatorValues = setOf("object"), discriminatorProperty = "type"
            )
            val resolver = Resolver(newPatterns = mapOf("(Object)" to objectPattern, "(AnyPattern)" to pattern), mockMode = true)
            val invalidValues = listOf(StringValue("(string)"), StringValue("(number)"))

            assertThat(invalidValues).allSatisfy {
                val fixedValue = pattern.fixValue(it, resolver)
                println(fixedValue.toStringLiteral())
                assertThat(fixedValue).isNotEqualTo(it).isInstanceOf(JSONObjectValue::class.java)
            }
        }

        @Test
        fun `should generate values even if pattern token matches but resolver is not in mock mode`() {
            val objectPattern = JSONObjectPattern(mapOf("type" to "object".toDiscriminator(), "number" to NumberPattern(), "string" to StringPattern()), typeAlias = "(Object)")
            val pattern = AnyPattern(
                pattern = listOf(objectPattern), typeAlias = "(AnyPattern)",
                discriminatorValues = setOf("object"), discriminatorProperty = "type"
            )
            val resolver = Resolver(newPatterns = mapOf("(Object)" to objectPattern, "(AnyPattern)" to pattern), mockMode = false)
            val values = listOf(StringValue("(Object)"), StringValue("(AnyPattern)"))

            assertThat(values).allSatisfy {
                val fixedValue = pattern.fixValue(it, resolver)
                println(fixedValue.toStringLiteral())
                assertThat(fixedValue).isNotEqualTo(it).isInstanceOf(JSONObjectValue::class.java)
            }
        }

        @Test
        fun `should work when pattern is scalar based of nullable type`() {
            val pattern = AnyPattern(listOf(NullPattern, StringPattern()), extensions = emptyMap())
            val dictionary = "(string): TODO".let(Dictionary::fromYaml)
            val resolver = Resolver(dictionary = dictionary)
            val invalidValue = NumberValue(999)
            val fixedValue = pattern.fixValue(invalidValue, resolver)

            assertThat(fixedValue).isInstanceOf(StringValue::class.java); fixedValue as StringValue
            assertThat(fixedValue.string).isEqualTo("TODO")
        }

        @Test
        fun `scalar value should be picked from dictionary when pattern has typeAlias and matching key in dictionary`() {
            val pattern = AnyPattern(listOf(NullPattern, StringPattern()), typeAlias = "(StringOrEmpty)", extensions = emptyMap())
            val dictionary = "StringOrEmpty: TODO".let(Dictionary::fromYaml)
            val resolver = Resolver(dictionary = dictionary)
            val invalidValue = NumberValue(999)
            val fixedValue = pattern.fixValue(invalidValue, resolver)

            assertThat(fixedValue).isInstanceOf(StringValue::class.java); fixedValue as StringValue
            assertThat(fixedValue.string).isEqualTo("TODO")
        }

        @Test
        fun `nullable pattern dictionary lookup should not throw an exception`() {
            val pattern = AnyPattern(listOf(NullPattern, NumberPattern()), extensions = emptyMap())
            val jsonObjPattern = JSONObjectPattern(mapOf("id" to pattern), typeAlias = "(Test)")

            val dictionary = "Test: { id: 999 }".let(Dictionary::fromYaml)
            val resolver = Resolver(dictionary = dictionary)

            val invalidValue = JSONObjectValue(mapOf("id" to StringValue("INVALID")))
            val fixedValue = assertDoesNotThrow { jsonObjPattern.fixValue(invalidValue, resolver) }

            assertThat(fixedValue).isInstanceOf(JSONObjectValue::class.java); fixedValue as JSONObjectValue
            assertThat(fixedValue.jsonObject).isEqualTo(mapOf("id" to NumberValue(999)))
        }

        @Test
        fun `should be able to fix invalid values with a partial resolver`() {
            val pattern = AnyPattern(
                listOf(
                    JSONObjectPattern(mapOf("type" to "sub1".toDiscriminator(), "prop" to StringPattern(), "extra" to NumberPattern()), typeAlias = "(Sub1)"),
                    JSONObjectPattern(mapOf("type" to "sub2".toDiscriminator(), "prop" to NumberPattern()), typeAlias = "(Sub2)")
                ), typeAlias = "(Base)",
                discriminator = Discriminator(
                    property = "type",
                    values = setOf("sub1", "sub2"),
                    mapping = mapOf("sub1" to "#/components/schemas/Sub1", "sub2" to "#/components/schemas/Sub2")
                )
            )
            val dictionary = "(number): 999".let(Dictionary::fromYaml)
            val resolver = Resolver(dictionary = dictionary).partializeKeyCheck()
            val partialValue = JSONObjectValue(mapOf("extra" to StringValue("(string)")))
            val fixedValue = pattern.fixValue(partialValue, resolver)

            assertThat(fixedValue).isEqualTo(JSONObjectValue(mapOf("extra" to NumberValue(999))))
        }
    }

    @Nested
    inner class FillInTheBlanksTests {
        @Test
        fun `should be able to fill in the blanks using correct pattern when discriminator is present`() {
            val pattern = AnyPattern(
                listOf(
                    JSONObjectPattern(mapOf("type" to "sub1".toDiscriminator(), "prop" to StringPattern(), "extra" to StringPattern()), typeAlias = "(Sub1)"),
                    JSONObjectPattern(mapOf("type" to "sub2".toDiscriminator(), "prop" to NumberPattern()), typeAlias = "(Sub2)")
                ), typeAlias = "(Base)",
                discriminator = Discriminator(
                    property = "type",
                    values = setOf("sub1", "sub2"),
                    mapping = mapOf("sub1" to "#/components/schemas/Sub1", "sub2" to "#/components/schemas/Sub2")
                )
            )
            val dictionary = "Sub2: { prop: 999 }".let(Dictionary::fromYaml)
            val resolver = Resolver(dictionary = dictionary)
            val partialValue = JSONObjectValue(mapOf("type" to StringValue("sub2")))
            val filledInValue = pattern.fillInTheBlanks(partialValue, resolver).value

            assertThat(filledInValue).isEqualTo(JSONObjectValue(mapOf("type" to StringValue("sub2"), "prop" to NumberValue(999))))
        }

        @Test
        fun `should be able to fill in the blanks when there is only pattern token`() {
            val patternSub1 = JSONObjectPattern(mapOf("type" to "sub1".toDiscriminator(), "prop" to StringPattern(), "extra" to StringPattern()), typeAlias = "(Sub1)")
            val patternSub2 = JSONObjectPattern(mapOf("type" to "sub2".toDiscriminator(), "prop" to NumberPattern()), typeAlias = "(Sub2)")
            val pattern = AnyPattern(
                listOf(patternSub1, patternSub2), typeAlias = "(Base)",
                discriminator = Discriminator(
                    property = "type",
                    values = setOf("sub1", "sub2"),
                    mapping = mapOf("sub1" to "#/components/schemas/Sub1", "sub2" to "#/components/schemas/Sub2")
                )
            )

            val dictionary = "Sub2: { prop: 999 }".let(Dictionary::fromYaml)
            val resolver = Resolver(dictionary = dictionary, newPatterns = mapOf("(Sub1)" to patternSub1, "(Sub2)" to patternSub2))
            val partialValue = StringValue("(Sub2)")
            val filledInValue = pattern.fillInTheBlanks(partialValue, resolver).value

            assertThat(filledInValue).isEqualTo(JSONObjectValue(mapOf("type" to StringValue("sub2"), "prop" to NumberValue(999))))
        }

        @Test
        fun `should be able to fill in when discriminator is missing from the partial value`() {
            val pattern = AnyPattern(
                listOf(
                    JSONObjectPattern(mapOf("type" to "sub1".toDiscriminator(), "prop" to StringPattern(), "extra" to StringPattern()), typeAlias = "(Sub1)"),
                    JSONObjectPattern(mapOf("type" to "sub2".toDiscriminator(), "prop" to NumberPattern()), typeAlias = "(Sub2)")
                ), typeAlias = "(Base)",
                discriminator = Discriminator(
                    property = "type",
                    values = setOf("sub1", "sub2"),
                    mapping = mapOf("sub1" to "#/components/schemas/Sub1", "sub2" to "#/components/schemas/Sub2")
                )
            )

            val dictionary = "Sub2: { prop: 999 }".let(Dictionary::fromYaml)
            val resolver = Resolver(dictionary = dictionary)
            val partialValue = JSONObjectValue(mapOf("prop" to StringValue("(number)")))
            val filledInValue = pattern.fillInTheBlanks(partialValue, resolver).value

            assertThat(filledInValue).isEqualTo(JSONObjectValue(mapOf("type" to StringValue("sub2"), "prop" to NumberValue(999))))
        }

        @Test
        fun `should be able to fill in when pattern token refers to self`() {
            val pattern = AnyPattern(
                listOf(
                    JSONObjectPattern(mapOf("type" to "sub1".toDiscriminator(), "prop" to StringPattern(), "extra" to StringPattern()), typeAlias = "(Sub1)"),
                    JSONObjectPattern(mapOf("type" to "sub2".toDiscriminator(), "prop" to NumberPattern()), typeAlias = "(Sub2)")
                ), typeAlias = "(Base)",
                discriminator = Discriminator(
                    property = "type",
                    values = setOf("sub1", "sub2"),
                    mapping = mapOf("sub1" to "#/components/schemas/Sub1", "sub2" to "#/components/schemas/Sub2")
                )
            )

            val dictionary = "Sub1: { prop: TODO, extra: TODO }".let(Dictionary::fromYaml)
            val resolver = Resolver(newPatterns = mapOf("(Base)" to pattern), dictionary = dictionary)
            val partialValue = StringValue("(Base)")
            val filledInValue = pattern.fillInTheBlanks(partialValue, resolver).value

            assertThat(filledInValue).isEqualTo(JSONObjectValue(mapOf(
                "type" to StringValue("sub1"), "prop" to StringValue("TODO"), "extra" to StringValue("TODO")
            )))
        }

        @Test
        fun `should work for negative test for invalid values or pattern tokens`() {
            val pattern = AnyPattern(
                listOf(
                    JSONObjectPattern(mapOf("type" to "sub1".toDiscriminator(), "prop" to StringPattern(), "extra" to StringPattern()), typeAlias = "(Sub1)"),
                    JSONObjectPattern(mapOf("type" to "sub2".toDiscriminator(), "prop" to NumberPattern()), typeAlias = "(Sub2)")
                ), typeAlias = "(Base)",
                discriminator = Discriminator(
                    property = "type",
                    values = setOf("sub1", "sub2"),
                    mapping = mapOf("sub1" to "#/components/schemas/Sub1", "sub2" to "#/components/schemas/Sub2")
                )
            )

            val dictionary = "(number): 999".let(Dictionary::fromYaml)
            val resolver = Resolver(newPatterns = mapOf("(Base)" to pattern), isNegative = true, dictionary = dictionary)
            val partialValue = JSONObjectValue(mapOf(
                "type" to StringValue("sub1"), "prop" to BooleanValue(false), "extra" to StringValue("(number)")
            ))
            val filledInValue = pattern.fillInTheBlanks(partialValue, resolver).value

            assertThat(filledInValue).isEqualTo(JSONObjectValue(mapOf(
                "type" to StringValue("sub1"), "prop" to BooleanValue(false), "extra" to NumberValue(999)
            )))
        }

        @Test
        fun `should work for negative test when partial is a pattern token`() {
            val pattern = AnyPattern(
                listOf(
                    JSONObjectPattern(mapOf("type" to "sub1".toDiscriminator(), "prop" to StringPattern(), "extra" to StringPattern()), typeAlias = "(Sub1)"),
                    JSONObjectPattern(mapOf("type" to "sub2".toDiscriminator(), "prop" to NumberPattern()), typeAlias = "(Sub2)")
                ), typeAlias = "(Base)",
                discriminator = Discriminator(
                    property = "type",
                    values = setOf("sub1", "sub2"),
                    mapping = mapOf("sub1" to "#/components/schemas/Sub1", "sub2" to "#/components/schemas/Sub2")
                )
            )
            val resolver = Resolver(newPatterns = mapOf("(Base)" to pattern), isNegative = true)
            val partialValue = StringValue("(boolean)")
            val filledInValue = pattern.fillInTheBlanks(partialValue, resolver).value

            assertThat(filledInValue).isInstanceOf(BooleanValue::class.java)
        }
    }

    @Nested
    inner class CalculatePathTests {
        @Test
        fun `calculatePath should return empty set when pattern list is empty`() {
            val pattern = AnyPattern(emptyList(), extensions = emptyMap())
            val value = StringValue("test")
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).isEmpty()
        }

        @Test
        fun `calculatePath should return empty set when no patterns match`() {
            val pattern = AnyPattern(listOf(NumberPattern()), extensions = emptyMap())
            val value = StringValue("test")
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).isEmpty()
        }

        @Test
        fun `calculatePath should return scalar type name for matching scalar pattern`() {
            val pattern = AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap())
            val value = StringValue("test")
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).containsExactly("string")
        }

        @Test
        fun `calculatePath should return number type name for matching number pattern`() {
            val pattern = AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap())
            val value = NumberValue(42)
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).containsExactly("number")
        }

        @Test
        fun `calculatePath should return boolean type name for matching boolean pattern`() {
            val pattern = AnyPattern(listOf(StringPattern(), BooleanPattern()), extensions = emptyMap())
            val value = BooleanValue(true)
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).containsExactly("boolean")
        }

        @Test
        fun `calculatePath should extract typeAlias from DeferredPattern`() {
            val pattern = AnyPattern(listOf(DeferredPattern("(TestType)")), extensions = emptyMap())
            val deferredResolver = Resolver(newPatterns = mapOf("(TestType)" to StringPattern()))
            val value = StringValue("test")

            val paths = pattern.calculatePath(value, deferredResolver)

            assertThat(paths).containsExactly("{TestType}")
        }

        @Test
        fun `calculatePath should use typeAlias from pattern when available`() {
            val patternWithAlias = StringPattern(typeAlias = "(CustomString)")
            val pattern = AnyPattern(listOf(patternWithAlias), extensions = emptyMap())
            val value = StringValue("test")
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).containsExactly("{CustomString}")
        }

        @Test
        fun `calculatePath should recurse into JSONObjectPattern with nested AnyPatterns`() {
            val nestedPattern = JSONObjectPattern(
                pattern = mapOf("nested" to AnyPattern(listOf(StringPattern()), extensions = emptyMap())),
                typeAlias = "(NestedObject)"
            )
            val pattern = AnyPattern(listOf(nestedPattern), extensions = emptyMap())
            val value = JSONObjectValue(mapOf("nested" to StringValue("test")))
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).containsExactly("{NestedObject}.nested{string}")
        }

        @Test
        fun `calculatePath should return typeAlias for JSONObjectPattern without nested AnyPatterns`() {
            val nestedPattern = JSONObjectPattern(
                pattern = mapOf("field" to StringPattern()),
                typeAlias = "(SimpleObject)"
            )
            val pattern = AnyPattern(listOf(nestedPattern), extensions = emptyMap())
            val value = JSONObjectValue(mapOf("field" to StringValue("test")))
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).containsExactly("{SimpleObject}")
        }

        @Test
        fun `calculatePath should return index notation for patterns without typeAlias`() {
            val patternWithoutAlias = JSONObjectPattern(pattern = mapOf("field" to StringPattern()))
            val pattern = AnyPattern(listOf(StringPattern(), patternWithoutAlias), extensions = emptyMap())
            val value = JSONObjectValue(mapOf("field" to StringValue("test")))
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).containsExactly("{[1]}")
        }

        @Test
        fun `calculatePath should handle pattern with discriminator`() {
            val discriminator = Discriminator(
                property = "type",
                values = setOf("string_type"),
                mapping = mapOf("string_type" to "#/components/schemas/StringType")
            )
            val pattern = AnyPattern(
                listOf(
                    JSONObjectPattern(
                        mapOf("type" to ExactValuePattern(StringValue("string_type")), "value" to StringPattern()),
                        typeAlias = "(StringType)"
                    )
                ),
                discriminator = discriminator
            )
            val value = JSONObjectValue(mapOf(
                "type" to StringValue("string_type"),
                "value" to StringValue("test")
            ))
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).containsExactly("{StringType}")
        }
    }

    private fun String.toDiscriminator(): ExactValuePattern {
        return ExactValuePattern(StringValue(this))
    }
}
