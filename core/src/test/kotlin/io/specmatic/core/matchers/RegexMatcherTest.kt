package io.specmatic.core.matchers

import io.mockk.every
import io.mockk.mockk
import io.specmatic.core.BreadCrumb
import io.specmatic.core.Resolver
import io.specmatic.core.jsonoperator.Optional
import io.specmatic.core.jsonoperator.RequestResponseOperator
import io.specmatic.core.jsonoperator.value.ValueOperator
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RegexMatcherTest {
    private val resolver = Resolver()
    private val mockOperator = mockk<RequestResponseOperator>()

    @Nested
    inner class ExecuteTest {
        @Test
        fun `should return success if the string value matches the regex`() {
            val matcher = RegexMatcher(
                path = BreadCrumb.from("/name"),
                regex = "^J.*"
            )

            every { mockOperator.get("/name") } returns HasValue(Optional.Some(ValueOperator.from(StringValue("John"))))
            val context = MatcherContext(resolver, matchOperator = mockOperator)

            val result = matcher.execute(context)
            assertThat(result).isInstanceOf(MatcherResult.Success::class.java)
        }

        @Test
        fun `should return failure if the string value does not match the regex`() {
            val matcher = RegexMatcher(
                path = BreadCrumb.from("/name"),
                regex = "^K.*"
            )

            every { mockOperator.get("/name") } returns HasValue(Optional.Some(ValueOperator.from(StringValue("John"))))
            val context = MatcherContext(resolver, matchOperator = mockOperator)

            val result = matcher.execute(context)
            assertThat(result).isInstanceOf(MatcherResult.MisMatch::class.java)
        }

        @Test
        fun `should return success if the number value matches the regex`() {
            val matcher = RegexMatcher(
                path = BreadCrumb.from("/name"),
                regex = "^-\\d+$"
            )

            every { mockOperator.get("/name") } returns HasValue(Optional.Some(ValueOperator.from(NumberValue(-4))))
            val context = MatcherContext(resolver, matchOperator = mockOperator)

            val result = matcher.execute(context)
            assertThat(result).isInstanceOf(MatcherResult.Success::class.java)
        }

        @Test
        fun `should return failure if the number value does not match the regex`() {
            val matcher = RegexMatcher(
                path = BreadCrumb.from("/name"),
                regex = "^-\\d+$"
            )

            every { mockOperator.get("/name") } returns HasValue(Optional.Some(ValueOperator.from(NumberValue(4))))
            val context = MatcherContext(resolver, matchOperator = mockOperator)

            val result = matcher.execute(context)
            assertThat(result).isInstanceOf(MatcherResult.MisMatch::class.java)
        }
    }

    @Nested
    inner class ToPatternSimplifiedTest {

        @Test
        fun `should return the pattern for the given regex pattern that matches a string`() {
            val pattern = RegexMatcher.toPatternSimplified(
                StringValue("pattern: ^J.*")
            )

            assertThat(pattern).isInstanceOf(ExactValuePattern::class.java)
            val value = (pattern as ExactValuePattern).pattern
            assertThat(value).isInstanceOf(StringValue::class.java)
            assertThat(value.toStringLiteral()).startsWith("J")
        }

        @Test
        fun `should return the pattern for the given regex pattern that matches a number`() {
            val pattern = RegexMatcher.toPatternSimplified(
                StringValue("pattern: ^2\\d*$")
            )

            assertThat(pattern).isInstanceOf(ExactValuePattern::class.java)
            val value = (pattern as ExactValuePattern).pattern
            assertThat(value).isInstanceOf(NumberValue::class.java)
            assertThat(value.toStringLiteral()).startsWith("2")
        }
    }

    @Nested
    inner class CanParseFromTest {

        @Test
        fun `canParseFrom should return true when properties contain pattern key`() {
            val props = mapOf("pattern" to StringValue("^J.*"))

            val result = RegexMatcher.canParseFrom(BreadCrumb.from(), props)

            assertThat(result).isTrue()
        }

        @Test
        fun `canParseFrom should return false when properties do not contain pattern key`() {
            val props = mapOf("dataType" to StringValue("^J.*"))

            val result = RegexMatcher.canParseFrom(BreadCrumb.from(), props)

            assertThat(result).isFalse()
        }
    }

    @Nested
    inner class ParseFromTest {
        @Test
        fun `parseFrom should return HasValue with RegexMatcher when properties contain valid pattern string`() {
            val props = mapOf("pattern" to StringValue("^J.*"))

            val matcherValue = RegexMatcher.parseFrom(BreadCrumb.from("/name"), props, MatcherContext(resolver))

            assertThat(matcherValue).isInstanceOf(HasValue::class.java)
            val matcher = (matcherValue as HasValue<*>).value as RegexMatcher
            assertThat(matcher.path).isEqualTo(BreadCrumb.from("/name"))
            assertThat(matcher.regex).isEqualTo("^J.*")
        }

        @Test
        fun `parseFrom should return HasFailure when pattern key is not a string`() {
            val props = mapOf("pattern" to NumberValue(123))

            val matcherValue = RegexMatcher.parseFrom(BreadCrumb.from(), props, MatcherContext(resolver))

            assertThat(matcherValue).isNotInstanceOf(HasValue::class.java)
        }
    }

    @Nested
    inner class ParseTest {

        @Test
        fun `parse should return failure when value does not contain properties`() {
            val matcherValue = RegexMatcher.parse(
                path = BreadCrumb.from("/name"),
                value = StringValue("just a string"),
                context = MatcherContext(resolver)
            )

            assertThat(matcherValue).isInstanceOf(HasFailure::class.java)
            val error = (matcherValue as HasFailure<*>).failure.reportString()
            assertThat(error).contains("""Cannot create RegexMatcher from value '"just a string"""")
        }

        @Test
        fun `parse should parse regex when provided as properties string`() {
            val matcherValue = RegexMatcher.parse(
                path = BreadCrumb.from("/name"),
                value = StringValue("pattern: ^K.*"),
                context = MatcherContext(resolver)
            )

            assertThat(matcherValue).isInstanceOf(HasValue::class.java)
            val matcher = (matcherValue as HasValue<*>).value as RegexMatcher
            assertThat(matcher.regex).isEqualTo("^K.*")
        }
    }
}