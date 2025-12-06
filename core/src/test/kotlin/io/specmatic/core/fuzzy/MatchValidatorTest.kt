package io.specmatic.core.fuzzy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class MatchValidatorTest {
    private val scorer = WeightedSimilarityScorer()

    @Nested
    inner class DiscriminatorValidatorTests {
        private val validator = DiscriminatorValidator()

        @Test
        fun `should return true when no discriminators defined for candidate`() {
            val context = createContext(inputTokens = listOf("user"), candidateKey = "user_id", candidateTokens = listOf("user", "id"), keyDiscriminators = emptyMap())
            assertThat(validator.isValid(context)).isTrue()
        }

        @Test
        fun `should return true when all required discriminator tokens are present`() {
            val context = createContext(
                inputTokens = listOf("billing", "address"),
                candidateKey = "billing_address",
                candidateTokens = listOf("billing", "address"),
                keyDiscriminators = mapOf("billing_address" to setOf("billing"))
            )
            assertThat(validator.isValid(context)).isTrue()
        }

        @Test
        fun `should return false when required discriminator token is missing`() {
            val context = createContext(
                inputTokens = listOf("address"),
                candidateKey = "billing_address",
                candidateTokens = listOf("billing", "address"),
                keyDiscriminators = mapOf("billing_address" to setOf("billing"))
            )
            assertThat(validator.isValid(context)).isFalse()
        }

        @Test
        fun `should accept fuzzy match for discriminator token`() {
            val context = createContext(
                inputTokens = listOf("billin", "address"),  // typo in billing
                candidateKey = "billing_address",
                candidateTokens = listOf("billing", "address"),
                keyDiscriminators = mapOf("billing_address" to setOf("billing"))
            )
            assertThat(validator.isValid(context)).isTrue()
        }

        @Test
        fun `should require all discriminator tokens when multiple defined`() {
            val context = createContext(
                inputTokens = listOf("primary", "address"),
                candidateKey = "primary_billing_address",
                candidateTokens = listOf("primary", "billing", "address"),
                keyDiscriminators = mapOf("primary_billing_address" to setOf("primary", "billing"))
            )
            assertThat(validator.isValid(context)).isFalse()
        }

        @Test
        fun `should pass when all multiple discriminators are present`() {
            val context = createContext(
                inputTokens = listOf("primary", "billing", "address"),
                candidateKey = "primary_billing_address",
                candidateTokens = listOf("primary", "billing", "address"),
                keyDiscriminators = mapOf("primary_billing_address" to setOf("primary", "billing"))
            )
            assertThat(validator.isValid(context)).isTrue()
        }
    }

    @Nested
    inner class SuffixValidatorTests {
        private val validator = SuffixValidator()

        @Test
        fun `should return true when candidate has no suffix`() {
            val context = createContext(
                inputTokens = emptyList(),
                candidateKey = "",
                candidateTokens = emptyList(),
                strictSuffixes = setOf("id")
            )
            assertThat(validator.isValid(context)).isTrue()
        }

        @Test
        fun `should return true when candidate suffix is not in strict suffixes`() {
            val context = createContext(
                inputTokens = listOf("user", "data"),
                candidateKey = "user_data",
                candidateTokens = listOf("user", "data"),
                strictSuffixes = setOf("id", "name")
            )
            assertThat(validator.isValid(context)).isTrue()
        }

        @Test
        fun `should return true when input suffix matches candidate strict suffix`() {
            val context = createContext(
                inputTokens = listOf("user", "id"),
                candidateKey = "user_id",
                candidateTokens = listOf("user", "id"),
                strictSuffixes = setOf("id")
            )
            assertThat(validator.isValid(context)).isTrue()
        }

        @Test
        fun `should return false when input has no tokens but candidate has strict suffix`() {
            val context = createContext(
                inputTokens = emptyList(),
                candidateKey = "user_id",
                candidateTokens = listOf("user", "id"),
                strictSuffixes = setOf("id")
            )
            assertThat(validator.isValid(context)).isFalse()
        }

        @Test
        fun `should return false when input suffix does not match candidate strict suffix`() {
            val context = createContext(
                inputTokens = listOf("user", "name"),
                candidateKey = "user_id",
                candidateTokens = listOf("user", "id"),
                strictSuffixes = setOf("id", "name")
            )
            assertThat(validator.isValid(context)).isFalse()
        }

        @Test
        fun `should accept fuzzy match for suffix`() {
            val context = createContext(
                inputTokens = listOf("user", "ids"),
                candidateKey = "user_ids",
                candidateTokens = listOf("user", "id"),
                strictSuffixes = setOf("id")
            )
            assertThat(validator.isValid(context)).isTrue()
        }

        @ParameterizedTest(name = "suffix mismatch: input ends with {0}, candidate ends with {1}")
        @CsvSource(
            "name, id",
            "count, timestamp",
            "seconds, milliseconds"
        )
        fun `should reject mismatched strict suffixes`(inputSuffix: String, candidateSuffix: String) {
            val context = createContext(
                inputTokens = listOf("delay", inputSuffix),
                candidateKey = "delay_$candidateSuffix",
                candidateTokens = listOf("delay", candidateSuffix),
                strictSuffixes = setOf(inputSuffix, candidateSuffix)
            )
            assertThat(validator.isValid(context)).isFalse()
        }
    }

    private fun createContext(
        inputTokens: List<String>,
        candidateKey: String,
        candidateTokens: List<String>,
        strictSuffixes: Set<String> = emptySet(),
        keyDiscriminators: Map<String, Set<String>> = emptyMap()
    ): ValidationContext {
        val model = FuzzyModel(validKeys = setOf(candidateKey), tokenWeights = emptyMap(), strictSuffixes = strictSuffixes, keyDiscriminators = keyDiscriminators)
        return ValidationContext(inputTokens.joinToString(), inputTokens, candidateKey, candidateTokens, model, scorer)
    }
}
