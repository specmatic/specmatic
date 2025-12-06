package io.specmatic.core.fuzzy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class LevenshteinStrategyTest {
    private val strategy = LevenshteinStrategy()

    @Nested
    inner class DistanceCalculation {
        @ParameterizedTest(name = "distance({0}, {1}) = {2}")
        @CsvSource(
            "hello, hello, 0",
            "hello, hallo, 1",
            "hello, helo, 1",
            "hello, helloo, 1",
            "kitten, sitting, 3",
            "flaw, lawn, 2",
            "saturday, sunday, 3",
            "'', hello, 5",
            "hello, '', 5",
            "'', '', 0"
        )
        fun `should calculate correct Levenshtein distance`(s1: String, s2: String, expected: Int) {
            assertThat(strategy.score(s1, s2)).isEqualTo(expected)
        }

        @Test
        fun `should be symmetric`() {
            assertThat(strategy.score("hello", "hallo")).isEqualTo(strategy.score("hallo", "hello"))
            assertThat(strategy.score("abc", "xyz")).isEqualTo(strategy.score("xyz", "abc"))
        }

        @Test
        fun `should handle single character differences`() {
            assertThat(strategy.score("a", "b")).isEqualTo(1)
            assertThat(strategy.score("a", "a")).isEqualTo(0)
        }

        @ParameterizedTest(name = "insertion: {0} -> {1}")
        @CsvSource(
            "test, tests, 1",
            "test, ttest, 1",
            "test, testt, 1"
        )
        fun `should count single insertion as distance 1`(s1: String, s2: String, expected: Int) {
            assertThat(strategy.score(s1, s2)).isEqualTo(expected)
        }

        @ParameterizedTest(name = "deletion: {0} -> {1}")
        @CsvSource(
            "tests, test, 1",
            "ttest, test, 1",
            "testt, test, 1"
        )
        fun `should count single deletion as distance 1`(s1: String, s2: String, expected: Int) {
            assertThat(strategy.score(s1, s2)).isEqualTo(expected)
        }

        @ParameterizedTest(name = "substitution: {0} -> {1}")
        @CsvSource(
            "test, tast, 1",
            "test, text, 1",
            "test, best, 1"
        )
        fun `should count single substitution as distance 1`(s1: String, s2: String, expected: Int) {
            assertThat(strategy.score(s1, s2)).isEqualTo(expected)
        }
    }

    @Nested
    inner class MaxAllowedDistance {
        @ParameterizedTest(name = "length {0} -> maxDistance {1}")
        @CsvSource(
            "3, 2",
            "4, 2",
            "5, 2",
            "6, 3",
            "10, 3",
            "20, 3"
        )
        fun `should return correct max allowed distance based on length`(length: Int, expected: Int) {
            assertThat(strategy.maxAllowedDistance(length)).isEqualTo(expected)
        }

        @Test
        fun `should be stricter for short words`() {
            assertThat(strategy.maxAllowedDistance(3)).isLessThan(strategy.maxAllowedDistance(10))
        }
    }
}
