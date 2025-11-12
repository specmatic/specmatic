package io.specmatic.core.matchers

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.specmatic.core.BreadCrumb
import io.specmatic.core.Resolver
import io.specmatic.core.jsonoperator.Optional
import io.specmatic.core.jsonoperator.RequestResponseOperator
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RepetitionMatcherTest {
    private val mockResolver = mockk<Resolver>()
    private val mockOperator = mockk<RequestResponseOperator>()

    @Test
    fun `should succeed when times is -1 without checking exhaustion`() {
        val matcher = RepetitionMatcher(
            path = BreadCrumb.from("/items"),
            times = -1,
            strategy = RepetitionStrategy.ANY,
        )

        val context = MatcherContext(mockResolver, matchOperator = mockOperator)
        val result = matcher.execute(context)
        assertThat(result).isInstanceOf(MatcherResult.Success::class.java)
    }

    @Nested
    inner class SharedOperatorTrackingTests {
        @Test
        fun `should track values in sharedOperator when ANY strategy is used`() {
            val matcher = RepetitionMatcher(
                path = BreadCrumb.from("/items"),
                times = 2,
                strategy = RepetitionStrategy.ANY,
            )

            val mockContext = mockk<MatcherContext>()
            val markerValue = StringValue("*")

            every { mockContext.withUpdatedTimes(2) } returns mockContext
            every { mockContext.getValueToMatch(BreadCrumb.from("/items")) } returns HasValue(Optional.Some(StringValue("item")))
            every { mockContext.getJsonArray(BreadCrumb.from("/items.RepetitionMatcher")) } returns HasValue(JSONArrayValue(listOf(markerValue, markerValue)))
            every { mockContext.addToCurrentExhaustionChecks(any(), any()) } returns mockContext
            every { mockContext.appendToJsonArray(BreadCrumb.from("/items.RepetitionMatcher"), markerValue) } returns HasValue(mockContext)

            matcher.execute(mockContext)
            verify { mockContext.appendToJsonArray(BreadCrumb.from("/items.RepetitionMatcher"), markerValue) }
        }

        @Test
        fun `should track actual values in sharedOperator when EACH strategy is used`() {
            val matcher = RepetitionMatcher(
                path = BreadCrumb.from("/status"),
                times = 1,
                strategy = RepetitionStrategy.EACH,
            )

            val mockContext = mockk<MatcherContext>()
            val targetValue = StringValue("active")

            every { mockContext.withUpdatedTimes(1) } returns mockContext
            every { mockContext.getValueToMatch(BreadCrumb.from("/status")) } returns HasValue(Optional.Some(targetValue))
            every { mockContext.getJsonArray(BreadCrumb.from("/status.RepetitionMatcher")) } returns HasValue(JSONArrayValue(listOf(targetValue)))
            every { mockContext.addToCurrentExhaustionChecks(any(), any()) } returns mockContext
            every { mockContext.appendToJsonArray(BreadCrumb.from("/status.RepetitionMatcher"), targetValue) } returns HasValue(mockContext)

            matcher.execute(mockContext)
            verify { mockContext.appendToJsonArray(BreadCrumb.from("/status.RepetitionMatcher"), targetValue) }
        }

        @Test
        fun `should read from sharedOperator to check exhaustion`() {
            val matcher = RepetitionMatcher(
                path = BreadCrumb.from("/items"),
                times = 3,
                strategy = RepetitionStrategy.ANY,
            )

            val mockContext = mockk<MatcherContext>()
            val existingItems = JSONArrayValue(listOf(StringValue("*"), StringValue("*")))

            every { mockContext.withUpdatedTimes(3) } returns mockContext
            every { mockContext.getValueToMatch(BreadCrumb.from("/items")) } returns HasValue(Optional.Some(StringValue("item")))
            every { mockContext.getJsonArray(BreadCrumb.from("/items.RepetitionMatcher")) } returns HasValue(existingItems)
            every { mockContext.addToCurrentExhaustionChecks(any(), any()) } returns mockContext
            every { mockContext.appendToJsonArray(any(), any()) } returns HasValue(mockContext)

            val result = matcher.execute(mockContext)
            verify { mockContext.getJsonArray(BreadCrumb.from("/items.RepetitionMatcher")) }
            assertThat(result).isInstanceOf(MatcherResult.Success::class.java)
        }
    }

    @Nested
    inner class RunSharedOperatorTrackingTests {
        @Test
        fun `should add to current run exhaustion checks for ANY strategy`() {
            val matcher = RepetitionMatcher(
                path = BreadCrumb.from("/items"),
                times = 1,
                strategy = RepetitionStrategy.ANY,
            )

            val mockContext = mockk<MatcherContext>()
            val markerValue = StringValue("*")

            every { mockContext.withUpdatedTimes(1) } returns mockContext
            every { mockContext.getValueToMatch(BreadCrumb.from("/items")) } returns HasValue(Optional.Some(StringValue("item")))
            every { mockContext.getJsonArray(BreadCrumb.from("/items.RepetitionMatcher")) } returns HasValue(JSONArrayValue(listOf(markerValue)))
            every { mockContext.addToCurrentExhaustionChecks(BreadCrumb.from("/items"), markerValue) } returns mockContext
            every { mockContext.appendToJsonArray(any(), any()) } returns HasValue(mockContext)

            matcher.execute(mockContext)
            verify { mockContext.addToCurrentExhaustionChecks(BreadCrumb.from("/items"), markerValue) }
        }

        @Test
        fun `should add to current run exhaustion checks for EACH strategy`() {
            val matcher = RepetitionMatcher(
                path = BreadCrumb.from("/status"),
                times = 1,
                strategy = RepetitionStrategy.EACH,
            )

            val mockContext = mockk<MatcherContext>()
            val targetValue = StringValue("active")

            every { mockContext.withUpdatedTimes(1) } returns mockContext
            every { mockContext.getValueToMatch(BreadCrumb.from("/status")) } returns HasValue(Optional.Some(targetValue))
            every { mockContext.getJsonArray(BreadCrumb.from("/status.RepetitionMatcher")) } returns HasValue(JSONArrayValue(listOf(targetValue)))
            every { mockContext.addToCurrentExhaustionChecks(BreadCrumb.from("/status"), targetValue) } returns mockContext
            every { mockContext.appendToJsonArray(any(), any()) } returns HasValue(mockContext)

            matcher.execute(mockContext)
            verify { mockContext.addToCurrentExhaustionChecks(BreadCrumb.from("/status"), targetValue) }
        }

        @Test
        fun `should call addToCurrentExhaustionChecks before appendToJsonArray`() {
            val matcher = RepetitionMatcher(
                path = BreadCrumb.from("/items"),
                times = 1,
                strategy = RepetitionStrategy.ANY,
            )

            val mockContext = mockk<MatcherContext>()
            val updatedContext = mockk<MatcherContext>()
            val markerValue = StringValue("*")

            every { mockContext.withUpdatedTimes(1) } returns mockContext
            every { mockContext.getValueToMatch(BreadCrumb.from("/items")) } returns HasValue(Optional.Some(StringValue("item")))
            every { mockContext.getJsonArray(BreadCrumb.from("/items.RepetitionMatcher")) } returns HasValue(JSONArrayValue(listOf(markerValue)))
            every { mockContext.addToCurrentExhaustionChecks(BreadCrumb.from("/items"), markerValue) } returns updatedContext
            every { updatedContext.appendToJsonArray(BreadCrumb.from("/items.RepetitionMatcher"), markerValue) } returns HasValue(updatedContext)

            matcher.execute(mockContext)
            verify(exactly = 1) { mockContext.addToCurrentExhaustionChecks(BreadCrumb.from("/items"), markerValue) }
            verify(exactly = 1) { updatedContext.appendToJsonArray(BreadCrumb.from("/items.RepetitionMatcher"), markerValue) }
        }
    }

    @Nested
    inner class AnyStrategyExhaustionTests {
        @Test
        fun `should be exhausted when ANY strategy has exactly matching times`() {
            val matcher = RepetitionMatcher(
                path = BreadCrumb.from("/items"),
                times = 3,
                strategy = RepetitionStrategy.ANY,
            )

            val mockContext = mockk<MatcherContext>()
            every { mockContext.withUpdatedTimes(3) } returns mockContext
            every { mockContext.getValueToMatch(BreadCrumb.from("/items")) } returns HasValue(Optional.Some(StringValue("item")))
            every { mockContext.getJsonArray(BreadCrumb.from("/items.RepetitionMatcher")) } returns HasValue(
                JSONArrayValue(listOf(StringValue("*"), StringValue("*"), StringValue("*"))),
            )
            every { mockContext.appendToJsonArray(any(), any()) } returns HasValue(mockContext)
            every { mockContext.addToCurrentExhaustionChecks(any(), any()) } returns mockContext

            val result = matcher.execute(mockContext)
            assertThat(result).isInstanceOf(MatcherResult.Exhausted::class.java)
        }

        @Test
        fun `should NOT be exhausted when ANY strategy has fewer items than required times`() {
            val matcher = RepetitionMatcher(
                path = BreadCrumb.from("/items"),
                times = 5,
                strategy = RepetitionStrategy.ANY,
            )

            val mockContext = mockk<MatcherContext>()
            every { mockContext.withUpdatedTimes(5) } returns mockContext
            every { mockContext.getValueToMatch(BreadCrumb.from("/items")) } returns HasValue(Optional.Some(StringValue("item")))
            every { mockContext.getJsonArray(BreadCrumb.from("/items.RepetitionMatcher")) } returns HasValue(
                JSONArrayValue(listOf(StringValue("*"), StringValue("*"))),
            )
            every { mockContext.appendToJsonArray(any(), any()) } returns HasValue(mockContext)
            every { mockContext.addToCurrentExhaustionChecks(any(), any()) } returns mockContext

            val result = matcher.execute(mockContext)
            assertThat(result).isInstanceOf(MatcherResult.Success::class.java)
        }
    }

    @Nested
    inner class EachStrategyExhaustionTests {
        @Test
        fun `should be exhausted when EACH strategy has exactly matching times`() {
            val matcher = RepetitionMatcher(
                path = BreadCrumb.from("/status"),
                times = 2,
                strategy = RepetitionStrategy.EACH,
            )

            val mockContext = mockk<MatcherContext>()
            val targetValue = StringValue("active")

            every { mockContext.withUpdatedTimes(2) } returns mockContext
            every { mockContext.getValueToMatch(BreadCrumb.from("/status")) } returns HasValue(Optional.Some(targetValue))
            every { mockContext.getJsonArray(BreadCrumb.from("/status.RepetitionMatcher")) } returns HasValue(
                JSONArrayValue(listOf(StringValue("active"), StringValue("active"))),
            )
            every { mockContext.appendToJsonArray(any(), any()) } returns HasValue(mockContext)
            every { mockContext.addToCurrentExhaustionChecks(any(), any()) } returns mockContext

            val result = matcher.execute(mockContext)
            assertThat(result).isInstanceOf(MatcherResult.Exhausted::class.java)
        }

        @Test
        fun `should NOT be exhausted when EACH strategy has fewer matching items than required times`() {
            val matcher = RepetitionMatcher(
                path = BreadCrumb.from("/status"),
                times = 3,
                strategy = RepetitionStrategy.EACH,
            )

            val mockContext = mockk<MatcherContext>()
            val targetValue = StringValue("active")

            every { mockContext.withUpdatedTimes(3) } returns mockContext
            every { mockContext.getValueToMatch(BreadCrumb.from("/status")) } returns HasValue(Optional.Some(targetValue))
            every { mockContext.getJsonArray(BreadCrumb.from("/status.RepetitionMatcher")) } returns HasValue(
                JSONArrayValue(listOf(
                    StringValue("active"),
                    StringValue("inactive"),
                    StringValue("active"),
                )),
            )
            every { mockContext.appendToJsonArray(any(), any()) } returns HasValue(mockContext)
            every { mockContext.addToCurrentExhaustionChecks(any(), any()) } returns mockContext

            val result = matcher.execute(mockContext)
            assertThat(result).isInstanceOf(MatcherResult.Success::class.java)
        }
    }

    @Test
    fun `should fail when path cannot be extracted`() {
        val matcher = RepetitionMatcher(
            path = BreadCrumb.from("/missing"),
            times = 1,
            strategy = RepetitionStrategy.ANY,
        )

        val mockContext = mockk<MatcherContext>()
        every { mockContext.withUpdatedTimes(1) } returns mockContext
        every { mockContext.getValueToMatch(BreadCrumb.from("/missing")) } returns HasFailure("Path not found")

        val result = matcher.execute(mockContext)
        assertThat(result).isInstanceOf(MatcherResult.MisMatch::class.java)
    }

    @Test
    fun `should fail when value at path is None`() {
        val matcher = RepetitionMatcher(
            path = BreadCrumb.from("/field"),
            times = 1,
            strategy = RepetitionStrategy.ANY,
        )

        val mockContext = mockk<MatcherContext>()
        every { mockContext.withUpdatedTimes(1) } returns mockContext
        every { mockContext.getValueToMatch(BreadCrumb.from("/field")) } returns HasValue(Optional.None)

        val result = matcher.execute(mockContext)
        assertThat(result).isInstanceOf(MatcherResult.MisMatch::class.java)
    }

    @Test
    fun `canBeExhausted should be true when times is not -1`() {
        val matcher = RepetitionMatcher(times = 3)
        assertThat(matcher.canBeExhausted).isTrue()
    }

    @Test
    fun `canBeExhausted should be false when times is -1`() {
        val matcher = RepetitionMatcher(times = -1)
        assertThat(matcher.canBeExhausted).isFalse()
    }

    @Nested
    inner class RepetitionStrategyTest {
        @Test
        fun `should parse any strategy case-insensitively`() {
            val strategies = listOf("any", "ANY", "Any")
            strategies.forEach { value ->
                val result = RepetitionStrategy.from(value)
                assertThat(result).isInstanceOf(HasValue::class.java)
                assertThat((result as HasValue).value).isEqualTo(RepetitionStrategy.ANY)
            }
        }

        @Test
        fun `should parse each strategy case-insensitively`() {
            val strategies = listOf("each", "EACH", "Each")
            strategies.forEach { value ->
                val result = RepetitionStrategy.from(value)
                assertThat(result).isInstanceOf(HasValue::class.java)
                assertThat((result as HasValue).value).isEqualTo(RepetitionStrategy.EACH)
            }
        }

        @Test
        fun `should fail for invalid strategy`() {
            val result = RepetitionStrategy.from("invalid")
            assertThat(result).isInstanceOf(HasFailure::class.java)
        }
    }
}
