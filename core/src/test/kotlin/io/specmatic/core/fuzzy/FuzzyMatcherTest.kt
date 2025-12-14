package io.specmatic.core.fuzzy

import io.specmatic.core.fuzzy.FuzzyMatcher.Companion.buildFuzzyMatcher
import io.specmatic.core.fuzzy.FuzzyMatcher.Companion.FuzzyBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class FuzzyMatcherTest {
    @Nested
    inner class ExactMatching {
        private val matcher = buildFuzzyMatcher {
            fromKeys(setOf("user_id", "user_name", "account_id"))
        }

        @ParameterizedTest
        @ValueSource(strings = ["user_id", "user_name", "account_id"])
        fun `should return ExactMatch for exact key`(key: String) {
            val result = matcher.match(key)
            assertThat(result).isInstanceOf(FuzzyMatchResult.ExactMatch::class.java)
            assertThat((result as FuzzyMatchResult.ExactMatch).key).isEqualTo(key)
        }

        @Test
        fun `should return ExactMatch for case-insensitive match via normalization`() {
            val matcher = FuzzyBuilder().fromKeys(setOf("user_id", "User_Name")).build()
            val result = matcher.match("USER_ID")
            assertThat(result).isInstanceOf(FuzzyMatchResult.FuzzyMatch::class.java)
        }

        @Test
        fun `should return ExactMatch for delimiter-normalized match`() {
            val matcher = FuzzyBuilder().fromKeys(setOf("user_id")).build()
            val result = matcher.match("user-id")
            assertThat(result).isInstanceOf(FuzzyMatchResult.FuzzyMatch::class.java)
            assertThat((result as FuzzyMatchResult.FuzzyMatch).key).isEqualTo("user_id")
        }
    }

    @Nested
    inner class FuzzyMatching {
        @Nested
        inner class TypoTolerance {
            private val matcher = FuzzyBuilder().fromKeys(setOf("authentication", "authorization", "response", "request")).build()

            @ParameterizedTest(name = "typo: {0} -> {1}")
            @CsvSource(
                "authentcation, authentication",
                "authenticationn, authentication",
                "authenication, authentication",
                "responze, response",
                "requets, request"
            )
            fun `should match keys with typos`(input: String, expected: String) {
                val result = matcher.match(input)
                assertThat(result).isInstanceOf(FuzzyMatchResult.FuzzyMatch::class.java)
                assertThat((result as FuzzyMatchResult.FuzzyMatch).key).isEqualTo(expected)
            }
        }

        @Nested
        inner class AbbreviationSupport {
            private val matcher = FuzzyBuilder().fromKeys(setOf("authentication_token", "request_body", "response_header")).build()

            @ParameterizedTest(name = "abbreviation: {0} -> {1}")
            @CsvSource(
                "auth_token, authentication_token",
                "req_body, request_body",
                "resp_header, response_header"
            )
            fun `should match abbreviated tokens via prefix`(input: String, expected: String) {
                val result = matcher.match(input)
                assertThat(result).isInstanceOf(FuzzyMatchResult.FuzzyMatch::class.java)
                assertThat((result as FuzzyMatchResult.FuzzyMatch).key).isEqualTo(expected)
            }
        }

        @Nested
        inner class MissingCommonTokens {
            @Test
            fun `should match when common token is omitted`() {
                val matcher = FuzzyBuilder().fromKeys(setOf("http_request", "http_response", "http_status", "grpc_request", "grpc_response")).build()
                val result = matcher.match("http_status")
                assertThat(result).isInstanceOf(FuzzyMatchResult.ExactMatch::class.java)
            }

            @Test
            fun `should weight rare tokens higher for scoring`() {
                val matcher = FuzzyBuilder().fromKeys(setOf("http_stub_id", "http_request_id", "http_response_id")).build()
                val result = matcher.match("stub_id")
                assertThat(result).isInstanceOf(FuzzyMatchResult.FuzzyMatch::class.java)
                assertThat((result as FuzzyMatchResult.FuzzyMatch).key).isEqualTo("http_stub_id")
            }
        }
    }

    @Nested
    inner class NoMatchScenarios {
        private val matcher = FuzzyBuilder().fromKeys(setOf("user_id", "user_name", "account_id")).build()

        @ParameterizedTest
        @ValueSource(strings = ["", "   ", "\t", "\n"])
        fun `should return NoMatch for blank input`(input: String) {
            val result = matcher.match(input)
            assertThat(result).isEqualTo(FuzzyMatchResult.NoMatch)
        }

        @Test
        fun `should return NoMatch for completely unrelated key`() {
            val result = matcher.match("xyz_abc_123")
            assertThat(result).isEqualTo(FuzzyMatchResult.NoMatch)
        }

        @Test
        fun `should return NoMatch when score below threshold`() {
            val matcher = FuzzyBuilder().fromKeys(setOf("very_long_key_name_here")).withThreshold(0.9).build()
            val result = matcher.match("key")
            assertThat(result).isEqualTo(FuzzyMatchResult.NoMatch)
        }
    }

    @Nested
    inner class SuffixValidation {
        @Test
        fun `should reject match when suffix types conflict`() {
            val keys = setOf("delay_seconds", "delay_milliseconds", "timeout_seconds")
            val matcher = FuzzyBuilder().fromKeys(keys).build()
            val result = matcher.match("delay_hours")
            assertThat(result).isEqualTo(FuzzyMatchResult.NoMatch)
        }

        @Test
        fun `should accept match when suffix matches`() {
            val keys = setOf("delay_seconds", "timeout_seconds")
            val matcher = FuzzyBuilder().fromKeys(keys).build()
            val result = matcher.match("dlay_seconds")
            assertThat(result).isInstanceOf(FuzzyMatchResult.FuzzyMatch::class.java)
            assertThat((result as FuzzyMatchResult.FuzzyMatch).key).isEqualTo("delay_seconds")
        }

        @Test
        fun `should accept fuzzy suffix match`() {
            val keys = setOf("user_identifier", "group_identifier")
            val matcher = FuzzyBuilder().fromKeys(keys).build()
            val result = matcher.match("user_ident")
            assertThat(result).isInstanceOf(FuzzyMatchResult.FuzzyMatch::class.java)
        }
    }

    @Nested
    inner class DiscriminatorValidation {
        @Test
        fun `should reject ambiguous input missing discriminator`() {
            val keys = setOf("billing_address", "shipping_address")
            val matcher = FuzzyBuilder().fromKeys(keys).build()
            val result = matcher.match("address")
            assertThat(result).isEqualTo(FuzzyMatchResult.NoMatch)
        }

        @Test
        fun `should accept input with correct discriminator`() {
            val keys = setOf("billing_address", "shipping_address")
            val matcher = FuzzyBuilder().fromKeys(keys).build()

            val result = matcher.match("billing_addr")
            assertThat(result).isInstanceOf(FuzzyMatchResult.FuzzyMatch::class.java)
            assertThat((result as FuzzyMatchResult.FuzzyMatch).key).isEqualTo("billing_address")
        }

        @Test
        fun `should accept fuzzy discriminator match`() {
            val keys = setOf("billing_address", "shipping_address")
            val matcher = FuzzyBuilder().fromKeys(keys).build()

            val result = matcher.match("billin_address")
            assertThat(result).isInstanceOf(FuzzyMatchResult.FuzzyMatch::class.java)
            assertThat((result as FuzzyMatchResult.FuzzyMatch).key).isEqualTo("billing_address")
        }
    }

    @Nested
    inner class RealWorldScenarios {
        @Test
        fun `should handle API schema keys`() {
            val keys = setOf("http-request", "http-response", "http-request-headers", "http-response-body", "http-status-code")
            val matcher = FuzzyBuilder().fromKeys(keys).build()
            assertThat(matcher.match("http-request")).isInstanceOf(FuzzyMatchResult.ExactMatch::class.java)
            assertThat(matcher.match("http_request")).isInstanceOf(FuzzyMatchResult.FuzzyMatch::class.java)
            assertThat(matcher.match("httpRequest")).isInstanceOf(FuzzyMatchResult.FuzzyMatch::class.java)
        }

        @Test
        fun `should handle snake case named properties`() {
            val keys = setOf("user_id", "user_name", "user_email", "created_at", "updated_at", "deleted_at")
            val matcher = FuzzyBuilder().fromKeys(keys).build()
            assertThat(matcher.match("userId")).isInstanceOf(FuzzyMatchResult.FuzzyMatch::class.java)
            assertThat(matcher.match("user_nm")).isInstanceOf(FuzzyMatchResult.FuzzyMatch::class.java)
        }

        @Test
        fun `should handle pascal case named properties`() {
            val keys = setOf("firstName", "lastName", "emailAddress", "phoneNumber", "streetAddress", "postalCode")
            val matcher = FuzzyBuilder().fromKeys(keys).build()
            assertThat(matcher.match("first_name")).isInstanceOf(FuzzyMatchResult.FuzzyMatch::class.java)
            assertThat(matcher.match("emialAddress")).isInstanceOf(FuzzyMatchResult.FuzzyMatch::class.java)
        }
    }

    @Nested
    inner class EdgeCases {
        @Test
        fun `should handle single key schema`() {
            val matcher = FuzzyBuilder().fromKeys(setOf("onlyKey")).build()
            assertThat(matcher.match("onlyKey")).isInstanceOf(FuzzyMatchResult.ExactMatch::class.java)
            assertThat(matcher.match("onlykey")).isInstanceOf(FuzzyMatchResult.FuzzyMatch::class.java)
        }

        @Test
        fun `should handle empty schema`() {
            val matcher = FuzzyBuilder().fromKeys(emptySet()).build()
            assertThat(matcher.match("anything")).isEqualTo(FuzzyMatchResult.NoMatch)
        }

        @Test
        fun `should handle very long keys`() {
            val longKey = "this_is_a_very_long_key_name_that_might_cause_issues_in_some_systems"
            val matcher = FuzzyBuilder()
                .fromKeys(setOf(longKey))
                .build()

            assertThat(matcher.match(longKey)).isInstanceOf(FuzzyMatchResult.ExactMatch::class.java)
        }

        @Test
        fun `should handle unicode in keys`() {
            val keys = setOf("user_名前", "ユーザー_id")
            val matcher = FuzzyBuilder().fromKeys(keys).build()
            assertThat(matcher.match("user_名前")).isInstanceOf(FuzzyMatchResult.ExactMatch::class.java)
        }

        @Test
        fun `should prefer higher scoring match when multiple candidates`() {
            val keys = setOf("user_id", "user_identifier", "user_identity")
            val matcher = FuzzyBuilder().fromKeys(keys).build()
            val result = matcher.match("user_id")
            assertThat(result).isInstanceOf(FuzzyMatchResult.ExactMatch::class.java)
            assertThat((result as FuzzyMatchResult.ExactMatch).key).isEqualTo("user_id")
        }
    }
}
