package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.matchers.MatcherEngine
import io.specmatic.core.value.StringValue
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.specmatic.core.DefaultMismatchMessages
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class SupportedTemplatePatternTest {
    @ParameterizedTest
    @ValueSource(strings = ["\$match(data.id)", "$(string)"])
    fun `supported templates should be treated as valid input by scalar patterns`(template: String) {
        val supportedTemplate = StringValue(template)
        val patterns = listOf(
            Base64StringPattern(),
            BinaryPattern(),
            BooleanPattern(),
            DatePattern,
            DateTimePattern,
            EmailPattern(),
            EnumPattern(listOf(StringValue("one"))),
            NullPattern,
            NumberPattern(),
            StringPattern(),
            TimePattern,
            UUIDPattern
        )

        patterns.forEach { pattern ->
            val result = pattern.matches(supportedTemplate, Resolver())
            assertThat(result)
                .withFailMessage("Expected template $template to pass for ${pattern::class.simpleName}")
                .isInstanceOf(Result.Success::class.java)
        }
    }

    @Test
    fun `malformed matcher template should not bypass regular type checks`() {
        val malformedMatcherTemplate = StringValue("\$match(data.id")

        val result = NumberPattern().matches(malformedMatcherTemplate, Resolver())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `negative resolver should keep matcher-generated values that do not match the original pattern`() {
        val matcherEngine = mockk<MatcherEngine>()
        val originalPattern = StringPattern(regex = "^expected$")
        val matcherExpression = StringValue($$"$match(exact: outside)")
        val generatedPattern = ExactValuePattern(StringValue("outside"))

        mockkObject(MatcherEngine.Companion)
        every { MatcherEngine.load() } returns matcherEngine
        every { matcherEngine.patternFrom(matcherExpression, originalPattern, any()) } returns generatedPattern

        try {
            val result = generateValueFromMatcher(
                self = originalPattern,
                value = matcherExpression,
                resolver = Resolver(isNegative = true),
            )

            assertThat(result).isInstanceOf(HasValue::class.java)
            assertThat((result as HasValue).value).isEqualTo(StringValue("outside"))
        } finally {
            unmockkObject(MatcherEngine.Companion)
        }
    }

    @Test
    fun `positive resolver should keep reject matcher-generated values that do not match the original pattern`() {
        val matcherEngine = mockk<MatcherEngine>()
        val originalPattern = StringPattern(regex = "^expected$")
        val matcherExpression = StringValue($$"$match(exact: outside)")
        val generatedPattern = ExactValuePattern(StringValue("outside"))

        mockkObject(MatcherEngine.Companion)
        every { MatcherEngine.load() } returns matcherEngine
        every { matcherEngine.patternFrom(matcherExpression, originalPattern, any()) } returns generatedPattern

        try {
            val result = generateValueFromMatcher(
                self = originalPattern,
                value = matcherExpression,
                resolver = Resolver(isNegative = false),
            )

            assertThat(result).isInstanceOf(HasFailure::class.java); result as HasFailure
            assertThat(result.toFailure().reportString()).contains(
                DefaultMismatchMessages.mismatchMessage(
                    expected = "type string that matches regex ^expected$",
                    actual = "value \"outside\" of type string"
                )
            )
        } finally {
            unmockkObject(MatcherEngine.Companion)
        }
    }
}
