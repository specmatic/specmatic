package io.specmatic.core.matchers

import io.mockk.every
import io.mockk.mockk
import io.specmatic.core.BreadCrumb
import io.specmatic.core.Resolver
import io.specmatic.core.jsonoperator.Optional
import io.specmatic.core.jsonoperator.RequestResponseOperator
import io.specmatic.core.jsonoperator.value.ValueOperator
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EqualityMatcherTest {
    private val mockResolver = mockk<Resolver>()
    private val mockOperator = mockk<RequestResponseOperator>()

    @Test
    fun `should match when values are equal using EQUALS strategy`() {
        val matcher = EqualityMatcher(
            path = BreadCrumb.from("/name"),
            value = StringValue("John"),
            strategy = EqualityStrategy.EQUALS,
        )

        every { mockOperator.get("/name") } returns HasValue(Optional.Some(ValueOperator.from(StringValue("John"))))
        val context = MatcherContext(mockResolver, matchOperator = mockOperator)

        val result = matcher.execute(context)
        assertThat(result).isInstanceOf(MatcherResult.Success::class.java)
    }

    @Test
    fun `should fail when values are not equal using EQUALS strategy`() {
        val matcher = EqualityMatcher(
            path = BreadCrumb.from("/name"),
            value = StringValue("John"),
            strategy = EqualityStrategy.EQUALS,
        )

        every { mockOperator.get("/name") } returns HasValue(Optional.Some(ValueOperator.from(StringValue("Jane"))))
        val context = MatcherContext(mockResolver, matchOperator = mockOperator)

        val result = matcher.execute(context)
        assertThat(result).isInstanceOf(MatcherResult.MisMatch::class.java)
        assertThat((result as MatcherResult.MisMatch).failure.reportString()).contains("equal to", "John", "Jane")
    }

    @Test
    fun `should match when values are not equal using NOT_EQUALS strategy`() {
        val matcher = EqualityMatcher(
            path = BreadCrumb.from("/status"),
            value = StringValue("inactive"),
            strategy = EqualityStrategy.NOT_EQUALS,
        )

        every { mockOperator.get("/status") } returns HasValue(Optional.Some(ValueOperator.from(StringValue("active"))))
        val context = MatcherContext(mockResolver, matchOperator = mockOperator)

        val result = matcher.execute(context)
        assertThat(result).isInstanceOf(MatcherResult.Success::class.java)
    }

    @Test
    fun `should fail when values are equal using NOT_EQUALS strategy`() {
        val matcher = EqualityMatcher(
            path = BreadCrumb.from("/status"),
            value = StringValue("active"),
            strategy = EqualityStrategy.NOT_EQUALS,
        )

        every { mockOperator.get("/status") } returns HasValue(Optional.Some(ValueOperator.from(StringValue("active"))))
        val context = MatcherContext(mockResolver, matchOperator = mockOperator)

        val result = matcher.execute(context)
        assertThat(result).isInstanceOf(MatcherResult.MisMatch::class.java)
        assertThat((result as MatcherResult.MisMatch).failure.reportString()).contains("not equal to", "active")
    }

    @Test
    fun `should fail when path cannot be extracted`() {
        val matcher = EqualityMatcher(
            path = BreadCrumb.from("/missing"),
            value = StringValue("test"),
            strategy = EqualityStrategy.EQUALS,
        )

        every { mockOperator.get("/missing") } returns HasFailure("Path not found")
        val context = MatcherContext(mockResolver, matchOperator = mockOperator)

        val result = matcher.execute(context)
        assertThat(result).isInstanceOf(MatcherResult.MisMatch::class.java)
        assertThat((result as MatcherResult.MisMatch).failure.reportString()).contains("Couldn't extract path")
    }

    @Test
    fun `should work with numeric values`() {
        val matcher = EqualityMatcher(
            path = BreadCrumb.from("/age"),
            value = NumberValue(30),
            strategy = EqualityStrategy.EQUALS,
        )

        every { mockOperator.get("/age") } returns HasValue(Optional.Some(ValueOperator.from(NumberValue(30))))
        val context = MatcherContext(mockResolver, matchOperator = mockOperator)

        val result = matcher.execute(context)
        assertThat(result).isInstanceOf(MatcherResult.Success::class.java)
    }

    @Nested
    inner class EqualityFactoryTest {
        private val mockResolver = mockk<Resolver>()
        private val mockOperator = mockk<RequestResponseOperator>()
        private val context = MatcherContext(mockResolver, matchOperator = mockOperator)

        @Test
        fun `should parse simple value as EQUALS matcher with EqualityFactory`() {
            val result = EqualityMatcher.Companion.EqualityFactory.parse(
                path = BreadCrumb.from("/name"),
                value = StringValue("test"),
                context = context,
            )

            assertThat(result).isInstanceOf(HasValue::class.java)
            val matcher = (result as HasValue).value
            assertThat(matcher.strategy).isEqualTo(EqualityStrategy.EQUALS)
        }

        @Test
        fun `should parse simple value as NOT_EQUALS matcher with NonEqualityFactory`() {
            val result = EqualityMatcher.Companion.NonEqualityFactory.parse(
                path = BreadCrumb.from("/status"),
                value = StringValue("inactive"),
                context = context,
            )

            assertThat(result).isInstanceOf(HasValue::class.java)
            val matcher = (result as HasValue).value
            assertThat(matcher.strategy).isEqualTo(EqualityStrategy.NOT_EQUALS)
        }

        @Test
        fun `should parse object with exact key`() {
            val result = EqualityMatcher.Companion.EqualityFactory.parse(
                path = BreadCrumb.from("/field"),
                value = StringValue("exact: expectedValue"),
                context = context,
            )

            assertThat(result).isInstanceOf(HasValue::class.java)
            val matcher = (result as HasValue).value
            assertThat(matcher.value).isEqualTo(StringValue("expectedValue"))
            assertThat(matcher.strategy).isEqualTo(EqualityStrategy.EQUALS)
        }

        @Test
        fun `should parse object with exact and matchType keys`() {
            val result = EqualityMatcher.Companion.NonEqualityFactory.parse(
                path = BreadCrumb.from("/field"),
                value = StringValue("exact: test, matchType: neq"),
                context = context,
            )

            assertThat(result).isInstanceOf(HasValue::class.java)
            val matcher = (result as HasValue).value
            assertThat(matcher.strategy).isEqualTo(EqualityStrategy.NOT_EQUALS)
        }

        @Test
        fun `should fail when matchType is not a string`() {
            val result = EqualityMatcher.Companion.EqualityFactory.parse(
                path = BreadCrumb.from("/field"),
                value = StringValue("exact: test, matchType: 123"),
                context = context,
            )

            assertThat(result).isInstanceOf(HasFailure::class.java)
            assertThat((result as HasFailure).failure.reportString()).contains("matchType", "eq or neq")
        }

        @Test
        fun `should fail when matchType is invalid`() {
            val result = EqualityMatcher.Companion.EqualityFactory.parse(
                path = BreadCrumb.from("/field"),
                value = StringValue("exact: test, matchType: invalid"),
                context = context,
            )

            assertThat(result).isInstanceOf(HasFailure::class.java)
        }
    }

    @Nested
    inner class EqualityStrategyTest {
        @Test
        fun `should parse eq strategy case-insensitively`() {
            val strategies = listOf("eq", "EQ", "Eq", "eQ")
            strategies.forEach { value ->
                val result = EqualityStrategy.from(value)
                assertThat(result).isInstanceOf(HasValue::class.java)
                assertThat((result as HasValue).value).isEqualTo(EqualityStrategy.EQUALS)
            }
        }

        @Test
        fun `should parse neq strategy case-insensitively`() {
            val strategies = listOf("neq", "NEQ", "Neq", "nEq")
            strategies.forEach { value ->
                val result = EqualityStrategy.from(value)
                assertThat(result).isInstanceOf(HasValue::class.java)
                assertThat((result as HasValue).value).isEqualTo(EqualityStrategy.NOT_EQUALS)
            }
        }

        @Test
        fun `should fail for invalid strategy`() {
            val result = EqualityStrategy.from("invalid")
            assertThat(result).isInstanceOf(HasFailure::class.java)
            assertThat((result as HasFailure).failure.reportString()).contains("Invalid EqualityStrategy")
        }

        @Test
        fun `EQUALS strategy should match equal values`() {
            val actual = StringValue("test")
            val expected = StringValue("test")
            assertThat(EqualityStrategy.EQUALS.matches(actual, expected)).isTrue()
        }

        @Test
        fun `EQUALS strategy should not match different values`() {
            val actual = StringValue("test")
            val expected = StringValue("other")
            assertThat(EqualityStrategy.EQUALS.matches(actual, expected)).isFalse()
        }

        @Test
        fun `NOT_EQUALS strategy should match different values`() {
            val actual = StringValue("test")
            val expected = StringValue("other")
            assertThat(EqualityStrategy.NOT_EQUALS.matches(actual, expected)).isTrue()
        }

        @Test
        fun `NOT_EQUALS strategy should not match equal values`() {
            val actual = StringValue("test")
            val expected = StringValue("test")
            assertThat(EqualityStrategy.NOT_EQUALS.matches(actual, expected)).isFalse()
        }
    }
}
