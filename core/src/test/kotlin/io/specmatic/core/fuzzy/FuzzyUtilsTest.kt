package io.specmatic.core.fuzzy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.stream.Stream

class FuzzyUtilsTest {
    @Nested
    inner class StringNormalizerTests {
        private val normalizer = StringNormalizer.AlphanumericNormalizer()

        @ParameterizedTest(name = "normalize({0}) = {1}")
        @CsvSource(
            "user_id, userid",
            "user-id, userid",
            "user.id, userid",
            "user id, userid",
            "USER_ID, userid",
            "User-Id, userid",
            "user__id, userid",
            "user---id, userid"
        )
        fun `should remove non-alphanumeric characters and lowercase`(input: String, expected: String) {
            assertThat(normalizer.normalize(input)).isEqualTo(expected)
        }

        @Test
        fun `should preserve numbers`() {
            assertThat(normalizer.normalize("user123")).isEqualTo("user123")
            assertThat(normalizer.normalize("user_123_id")).isEqualTo("user123id")
        }

        @Test
        fun `should handle empty string`() {
            assertThat(normalizer.normalize("")).isEqualTo("")
        }

        @Test
        fun `should handle string with only special characters`() {
            assertThat(normalizer.normalize("___---...")).isEqualTo("")
        }

        @ParameterizedTest
        @ValueSource(strings = ["userId", "USERID", "UserId", "userid", "USER_ID"])
        fun `should normalize different case variations to same result`(input: String) {
            assertThat(normalizer.normalize(input)).isEqualTo("userid")
        }
    }

    @Nested
    inner class StringTokenizerTests {
        private val tokenizer = StringTokenizer.StandardTokenizer()

        @Nested
        inner class DelimiterSplitting {
            @ParameterizedTest(name = "tokenize({0}) = [{1}, {2}]")
            @CsvSource(
                "user_id, user, id",
                "user-id, user, id",
                "user.id, user, id",
                "user id, user, id"
            )
            fun `should split on common delimiters`(input: String, first: String, second: String) {
                assertThat(tokenizer.tokenize(input)).containsExactly(first, second)
            }

            @Test
            fun `should split on multiple consecutive delimiters`() {
                assertThat(tokenizer.tokenize("user__id")).containsExactly("user", "id")
                assertThat(tokenizer.tokenize("user---id")).containsExactly("user", "id")
            }
        }

        @Nested
        inner class CamelCaseSplitting {
            @ParameterizedTest
            @MethodSource("io.specmatic.core.fuzzy.FuzzyUtilsTest#splitTokenCases")
            fun `should split camelCase`(input: String, expected: List<String>) {
                assertThat(tokenizer.tokenize(input)).isEqualTo(expected)
            }

            @Test
            fun `should handle mixed camelCase and delimiters`() {
                assertThat(tokenizer.tokenize("user_firstName")).containsExactly("user", "first", "name")
                assertThat(tokenizer.tokenize("http-requestBody")).containsExactly("http", "request", "body")
            }
        }

        @Nested
        inner class Lowercasing {
            @ParameterizedTest
            @CsvSource(
                "USER_ID, user, id",
                "User_Id, user, id",
                "HTTP_REQUEST, http, request"
            )
            fun `should lowercase all tokens`(input: String, first: String, second: String) {
                assertThat(tokenizer.tokenize(input)).containsExactly(first, second)
            }
        }

        @Nested
        inner class EdgeCases {
            @Test
            fun `should return empty list for empty string`() {
                assertThat(tokenizer.tokenize("")).isEmpty()
            }

            @Test
            fun `should return empty list for only delimiters`() {
                assertThat(tokenizer.tokenize("___")).isEmpty()
                assertThat(tokenizer.tokenize("---")).isEmpty()
                assertThat(tokenizer.tokenize("...")).isEmpty()
            }

            @Test
            fun `should handle single word`() {
                assertThat(tokenizer.tokenize("user")).containsExactly("user")
            }

            @Test
            fun `should preserve numbers in tokens`() {
                assertThat(tokenizer.tokenize("user123")).containsExactly("user123")
                assertThat(tokenizer.tokenize("v2_api")).containsExactly("v2", "api")
            }

            @Test
            fun `should handle numeric-only tokens`() {
                assertThat(tokenizer.tokenize("123")).containsExactly("123")
                assertThat(tokenizer.tokenize("user_123")).containsExactly("user", "123")
            }

            @Test
            fun `should handle long compound keys`() {
                val tokens = tokenizer.tokenize("http_request_body_content_type")
                assertThat(tokens).containsExactly("http", "request", "body", "content", "type")
            }
        }
    }

    companion object {
        @JvmStatic
        fun splitTokenCases(): Stream<Arguments> = Stream.of(
            Arguments.of("userId", listOf("user", "id")),
            Arguments.of("userName", listOf("user", "name")),
            Arguments.of("httpRequest", listOf("http", "request")),
            Arguments.of("XMLParser", listOf("xml", "parser")),
        )
    }
}
