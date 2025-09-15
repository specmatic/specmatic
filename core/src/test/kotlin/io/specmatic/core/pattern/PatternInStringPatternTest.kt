package io.specmatic.core.pattern

import io.specmatic.GENERATION
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.value.StringValue
import io.specmatic.shouldMatch
import org.junit.jupiter.api.Tag

internal class PatternInStringPatternTest {
    @Test
    fun `should match a number in string`() {
        StringValue("10") shouldMatch PatternInStringPattern(NumberPattern())
    }

    @Test
    fun `should match a boolean in string`() {
        StringValue("true") shouldMatch PatternInStringPattern(BooleanPattern())
    }

    @Test
    fun `should generate a number in a string`() {
        val value = PatternInStringPattern(NumberPattern()).generate(Resolver())

        assertThat(value).isInstanceOf(StringValue::class.java)
        if(value !is StringValue)
            fail("Expected StringValue")

        try {
            value.string.toInt()
        } catch(e: Throwable) {
            fail("Value was not a number: ${e.message}")
        }
    }

    @Test
    fun `should generate a list of patterns based on a Row`() {
        val patterns = PatternInStringPattern(NumberPattern()).newBasedOn(Row(), Resolver()).map { it.value }.toList()

        assertThat(patterns).hasSize(1)

        val pattern = patterns.first()

        assertThat(pattern).isInstanceOf(PatternInStringPattern::class.java)

        if (pattern !is PatternInStringPattern) fail("Expected PatternInStringPattern")

        assertThat(pattern.pattern).isInstanceOf(NumberPattern::class.java)
    }

    @Test
    fun `should parse a string`() {
        val pattern = PatternInStringPattern(NumberPattern())
        val value = pattern.parse("10", Resolver())

        assertThat(value).isEqualTo(StringValue("10"))
    }

    @Test
    fun `should match another pattern-in-string of the same inner pattern type`() {
        val pattern1 = PatternInStringPattern(NumberPattern())
        val pattern2 = PatternInStringPattern(NumberPattern())

        assertThat(pattern1.encompasses(pattern2, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)

        val pattern3 = PatternInStringPattern(BooleanPattern())
        assertThat(pattern1.encompasses(pattern3, Resolver(), Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `should encompass itself`() {
        val type = parsedPattern("""(number in string)""")
        assertThat(type.encompasses(type, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should not encompass another contained type`() {
        val numberInString = parsedPattern("""(number in string)""")
        val booleanInString = parsedPattern("""(boolean in string)""")
        assertThat(numberInString.encompasses(booleanInString, Resolver(), Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `should encompass value matching the given type`() {
        val numberInStringType = parsedPattern("""(number in string)""")
        val numberInStringValue = ExactValuePattern(StringValue("""10"""))
        assertEncompasses(numberInStringType, Resolver(), numberInStringValue, Resolver())
    }

    @Test
    @Tag(GENERATION)
    fun `negative values should be generated`() {
        val result = PatternInStringPattern().negativeBasedOn(Row(), Resolver()).map { it.value }.toList()
        assertThat(result.map { it.typeName }).containsExactlyInAnyOrder(
            "null"
        )
    }

    private fun assertEncompasses(bigger: Pattern, biggerResolver: Resolver, smaller: ExactValuePattern, smallerResolver: Resolver) {
        val result = bigger.encompasses(smaller, biggerResolver, smallerResolver)
        if(result is Result.Failure) fail(result.toReport().toText())
    }
}
