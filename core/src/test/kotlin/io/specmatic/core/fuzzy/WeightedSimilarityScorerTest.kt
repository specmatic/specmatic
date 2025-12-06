package io.specmatic.core.fuzzy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource

class WeightedSimilarityScorerTest {
    private val scorer = WeightedSimilarityScorer()
    private val uniformWeights = mapOf("user" to 1.0, "name" to 1.0, "id" to 1.0, "account" to 1.0)

    @Nested
    inner class TokenMatching {
        @ParameterizedTest(name = "exact: {0} == {1}")
        @CsvSource(
            "user, user",
            "name, name",
            "id, id",
            "httpRequest, httpRequest"
        )
        fun `should return EXACT for identical tokens`(input: String, candidate: String) {
            assertThat(scorer.matchToken(input, candidate)).isEqualTo(MatchQuality.EXACT)
        }

        @ParameterizedTest(name = "prefix: {0} matches {1}")
        @CsvSource(
            "sec, seconds",
            "milli, milliseconds",
            "auth, authentication",
            "req, request"
        )
        fun `should return PREFIX when input is prefix of candidate`(input: String, candidate: String) {
            assertThat(scorer.matchToken(input, candidate)).isEqualTo(MatchQuality.PREFIX)
        }

        @ParameterizedTest(name = "prefix reverse: {0} matches {1}")
        @CsvSource(
            "seconds, sec",
            "milliseconds, milli",
            "authentication, auth"
        )
        fun `should return PREFIX when candidate is prefix of input`(input: String, candidate: String) {
            assertThat(scorer.matchToken(input, candidate)).isEqualTo(MatchQuality.PREFIX)
        }

        @ParameterizedTest(name = "fuzzy: {0} ~ {1}")
        @CsvSource(
            "requst, request",
            "accout, account",
            "authenication, authentication",
            "respnse, response"
        )
        fun `should return FUZZY for typos within threshold`(input: String, candidate: String) {
            assertThat(scorer.matchToken(input, candidate)).isEqualTo(MatchQuality.FUZZY)
        }

        @ParameterizedTest(name = "no match: {0} !~ {1}")
        @CsvSource(
            "user, account",
            "name, address",
            "id, type",
            "abc, xyz"
        )
        fun `should return NONE for completely different tokens`(input: String, candidate: String) {
            assertThat(scorer.matchToken(input, candidate)).isEqualTo(MatchQuality.NONE)
        }

        @ParameterizedTest(name = "short token: {0} !~ {1}")
        @CsvSource(
            "a, b",
            "id, nm",
            "ab, cd",
            "usr, acc"
        )
        fun `should return NONE for tokens shorter than minimum length`(input: String, candidate: String) {
            assertThat(scorer.matchToken(input, candidate)).isEqualTo(MatchQuality.NONE)
        }

        @Test
        fun `tokensMatch should return true for any matched quality`() {
            assertThat(scorer.tokensMatch("user", "user")).isTrue()
            assertThat(scorer.tokensMatch("sec", "seconds")).isTrue()
            assertThat(scorer.tokensMatch("requst", "request")).isTrue()
        }

        @Test
        fun `tokensMatch should return false for NONE quality`() {
            assertThat(scorer.tokensMatch("user", "account")).isFalse()
            assertThat(scorer.tokensMatch("ab", "cd")).isFalse()
        }
    }

    @Nested
    inner class ScoringWithUniformWeights {
        @Test
        fun `should return 1 for identical token lists`() {
            val tokens = listOf("user", "name")
            assertThat(scorer.score(tokens, tokens, uniformWeights)).isEqualTo(1.0)
        }

        @Test
        fun `should return 0 for empty input tokens`() {
            assertThat(scorer.score(emptyList(), listOf("user"), uniformWeights)).isEqualTo(0.0)
        }

        @Test
        fun `should return 0 for empty candidate tokens`() {
            assertThat(scorer.score(listOf("user"), emptyList(), uniformWeights)).isEqualTo(0.0)
        }

        @Test
        fun `should return 0 for both empty`() {
            assertThat(scorer.score(emptyList(), emptyList(), uniformWeights)).isEqualTo(0.0)
        }

        @Test
        fun `should return 0 for completely different tokens`() {
            assertThat(scorer.score(listOf("xxxx", "yyyy"), listOf("user", "name"), uniformWeights)).isEqualTo(0.0)
        }

        @Test
        fun `should handle partial matches with F1 scoring`() {
            val score = scorer.score(listOf("user"), listOf("user", "name"), uniformWeights)
            assertThat(score).isBetween(0.65, 0.68)
        }

        @Test
        fun `should be symmetric for identical token counts`() {
            val input = listOf("user", "account")
            val candidate = listOf("user", "name")
            val score1 = scorer.score(input, candidate, uniformWeights)
            val score2 = scorer.score(candidate, input, uniformWeights)
            assertThat(score1).isEqualTo(score2)
        }
    }

    @Nested
    inner class ScoringWithNonUniformWeights {
        @Test
        fun `should weight rare tokens higher`() {
            val weights = mapOf("http" to 1.5, "stub" to 4.0, "id" to 3.5)
            val inputTokens = listOf("stub", "id")
            val candidateTokens = listOf("http", "stub", "id")
            val score = scorer.score(inputTokens, candidateTokens, weights)
            assertThat(score).isGreaterThan(0.75)
        }

        @Test
        fun `should penalize missing heavy tokens more`() {
            val weights = mapOf("user" to 4.0, "name" to 4.0, "the" to 1.0)
            val scoreMissingHeavy = scorer.score(listOf("user", "the"), listOf("user", "name"), weights)
            val scoreMissingLight = scorer.score(listOf("user", "name"), listOf("user", "name", "the"), weights)
            assertThat(scoreMissingLight).isGreaterThan(scoreMissingHeavy)
        }

        @Test
        fun `should use default weight for unknown tokens`() {
            val weights = mapOf("user" to 2.0)
            val score = scorer.score(listOf("user", "unknown"), listOf("user", "unknown"), weights)
            assertThat(score).isEqualTo(1.0)
        }
    }

    @Nested
    inner class FuzzyTokenMatching {
        @Test
        fun `should match tokens with typos in scoring`() {
            val input = listOf("usre", "nmae")
            val candidate = listOf("user", "name")
            val score = scorer.score(input, candidate, uniformWeights)
            assertThat(score).isEqualTo(1.0)
        }

        @Test
        fun `should match abbreviated tokens via prefix`() {
            val input = listOf("auth", "resp")
            val candidate = listOf("authentication", "response")
            val weights = mapOf("authentication" to 1.0, "response" to 1.0)
            val score = scorer.score(input, candidate, weights)
            assertThat(score).isEqualTo(1.0)
        }
    }

    @Nested
    inner class EdgeCases {
        @Test
        fun `should handle single token lists`() {
            val score = scorer.score(listOf("user"), listOf("user"), uniformWeights)
            assertThat(score).isEqualTo(1.0)
        }

        @Test
        fun `should handle duplicate tokens in input`() {
            val input = listOf("user", "user")
            val candidate = listOf("user", "name")
            val score = scorer.score(input, candidate, uniformWeights)
            assertThat(score).isGreaterThan(0.0).isLessThan(1.0)
        }

        @Test
        fun `should not reuse matched tokens`() {
            val input = listOf("user")
            val candidate = listOf("user", "user")
            val score = scorer.score(input, candidate, uniformWeights)
            assertThat(score).isBetween(0.65, 0.68)
        }
    }
}