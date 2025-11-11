package io.specmatic.core.matchers

import io.mockk.every
import io.mockk.mockk
import io.specmatic.core.BreadCrumb
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.jsonoperator.Optional
import io.specmatic.core.jsonoperator.RequestResponseOperator
import io.specmatic.core.jsonoperator.value.ValueOperator
import io.specmatic.core.pattern.*
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PatternMatcherTest {
    private val mockOperator = mockk<RequestResponseOperator>()
    private val mockResolver = mockk<Resolver>()

    @Test
    fun `should match when value satisfies pattern with FULL strategy`() {
        val matcher = PatternMatcher(
            path = BreadCrumb.from("/email"),
            pattern = EmailPattern(),
            strategy = PatternMatchStrategy.FULL,
        )

        every { mockOperator.get("/email") } returns HasValue(Optional.Some(ValueOperator.from(StringValue("test@example.com"))))
        val context = MatcherContext(Resolver(), matchOperator = mockOperator)

        val result = matcher.rawExecute(context)
        assertThat(result).isInstanceOf(MatcherResult.Success::class.java)
    }

    @Test
    fun `should fail when value does not satisfy pattern`() {
        val matcher = PatternMatcher(
            path = BreadCrumb.from("/email"),
            pattern = EmailPattern(),
            strategy = PatternMatchStrategy.FULL,
        )

        every { mockOperator.get("/email") } returns HasValue(Optional.Some(ValueOperator.from(StringValue("invalid"))))
        val context = MatcherContext(Resolver(), matchOperator = mockOperator)

        val result = matcher.rawExecute(context)
        assertThat(result).isInstanceOf(MatcherResult.MisMatch::class.java)
        assertThat((result as MatcherResult.MisMatch).failure.reportString()).contains("Expected email string")
    }

    @Test
    fun `should use PARTIAL strategy to update resolver`() {
        val mockPattern = mockk<Pattern>()
        val partializedResolver = mockk<Resolver>()

        every { mockResolver.partializeKeyCheck() } returns partializedResolver
        every { mockPattern.matches(StringValue("test"), partializedResolver) } returns Result.Success()

        val matcher = PatternMatcher(
            path = BreadCrumb.from("/field"),
            pattern = mockPattern,
            strategy = PatternMatchStrategy.PARTIAL,
        )

        every { mockOperator.get("/field") } returns HasValue(Optional.Some(ValueOperator.from(StringValue("test"))))
        val context = MatcherContext(mockResolver, matchOperator = mockOperator)

        val result = matcher.rawExecute(context)
        assertThat(result).isInstanceOf(MatcherResult.Success::class.java)
    }

    @Test
    fun `should fail when path cannot be extracted`() {
        val mockPattern = mockk<Pattern>()
        val matcher = PatternMatcher(
            path = BreadCrumb.from("/missing"),
            pattern = mockPattern,
        )

        every { mockOperator.get("/missing") } returns HasFailure("Path not found")
        val context = MatcherContext(mockResolver, matchOperator = mockOperator)

        val result = matcher.rawExecute(context)
        assertThat(result).isInstanceOf(MatcherResult.MisMatch::class.java)
        assertThat((result as MatcherResult.MisMatch).failure.reportString()).contains("Couldn't extract path")
    }

    @Test
    fun `should fail when value at path is null`() {
        val mockPattern = mockk<Pattern>()
        val matcher = PatternMatcher(
            path = BreadCrumb.from("/field"),
            pattern = mockPattern,
        )

        every { mockOperator.get("/field") } returns HasValue(Optional.None)
        val context = MatcherContext(mockResolver, matchOperator = mockOperator)

        val result = matcher.rawExecute(context)
        assertThat(result).isInstanceOf(MatcherResult.MisMatch::class.java)
        assertThat((result as MatcherResult.MisMatch).failure.reportString()).contains("Couldn't find value at path")
    }

    @Nested
    inner class PatternMatcherFactoryTest {
        private val mockResolver = mockk<Resolver>()
        private val mockOperator = mockk<RequestResponseOperator>()
        private val context = MatcherContext(mockResolver, matchOperator = mockOperator)

        @Test
        fun `should parse simple pattern string`() {
            val mockPattern = mockk<Pattern>()
            every { mockResolver.getPattern("(string)") } returns mockPattern

            val result = PatternMatcher.parse(
                path = BreadCrumb.from("/name"),
                value = StringValue("string"),
                context = context,
            )

            assertThat(result).isInstanceOf(HasValue::class.java)
            val matcher = (result as HasValue).value
            assertThat(matcher.pattern).isEqualTo(mockPattern)
            assertThat(matcher.strategy).isEqualTo(PatternMatchStrategy.FULL)
        }

        @Test
        fun `should parse dataType property as pattern`() {
            val mockPattern = mockk<Pattern>()
            every { mockResolver.getPattern("(number)") } returns mockPattern

            val result = PatternMatcher.parse(
                path = BreadCrumb.from("/age"),
                value = StringValue("dataType: number"),
                context = context,
            )

            assertThat(result).isInstanceOf(HasValue::class.java)
            val matcher = (result as HasValue).value
            assertThat(matcher.pattern).isEqualTo(mockPattern)
            assertThat(matcher.strategy).isEqualTo(PatternMatchStrategy.FULL)
        }

        @Test
        fun `should parse dataType with partial strategy`() {
            val mockPattern = mockk<Pattern>()
            every { mockResolver.getPattern("(object)") } returns mockPattern

            val result = PatternMatcher.parse(
                path = BreadCrumb.from("/data"),
                value = StringValue("dataType: object, partial: partial"),
                context = context,
            )

            assertThat(result).isInstanceOf(HasValue::class.java)
            val matcher = (result as HasValue).value
            assertThat(matcher.strategy).isEqualTo(PatternMatchStrategy.PARTIAL)
        }

        @Test
        fun `should fail when value is not a string`() {
            val result = PatternMatcher.parse(
                path = BreadCrumb.from("/field"),
                value = NumberValue(123),
                context = context,
            )

            assertThat(result).isInstanceOf(HasFailure::class.java)
            assertThat((result as HasFailure).failure.reportString()).contains("expected String")
        }

        @Test
        fun `should fail when dataType is not a string`() {
            val result = PatternMatcher.parse(
                path = BreadCrumb.from("/field"),
                value = StringValue("dataType: 123"),
                context = context,
            )

            assertThat(result).isInstanceOf(HasFailure::class.java)
            assertThat((result as HasFailure).failure.reportString()).contains("dataType")
        }

        @Test
        fun `should fail when partial strategy is invalid`() {
            val mockPattern = mockk<Pattern>()
            every { mockResolver.getPattern("(string)") } returns mockPattern

            val result = PatternMatcher.parse(
                path = BreadCrumb.from("/field"),
                value = StringValue("dataType: string, partial: invalid"),
                context = context,
            )

            assertThat(result).isInstanceOf(HasFailure::class.java)
            assertThat((result as HasFailure).failure.reportString()).contains("Invalid PatternMatchStrategy")
        }

        @Test
        fun `should fail when resolver throws exception`() {
            every { mockResolver.getPattern(any()) } throws IllegalArgumentException("Unknown pattern")

            val result = PatternMatcher.parse(
                path = BreadCrumb.from("/field"),
                value = StringValue("unknown"),
                context = context,
            )

            assertThat(result).isInstanceOf(HasException::class.java)
        }
    }

    @Nested
    inner class PatternMatchStrategyTest {
        @Test
        fun `should parse full strategy case-insensitively`() {
            val strategies = listOf("full", "FULL", "Full", "fUlL")
            strategies.forEach { value ->
                val result = PatternMatchStrategy.from(value)
                assertThat(result).isInstanceOf(HasValue::class.java)
                assertThat((result as HasValue).value).isEqualTo(PatternMatchStrategy.FULL)
            }
        }

        @Test
        fun `should parse partial strategy case-insensitively`() {
            val strategies = listOf("partial", "PARTIAL", "Partial", "pArTiAl")
            strategies.forEach { value ->
                val result = PatternMatchStrategy.from(value)
                assertThat(result).isInstanceOf(HasValue::class.java)
                assertThat((result as HasValue).value).isEqualTo(PatternMatchStrategy.PARTIAL)
            }
        }

        @Test
        fun `should fail for invalid strategy`() {
            val result = PatternMatchStrategy.from("invalid")
            assertThat(result).isInstanceOf(HasFailure::class.java)
            assertThat((result as HasFailure).failure.reportString()).contains("Invalid PatternMatchStrategy")
        }

        @Test
        fun `FULL strategy should use default key check`() {
            val mockResolver = mockk<Resolver>()
            val updatedResolver = mockk<Resolver>()
            every { mockResolver.copy(findKeyErrorCheck = any()) } returns updatedResolver

            val result = PatternMatchStrategy.FULL.update(mockResolver)
            assertThat(result).isEqualTo(updatedResolver)
        }

        @Test
        fun `PARTIAL strategy should partialize key check`() {
            val mockResolver = mockk<Resolver>()
            val partializedResolver = mockk<Resolver>()
            every { mockResolver.partializeKeyCheck() } returns partializedResolver

            val result = PatternMatchStrategy.PARTIAL.update(mockResolver)
            assertThat(result).isEqualTo(partializedResolver)
        }
    }
}
