package io.specmatic.core.fuzzy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class FuzzyLearnerTest {
    @Nested
    inner class TokenWeightComputation {
        @Test
        fun `should compute IDF weights for tokens`() {
            val keys = setOf("http_request", "http_response", "http_status", "grpc_request")
            val model = FuzzyLearner(keys).learn()
            assertThat(model.tokenWeights["grpc"]).isGreaterThan(model.tokenWeights["http"])
        }

        @Test
        fun `rare tokens should have higher weight than common tokens`() {
            val keys = setOf(
                "user_id", "user_name", "user_email", "user_phone",
                "account_id", "account_status",
                "unique_field"
            )
            val model = FuzzyLearner(keys).learn()
            assertThat(model.tokenWeights["unique"]).isGreaterThan(model.tokenWeights["user"])
        }

        @Test
        fun `should handle single key`() {
            val keys = setOf("user_id")
            val model = FuzzyLearner(keys).learn()
            assertThat(model.tokenWeights["user"]).isEqualTo(1.0)
            assertThat(model.tokenWeights["id"]).isEqualTo(1.0)
        }

        @Test
        fun `should handle empty keys`() {
            val model = FuzzyLearner(emptySet()).learn()
            assertThat(model.tokenWeights).isEmpty()
        }

        @Test
        fun `should count each token once per key for IDF`() {
            val keys = setOf("test_test", "other_key")
            val model = FuzzyLearner(keys).learn()
            assertThat(model.tokenWeights["test"]).isEqualTo(model.tokenWeights["other"])
        }
    }

    @Nested
    inner class SuffixDetection {
        @Test
        fun `should identify common suffixes appearing at end of multiple keys`() {
            val keys = setOf("user_id", "account_id", "group_id", "user_name", "full_name")
            val model = FuzzyLearner(keys).learn()
            assertThat(model.strictSuffixes).contains("id", "name")
        }

        @Test
        fun `should not include suffix appearing only once`() {
            val keys = setOf("user_id", "account_id", "user_email")
            val model = FuzzyLearner(keys).learn()
            assertThat(model.strictSuffixes).contains("id")
            assertThat(model.strictSuffixes).doesNotContain("email")
        }

        @Test
        fun `should respect minimum frequency ratio for suffixes`() {
            val keys = (1..100).map { "key${it}_id" }.toSet() + setOf("special_type")
            val model = FuzzyLearner(keys, suffixMinFrequencyRatio = 0.05).learn()
            assertThat(model.strictSuffixes).contains("id")
            assertThat(model.strictSuffixes).doesNotContain("type")
        }

        @Test
        fun `should handle keys with single token`() {
            val keys = setOf("id", "name", "user_id", "user_name")
            val model = FuzzyLearner(keys).learn()
            assertThat(model.strictSuffixes).contains("id", "name")
        }
    }

    @Nested
    inner class DiscriminatorDetection {
        @Test
        fun `should identify discriminators for similar keys`() {
            val keys = setOf("ship_address", "shop_address")
            val model = FuzzyLearner(keys).learn()
            assertThat(model.keyDiscriminators["ship_address"]).contains("ship")
            assertThat(model.keyDiscriminators["shop_address"]).contains("shop")
        }

        @Test
        fun `should not create discriminators for dissimilar keys`() {
            val keys = setOf("user_id", "account_name", "http_request")
            val model = FuzzyLearner(keys).learn()
            assertThat(model.keyDiscriminators).isEmpty()
        }

        @Test
        fun `should handle keys with no unique tokens`() {
            val keys = setOf("user_id", "user_id_backup")
            val model = FuzzyLearner(keys).learn()
            assertThat(model.keyDiscriminators["user_id"]).isNullOrEmpty()
            assertThat(model.keyDiscriminators["user_id_backup"]).contains("backup")
        }
    }

    @Nested
    inner class ModelIntegrity {
        @Test
        fun `should preserve all valid keys in model`() {
            val keys = setOf("user_id", "account_name", "http_request")
            val model = FuzzyLearner(keys).learn()
            assertThat(model.validKeys).containsExactlyInAnyOrderElementsOf(keys)
        }

        @Test
        fun `should handle mixed case keys`() {
            val keys = setOf("userId", "USER_ID", "user-id")
            val model = FuzzyLearner(keys).learn()
            assertThat(model.validKeys).containsExactlyInAnyOrderElementsOf(keys)
            assertThat(model.tokenWeights.keys).allMatch { it == it.lowercase() }
        }

        @Test
        fun `should handle special characters in keys`() {
            val keys = setOf("user.id", "user-id", "user_id", "user:id")
            val model = FuzzyLearner(keys).learn()
            assertThat(model.validKeys).hasSize(4)
            assertThat(model.tokenWeights).containsKeys("user", "id")
        }
    }

    @Nested
    inner class ConfigurableThresholds {
        @Test
        fun `should respect custom similarity threshold`() {
            val keys = setOf("user_name", "user_id")
            val modelLowThreshold = FuzzyLearner(keys, similarityThreshold = 0.3).learn()
            val modelHighThreshold = FuzzyLearner(keys, similarityThreshold = 0.9).learn()
            val lowDiscriminators = modelLowThreshold.keyDiscriminators.values.flatten().size
            val highDiscriminators = modelHighThreshold.keyDiscriminators.values.flatten().size
            assertThat(lowDiscriminators).isGreaterThanOrEqualTo(highDiscriminators)
        }
    }
}
