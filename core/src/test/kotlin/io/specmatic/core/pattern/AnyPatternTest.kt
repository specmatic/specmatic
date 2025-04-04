package io.specmatic.core.pattern

import io.specmatic.*
import io.specmatic.core.PARTIAL_KEYCHECK
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.utilities.withNullPattern
import io.specmatic.core.value.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

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
        val pattern = AnyPattern(listOf(NumberPattern(), StringPattern()))
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
        val pattern = AnyPattern(listOf(objectPattern, listPattern))

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
        val pattern1 = AnyPattern(listOf(NullPattern, StringPattern()))
        val pattern2 = AnyPattern(listOf(DeferredPattern("(empty)"), StringPattern()))

        val value = parsedValue("""{"firstname": "Jane", "lastname": "Doe"}""")

        val resolver = withNullPattern(Resolver())

        val result1 = pattern1.matches(value, resolver)
        val result2 = pattern2.matches(value, resolver)

        assertThat(result2.toReport().toText().trimIndent()).isEqualTo("""Expected string, actual was JSON object {
       "firstname": "Jane",
       "lastname": "Doe"
   }""")

        assertThat(result1.toReport().toText().trimIndent()).isEqualTo("""Expected string, actual was JSON object {
       "firstname": "Jane",
       "lastname": "Doe"
   }""")
    }

    @Test
    fun `should return error message when object type matches but there is another mismatch and no ambiguous pattern in AnyPattern list`() {
        val jsonPattern = JSONObjectPattern(
            mapOf(
                "firstname" to StringPattern(),
                "lastname" to NumberPattern()
            )
        )
        val pattern = AnyPattern(listOf(NullPattern, DeferredPattern(pattern = "(Person)")))
        val value = parsedValue("""{"firstname": "Jane", "lastname": "Doe"}""")
        val resolver = withNullPattern(Resolver(
            newPatterns = mapOf("(Person)" to jsonPattern)
        ))

        val result = pattern.matches(value, resolver)

        assertThat(result.toReport().toText().trimIndent()).doesNotContain("when Person object")
    }

    @Test
    fun `typename of a nullable type`() {
        val pattern1 = AnyPattern(listOf(NullPattern, StringPattern()))
        val pattern2 = AnyPattern(listOf(DeferredPattern("(empty)"), StringPattern()))

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
            )
        ).newBasedOn(Row(), Resolver()).map { it.value }.toList().let { patterns ->
            patterns.map { it.typeName } shouldContainInAnyOrder listOf("number", "\"one\"", "\"two\"")
        }
    }

    @Test
    @Tag(GENERATION)
    fun `should create a new pattern based on the given row`() {
        val pattern = AnyPattern(listOf(parsedPattern("""{"id": "(number)"}""")))
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
        val pattern = AnyPattern(listOf(parsedPattern("""{"id": "(number)"}""")))
        val value = pattern.newBasedOn(Row(), Resolver()).map { it.value }.first().generate(Resolver())

        value as JSONObjectValue

        val id = value.jsonObject.getValue("id")

        assertThat(id).isInstanceOf(NumberValue::class.java)
    }

    @Test
    fun `should generate a value based on the pattern given`() {
        NumberValue(10) shouldMatch AnyPattern(listOf(parsedPattern("(number)")))
    }

    @Test
    fun `AnyPattern of null and string patterns should encompass null pattern`() {
        assertThat(AnyPattern(listOf(NullPattern, StringPattern())).encompasses(NullPattern, Resolver(), Resolver())).isInstanceOf(
            Result.Success::class.java)
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
        val anyOfString = AnyPattern(listOf(StringPattern()))

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
        val type = AnyPattern(listOf(NullPattern, NumberPattern()))
        assertThat(type.typeName).isEqualTo("(number?)")
    }

    private fun toEnum(items: List<Value>): AnyPattern {
        return AnyPattern(items.map { ExactValuePattern(it) })
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
                )
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
        val type = AnyPattern(listOf(NullPattern, StringPattern()))
        val parsedValue = type.parse("22B Baker Street", Resolver(isNegative = true))
        assertThat(parsedValue.toStringLiteral()).isEqualTo("22B Baker Street")
    }

    @Test
    @Tag(GENERATION)
    fun `values for negative tests`() {
        val negativeTypes =
            AnyPattern(listOf(NullPattern, StringPattern())).negativeBasedOn(Row(), Resolver()).map { it.value }
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
        val oneOfCustomerOrEmployeeType = AnyPattern(listOf(customerType, employeeType))

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
        val type = AnyPattern(listOf(NullPattern, StringPattern()))
        val wrappedList = type.listOf(listOf(StringValue("It's me"), StringValue("Hi"), StringValue("I'm the problem it's me")), Resolver()) as JSONArrayValue

        val wrappedValues = wrappedList.list.map { it.toStringLiteral() }
        val expectedValues = listOf("It's me", "Hi", "I'm the problem it's me")

        assertThat(wrappedValues).isEqualTo(expectedValues)
    }

    @Test
    fun `should wrap values in the relevant list type when the AnyPattern object represents an enum with 3 options`() {
        val type = AnyPattern(listOf(
            ExactValuePattern(StringValue("one")), ExactValuePattern(StringValue("two")), ExactValuePattern(StringValue("three"))))
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
        val result = pattern.matches(invalidValue, Resolver(findKeyErrorCheck = PARTIAL_KEYCHECK))

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

            val dictionary = mapOf("Sub1.prop" to StringValue("TODO"), "Sub2.prop" to NumberValue(999))
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

            val dictionary = mapOf("Sub1.prop" to StringValue("TODO"), "Sub2.prop" to NumberValue(999))
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

            val dictionary = mapOf("Sub1.prop" to StringValue("TODO"), "Sub2.prop" to NumberValue(999))
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

            val dictionary = mapOf("Sub1.prop" to StringValue("TODO"), "Sub2.prop" to NumberValue(999))
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
            val dictionary = mapOf("Sub2.prop" to NumberValue(999))
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

            val dictionary = mapOf("Sub2.prop" to NumberValue(999))
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

            val dictionary = mapOf("Sub2.prop" to NumberValue(999))
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

            val dictionary = mapOf("Sub1.prop" to StringValue("TODO"), "Sub1.extra" to StringValue("TODO"),)
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

            val resolver = Resolver(newPatterns = mapOf("(Base)" to pattern), isNegative = true, dictionary = mapOf("(number)" to NumberValue(999)))
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

    private fun String.toDiscriminator(): ExactValuePattern {
        return ExactValuePattern(StringValue(this))
    }
}
