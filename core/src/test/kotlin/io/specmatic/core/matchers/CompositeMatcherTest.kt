package io.specmatic.core.matchers

import io.mockk.every
import io.mockk.mockk
import io.specmatic.core.BreadCrumb
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.jsonoperator.RequestResponseOperator
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CompositeMatcherTest {
    private val mockResolver = mockk<Resolver>()
    private val mockOperator = mockk<RequestResponseOperator>()

    @Test
    fun `should execute all non-exhaustive matchers first acting as a guard-clause`() {
        val matcher1 = mockk<Matcher>()
        val matcher2 = mockk<Matcher>()

        every { matcher1.canBeExhausted } returns false
        every { matcher2.canBeExhausted } returns false
        every { matcher1.execute(any()) } returns MatcherResult.Success(MatcherContext(mockResolver))
        every { matcher2.execute(any()) } returns MatcherResult.Success(MatcherContext(mockResolver))

        val compositeMatcher = CompositeMatcher(
            path = BreadCrumb.from("/test"),
            matchers = listOf(matcher1, matcher2),
        )

        val context = MatcherContext(mockResolver, matchOperator = mockOperator)
        val result = compositeMatcher.execute(context)
        assertThat(result).isInstanceOf(MatcherResult.Success::class.java)
    }

    @Test
    fun `should execute exhaustive matchers after non-exhaustive matchers`() {
        val nonExhaustiveMatcher = mockk<Matcher>()
        val exhaustiveMatcher = mockk<Matcher>()

        every { nonExhaustiveMatcher.canBeExhausted } returns false
        every { exhaustiveMatcher.canBeExhausted } returns true
        every { nonExhaustiveMatcher.execute(any()) } returns MatcherResult.Success(MatcherContext(mockResolver))
        every { exhaustiveMatcher.execute(any()) } returns MatcherResult.Exhausted(MatcherContext(mockResolver))

        val compositeMatcher = CompositeMatcher(
            path = BreadCrumb.from("/test"),
            matchers = listOf(nonExhaustiveMatcher, exhaustiveMatcher),
        )

        val context = MatcherContext(mockResolver, matchOperator = mockOperator)
        val result = compositeMatcher.execute(context)
        assertThat(result).isInstanceOf(MatcherResult.Exhausted::class.java)
    }

    @Test
    fun `should fail immediately if non-exhaustive matcher fails`() {
        val nonExhaustiveMatcher = mockk<Matcher>()
        val exhaustiveMatcher = mockk<Matcher>()

        every { nonExhaustiveMatcher.canBeExhausted } returns false
        every { exhaustiveMatcher.canBeExhausted } returns true
        every { nonExhaustiveMatcher.execute(any()) } returns MatcherResult.MisMatch(
            Result.Failure("Non-exhaustive failed"), MatcherContext(mockResolver, matchOperator = mockOperator),
        )

        val compositeMatcher = CompositeMatcher(
            path = BreadCrumb.from("/test"),
            matchers = listOf(nonExhaustiveMatcher, exhaustiveMatcher),
        )

        val context = MatcherContext(mockResolver, matchOperator = mockOperator)
        val result = compositeMatcher.execute(context)

        assertThat(result).isInstanceOf(MatcherResult.MisMatch::class.java)
    }

    @Test
    fun `should use any result strategy for exhaustive matchers result coalescing returning exhausted when all are exhausted`() {
        val exhaustiveMatcher1 = mockk<Matcher>()
        val exhaustiveMatcher2 = mockk<Matcher>()

        every { exhaustiveMatcher1.canBeExhausted } returns true
        every { exhaustiveMatcher2.canBeExhausted } returns true
        every { exhaustiveMatcher1.execute(any()) } returns MatcherResult.Success(MatcherContext(mockResolver))
        every { exhaustiveMatcher2.execute(any()) } returns MatcherResult.Exhausted(MatcherContext(mockResolver))

        val compositeMatcher = CompositeMatcher(
            path = BreadCrumb.from("/test"),
            matchers = listOf(exhaustiveMatcher1, exhaustiveMatcher2),
        )

        val context = MatcherContext(mockResolver, matchOperator = mockOperator)
        val result = compositeMatcher.execute(context)

        assertThat(result).isInstanceOf(MatcherResult.Exhausted::class.java)
    }

    @Test
    fun `should return success when at-least one exhaustive matcher is not exhausted`() {
        val exhaustiveMatcher1 = mockk<Matcher>()
        val exhaustiveMatcher2 = mockk<Matcher>()

        every { exhaustiveMatcher1.canBeExhausted } returns true
        every { exhaustiveMatcher2.canBeExhausted } returns true
        every { exhaustiveMatcher1.execute(any()) } returns MatcherResult.Success(MatcherContext(mockResolver))
        every { exhaustiveMatcher2.execute(any()) } returns MatcherResult.Success(MatcherContext(mockResolver))

        val compositeMatcher = CompositeMatcher(
            path = BreadCrumb.from("/test"),
            matchers = listOf(exhaustiveMatcher1, exhaustiveMatcher2),
        )

        val context = MatcherContext(mockResolver, matchOperator = mockOperator)
        val result = compositeMatcher.execute(context)
        assertThat(result).isInstanceOf(MatcherResult.Success::class.java)
    }

    @Test
    fun `canBeExhausted should be true if any matcher can be exhausted`() {
        val nonExhaustiveMatcher = mockk<Matcher>()
        val exhaustiveMatcher = mockk<Matcher>()

        every { nonExhaustiveMatcher.canBeExhausted } returns false
        every { exhaustiveMatcher.canBeExhausted } returns true

        val compositeMatcher = CompositeMatcher(matchers = listOf(nonExhaustiveMatcher, exhaustiveMatcher))
        assertThat(compositeMatcher.canBeExhausted).isTrue()
    }

    @Test
    fun `canBeExhausted should be false if no matchers can be exhausted`() {
        val matcher1 = mockk<Matcher>()
        val matcher2 = mockk<Matcher>()

        every { matcher1.canBeExhausted } returns false
        every { matcher2.canBeExhausted } returns false

        val compositeMatcher = CompositeMatcher(matchers = listOf(matcher1, matcher2))
        assertThat(compositeMatcher.canBeExhausted).isFalse()
    }

    @Test
    fun `createDynamicMatchers should return itself`() {
        val compositeMatcher = CompositeMatcher()
        val context = MatcherContext(mockResolver, matchOperator = mockOperator)
        val dynamicMatchers = compositeMatcher.createDynamicMatchers(context)

        assertThat(dynamicMatchers).hasSize(1)
        assertThat(dynamicMatchers[0]).isEqualTo(compositeMatcher)
    }

    @Nested
    inner class CompositeMatcherFactoryTest {
        private val mockResolver = mockk<Resolver>()
        private val mockOperator = mockk<RequestResponseOperator>()
        private val context = MatcherContext(mockResolver, matchOperator = mockOperator)

        @Test
        fun `should parse from dictionary value with 2 possible matchers`() {
            val result = CompositeMatcher.parse(
                path = BreadCrumb.from("/test"),
                value = StringValue("exact: test, times: 2"),
                context = context,
            )

            assertThat(result).isInstanceOf(HasValue::class.java)
            val matcher = (result as HasValue).value
            assertThat(matcher.matchers).hasSize(2)
        }

        @Test
        fun `should fail when value is not an dictionary string`() {
            val result = CompositeMatcher.parse(
                path = BreadCrumb.from("/test"),
                value = StringValue("not an object"),
                context = context,
            )

            assertThat(result).isInstanceOf(HasFailure::class.java)
            assertThat((result as HasFailure).failure.reportString()).contains("Cannot create CompositeMatcher")
        }

        @Test
        fun `should fail when parsing from properties`() {
            val properties = mapOf("exact" to StringValue("test"))
            val result = CompositeMatcher.parseFrom(
                path = BreadCrumb.from("/test"),
                properties = properties,
                context = context,
            )

            assertThat(result).isInstanceOf(HasFailure::class.java)
            assertThat((result as HasFailure).failure.reportString()).contains("CompositeMatcher cannot be parsed from properties")
        }

        @Test
        fun `canParseFrom should always return false`() {
            val properties = mapOf("exact" to StringValue("test"))
            val result = CompositeMatcher.canParseFrom(
                path = BreadCrumb.from("/test"),
                properties = properties,
            )
            assertThat(result).isFalse()
        }
    }
}
