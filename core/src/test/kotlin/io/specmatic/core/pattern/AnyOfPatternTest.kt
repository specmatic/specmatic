package io.specmatic.core.pattern

import io.specmatic.*
import io.specmatic.core.*
import io.specmatic.core.value.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.assertThrows

internal class AnyOfPatternTest {
    @Test
    fun `should match if any of the patterns match`() {
        val pattern = AnyOfPattern(listOf(NumberPattern(), StringPattern()), extensions = emptyMap())
        
        // Should match number
        val numberResult = pattern.matches(NumberValue(42), Resolver())
        assertThat(numberResult).isInstanceOf(Result.Success::class.java)
        
        // Should match string
        val stringResult = pattern.matches(StringValue("hello"), Resolver())
        assertThat(stringResult).isInstanceOf(Result.Success::class.java)
        
        // Should not match boolean
        val booleanResult = pattern.matches(BooleanValue(true), Resolver())
        assertThat(booleanResult).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `should generate value from one of the patterns`() {
        val pattern = AnyOfPattern(listOf(NumberPattern(), StringPattern()), extensions = emptyMap())
        val value = pattern.generate(Resolver())
        
        // Should generate either a string or number
        val matchResult = pattern.matches(value, Resolver())
        assertThat(matchResult).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should handle nullable patterns`() {
        val pattern = AnyOfPattern(listOf(NumberPattern(), NullPattern), extensions = emptyMap())
        
        // Should match number
        val numberResult = pattern.matches(NumberValue(42), Resolver())
        assertThat(numberResult).isInstanceOf(Result.Success::class.java)
        
        // Should match null
        val nullResult = pattern.matches(NullValue, Resolver())
        assertThat(nullResult).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should have correct type name`() {
        val pattern = AnyOfPattern(listOf(NumberPattern(), StringPattern()), extensions = emptyMap())
        assertThat(pattern.typeName).isEqualTo("(anyOf number string)")
    }

    @Test
    fun `should have correct nullable type name`() {
        val pattern = AnyOfPattern(listOf(NumberPattern(), NullPattern), extensions = emptyMap())
        assertThat(pattern.typeName).isEqualTo("(anyOf number \"null\")")
    }

    @Test
    fun `should parse value using any matching pattern`() {
        val pattern = AnyOfPattern(listOf(NumberPattern(), StringPattern()), extensions = emptyMap())
        
        // Should parse number first (before string attempts to parse it)
        val numberValue = pattern.parse("42", Resolver())
        assertThat(numberValue).isInstanceOf(NumberValue::class.java)
        assertThat((numberValue as NumberValue).number).isEqualTo(42)
        
        // Should parse string when number parsing fails
        val stringValue = pattern.parse("hello", Resolver())
        assertThat(stringValue).isInstanceOf(StringValue::class.java)
        assertThat((stringValue as StringValue).string).isEqualTo("hello")
    }

    @Test
    fun `should throw exception when parsing fails for all patterns`() {
        val pattern = AnyOfPattern(listOf(NumberPattern(), BooleanPattern()), extensions = emptyMap())
        
        assertThrows<ContractException> {
            pattern.parse("hello", Resolver())
        }
    }

    @Test
    fun `should encompass compatible patterns`() {
        val biggerPattern = AnyOfPattern(listOf(NumberPattern(), StringPattern()), extensions = emptyMap())
        val smallerPattern = StringPattern()
        
        val result = biggerPattern.encompasses(smallerPattern, Resolver(), Resolver())
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should not encompass incompatible patterns`() {
        val pattern = AnyOfPattern(listOf(NumberPattern(), StringPattern()), extensions = emptyMap())
        val incompatiblePattern = BooleanPattern()
        
        val result = pattern.encompasses(incompatiblePattern, Resolver(), Resolver())
        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `should encompass anyOf with same types in different order`() {
        val pattern1 = AnyOfPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap())
        val pattern2 = AnyOfPattern(listOf(NumberPattern(), StringPattern()), extensions = emptyMap())
        
        val result = pattern1.encompasses(pattern2, Resolver(), Resolver())
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should handle JSON object patterns`() {
        val objectPattern1 = JSONObjectPattern(mapOf("name" to StringPattern()), typeAlias = "(Person)")
        val objectPattern2 = JSONObjectPattern(mapOf("id" to NumberPattern()), typeAlias = "(Id)")
        val pattern = AnyOfPattern(listOf(objectPattern1, objectPattern2), extensions = emptyMap())
        
        val personValue = JSONObjectValue(mapOf("name" to StringValue("John")))
        val idValue = JSONObjectValue(mapOf("id" to NumberValue(123)))
        
        assertThat(pattern.matches(personValue, Resolver())).isInstanceOf(Result.Success::class.java)
        assertThat(pattern.matches(idValue, Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should encompass JSONObjectPattern with compatible structure`() {
        val personPattern = JSONObjectPattern(mapOf("name" to StringPattern(), "age" to NumberPattern()), typeAlias = "(Person)")
        val idPattern = JSONObjectPattern(mapOf("id" to NumberPattern()), typeAlias = "(Id)")
        val anyOfPattern = AnyOfPattern(listOf(personPattern, idPattern), extensions = emptyMap())
        
        val compatiblePattern = JSONObjectPattern(mapOf("name" to StringPattern(), "age" to NumberPattern()), typeAlias = "(SimplePerson)")
        
        val result = anyOfPattern.encompasses(compatiblePattern, Resolver(), Resolver())
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should fix value using best matching pattern`() {
        val pattern = AnyOfPattern(listOf(NumberPattern(), StringPattern()), extensions = emptyMap())
        val value = StringValue("hello")
        
        val fixedValue = pattern.fixValue(value, Resolver())
        assertThat(fixedValue).isEqualTo(value)
    }

    @Test
    fun `should eliminate optional keys correctly`() {
        val objectPattern = parsedPattern("""{
            "mandatoryKey": "(string)",
            "optionalKey?": "(number)"
        }""")
        val pattern = AnyOfPattern(listOf(objectPattern), extensions = emptyMap())
        
        val originalValue = parsedValue("""{
            "mandatoryKey": "hello",
            "optionalKey": 42
        }""")
        
        val expectedValue = parsedValue("""{
            "mandatoryKey": "hello"
        }""")
        
        val result = pattern.eliminateOptionalKey(originalValue, Resolver())
        assertEquals(expectedValue, result)
    }

    @Test
    @Tag(GENERATION)
    fun `should generate new patterns for all available types`() {
        AnyOfPattern(
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
    fun `newBasedOn should work with two JSON object patterns and row example matching one pattern`() {
        val objectPattern1 = JSONObjectPattern(mapOf("name" to StringPattern(), "age" to NumberPattern()))
        val objectPattern2 = JSONObjectPattern(mapOf("id" to NumberPattern(), "type" to StringPattern()))
        val pattern = AnyOfPattern(listOf(objectPattern1, objectPattern2), extensions = emptyMap())
        
        val row = Row(listOf("id", "type"), listOf("42", "admin"))
        
        val newPatterns = pattern.newBasedOn(row, Resolver()).toList()
        assertThat(newPatterns.size).isGreaterThan(0)
        
        // Should find a pattern that can be used with the provided row data
        val matchingPattern = newPatterns.find { 
            val generated = it.value.generate(Resolver())
            generated is JSONObjectValue && 
            generated.jsonObject.containsKey("id") && 
            generated.jsonObject.containsKey("type")
        }
        assertThat(matchingPattern).isNotNull
    }

    @Test
    fun `should handle empty pattern list gracefully`() {
        val pattern = AnyOfPattern(emptyList(), extensions = emptyMap())
        
        val result = pattern.matches(StringValue("hello"), Resolver())
        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `should work with complex nested patterns`() {
        val listPattern = ListPattern(StringPattern())
        val objectPattern = JSONObjectPattern(mapOf("data" to NumberPattern()))
        val pattern = AnyOfPattern(listOf(listPattern, objectPattern), extensions = emptyMap())
        
        val listValue = JSONArrayValue(listOf(StringValue("hello"), StringValue("world")))
        val objectValue = JSONObjectValue(mapOf("data" to NumberValue(42)))
        
        assertThat(pattern.matches(listValue, Resolver())).isInstanceOf(Result.Success::class.java)
        assertThat(pattern.matches(objectValue, Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should handle exact value patterns`() {
        val pattern = AnyOfPattern(
            listOf(
                ExactValuePattern(StringValue("hello")),
                ExactValuePattern(StringValue("world"))
            ),
            extensions = emptyMap()
        )
        
        assertThat(pattern.matches(StringValue("hello"), Resolver())).isInstanceOf(Result.Success::class.java)
        assertThat(pattern.matches(StringValue("world"), Resolver())).isInstanceOf(Result.Success::class.java)
        assertThat(pattern.matches(StringValue("other"), Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `should match only one pattern for successful match`() {
        // Unlike oneOf which requires exactly one match, anyOf requires at least one match
        val stringPattern = StringPattern()
        val alsoStringPattern = ExactValuePattern(StringValue("hello"))
        val pattern = AnyOfPattern(listOf(stringPattern, alsoStringPattern), extensions = emptyMap())
        
        // This should succeed even though both patterns might match "hello"
        val result = pattern.matches(StringValue("hello"), Resolver())
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should return pattern set from all sub-patterns`() {
        val pattern = AnyOfPattern(listOf(NumberPattern(), StringPattern()), extensions = emptyMap())
        val patternSet = pattern.patternSet(Resolver())
        
        assertThat(patternSet).hasSize(2)
        assertThat(patternSet).containsExactlyInAnyOrder(NumberPattern(), StringPattern())
    }

    @Test
    fun `listOf should use first pattern for list creation`() {
        val pattern = AnyOfPattern(listOf(NumberPattern(), StringPattern()), extensions = emptyMap())
        val values = listOf(StringValue("hello"), StringValue("world"))
        
        val result = pattern.listOf(values, Resolver())
        assertThat(result).isInstanceOf(JSONArrayValue::class.java)
    }

    @Test
    fun `listOf should throw exception with empty patterns`() {
        val pattern = AnyOfPattern(emptyList(), extensions = emptyMap())
        val values = listOf(StringValue("hello"))
        
        assertThrows<ContractException> {
            pattern.listOf(values, Resolver())
        }
    }

    @Test
    fun `fillInTheBlanks should return value from first matching pattern`() {
        val pattern = AnyOfPattern(listOf(NumberPattern(), StringPattern()), extensions = emptyMap())
        val value = StringValue("hello")
        
        val result = pattern.fillInTheBlanks(value, Resolver(), false)
        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue).value).isEqualTo(value)
    }

    @Test
    fun `fillInTheBlanks should handle removeExtraKeys for JSON objects`() {
        val objectPattern1 = JSONObjectPattern(mapOf("name" to StringPattern(), "age" to NumberPattern()))
        val objectPattern2 = JSONObjectPattern(mapOf("id" to NumberPattern()))
        val pattern = AnyOfPattern(listOf(objectPattern1, objectPattern2), extensions = emptyMap())
        
        val value = JSONObjectValue(mapOf(
            "name" to StringValue("John"),
            "age" to NumberValue(30),
            "extra" to StringValue("should be removed")
        ))
        
        val result = pattern.fillInTheBlanks(value, Resolver(), true)
        assertThat(result).isInstanceOf(HasValue::class.java)
        
        val resultValue = (result as HasValue).value as JSONObjectValue
        assertThat(resultValue.jsonObject).containsKeys("name", "age")
        assertThat(resultValue.jsonObject).doesNotContainKey("extra")
    }

    @Test
    fun `fillInTheBlanks should return failure when no pattern matches`() {
        val pattern = AnyOfPattern(listOf(NumberPattern(), BooleanPattern()), extensions = emptyMap())
        val value = StringValue("hello")
        
        val result = pattern.fillInTheBlanks(value, Resolver(), false)
        assertThat(result).isInstanceOf(HasFailure::class.java)
    }

    @Test
    fun `fillInTheBlanks should work with two JSON object patterns`() {
        val objectPattern1 = JSONObjectPattern(mapOf("name" to StringPattern(), "age" to NumberPattern()))
        val objectPattern2 = JSONObjectPattern(mapOf("id" to NumberPattern(), "type" to StringPattern()))
        val pattern = AnyOfPattern(listOf(objectPattern1, objectPattern2), extensions = emptyMap())
        
        val value = JSONObjectValue(mapOf(
            "id" to NumberValue(123),
            "type" to StringValue("user")
        ))
        
        val result = pattern.fillInTheBlanks(value, Resolver(), false)
        assertThat(result).isInstanceOf(HasValue::class.java)
        
        val resultValue = (result as HasValue).value as JSONObjectValue
        assertThat(resultValue.jsonObject).containsKeys("id", "type")
        assertThat(resultValue.jsonObject["id"]).isEqualTo(NumberValue(123))
        assertThat(resultValue.jsonObject["type"]).isEqualTo(StringValue("user"))
    }
}