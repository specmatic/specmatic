package io.specmatic.conversions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

class TemplateTokenizerTest {
    @ParameterizedTest
    @ValueSource(strings = [""])
    fun `returns empty tokens for empty input`(input: String) {
        assertThat(TemplateTokenizer(TemplateTokenizer.openApiPathRegex).tokenize(input)).isEmpty()
    }

    @ParameterizedTest
    @MethodSource("noPlaceholderInputs")
    fun `returns a single literal when input has no placeholders`(input: String) {
        val tokens = TemplateTokenizer(TemplateTokenizer.openApiPathRegex).tokenize(input)
        assertThat(tokens).containsExactly(TemplateSegment(startIndex = 0, endIndex = input.lastIndex, token = input, type = SegmentType.TEXT))
        assertTokenCoverage(input, tokens)
    }

    @Test
    fun `extract returns empty list when regex does not match entire input`() {
        val tokenizer = TemplateTokenizer(Regex("^/product/(.+)/latest$"))
        val parts = tokenizer.extract("/products/12/orders/abc/latest")
        assertThat(parts).isEmpty()
    }

    @Test
    fun `extract returns list of segments based on the regex provided`() {
        val tokenizer = TemplateTokenizer(Regex("^/users/([A-Za-z0-9_-]+)/orders/([A-Za-z0-9_-]+)$"))
        val input = "/users/u-1/orders/o_2"
        val parts = tokenizer.extract(input)
        assertThat(parts).containsExactly("/users/", "u-1", "/orders/", "o_2")
        assertThat(parts.joinToString("")).isEqualTo(input)
    }

    @Test
    fun `extract does not produce empty literals between adjacent captures`() {
        val tokenizer = TemplateTokenizer(Regex("^/(.+)(.+)/pets$"))
        val input = "/ab/pets"
        val parts = tokenizer.extract(input)
        assertThat(parts).doesNotContain("")
        assertThat(parts.joinToString("")).isEqualTo(input)
    }

    @Nested
    inner class OpenApiTokenizer {
        private val tokenizer = TemplateTokenizer(TemplateTokenizer.openApiPathRegex)

        @ParameterizedTest
        @MethodSource("io.specmatic.conversions.TemplateTokenizerTest#openApiOnlyVariableInputs")
        fun `tokenizes input containing only a placeholder`(input: String, expectedName: String) {
            val tokens = tokenizer.tokenize(input)
            assertThat(tokens).containsExactly(TemplateSegment(startIndex = 0, endIndex = input.lastIndex, token = expectedName, type = SegmentType.PLACEHOLDER))
            assertTokenCoverage(input, tokens)
        }

        @ParameterizedTest
        @ValueSource(strings = ["/pets/{petId", "/pets/petId}", "/pets/{}"])
        fun `treats unmatched or invalid brace patterns as literal text`(input: String) {
            val tokens = tokenizer.tokenize(input)
            assertThat(tokens).containsExactly(TemplateSegment(startIndex = 0, endIndex = input.lastIndex, token = input, type = SegmentType.TEXT))
            assertTokenCoverage(input, tokens)
        }

        @ParameterizedTest
        @MethodSource("io.specmatic.conversions.TemplateTokenizerTest#openApiPathCases")
        fun `tokenizes openapi placeholders in paths`(input: String, expectedPlaceholders: List<String>, expectedTextSegments: List<String>) {
            val tokens = tokenizer.tokenize(input)
            assertThat(tokens.filter { it.type == SegmentType.PLACEHOLDER }.map { it.token }).containsExactlyElementsOf(expectedPlaceholders)
            assertThat(tokens.filter { it.type == SegmentType.TEXT }.map { it.token }).containsExactlyElementsOf(expectedTextSegments)
            assertTokenCoverage(input, tokens)
        }

        @Test
        fun `does not create empty TEXT segments for adjacent placeholders`() {
            val input = "/{tenant}{region}/pets"
            val tokens = tokenizer.tokenize(input)
            assertThat(tokens.map { it.type }).containsExactly(SegmentType.TEXT, SegmentType.PLACEHOLDER, SegmentType.PLACEHOLDER, SegmentType.TEXT)
            assertThat(tokens.filter { it.type == SegmentType.TEXT }.map { it.token }).doesNotContain("")
            assertTokenCoverage(input, tokens)
        }

        @Test
        fun `extract splits standard openapi style path`() {
            val extractTokenizer = TemplateTokenizer(Regex("^/product/(?<id>.+)/order/(?<id2>.+)/latest$"))
            val input = "/product/12/order/abc/latest"
            val parts = extractTokenizer.extract(input)
            assertThat(parts).containsExactly("/product/", "12", "/order/", "abc", "/latest")
            assertThat(parts.joinToString("")).isEqualTo(input)
        }

        @Test
        fun `extract splits interpolated path correctly`() {
            val interpolatedTokenizer = TemplateTokenizer(Regex("^/product/product-(?<id>.+)/order/order-(?<id2>.+)/latest$"))
            val input = "/product/product-12/order/order-abc/latest"
            val parts = interpolatedTokenizer.extract(input)
            assertThat(parts).containsExactly("/product/product-", "12", "/order/order-", "abc", "/latest")
            assertThat(parts.joinToString("")).isEqualTo(input)
        }

        @Test
        fun `extract handles capture at start`() {
            val startTokenizer = TemplateTokenizer(Regex("^(?<id>.+)/latest$"))
            val input = "xyz/latest"
            val parts = startTokenizer.extract(input)
            assertThat(parts).containsExactly("xyz", "/latest")
            assertThat(parts.joinToString("")).isEqualTo(input)
        }

        @Test
        fun `extract handles capture at end`() {
            val endTokenizer = TemplateTokenizer(Regex("^/orders/(?<orderId>.+)$"))
            val input = "/orders/999"
            val parts = endTokenizer.extract(input)
            assertThat(parts).containsExactly("/orders/", "999")
            assertThat(parts.joinToString("")).isEqualTo(input)
        }
    }

    @Nested
    inner class CustomTokenizer {
        @ParameterizedTest
        @MethodSource("io.specmatic.conversions.TemplateTokenizerTest#otherSyntaxCases")
        fun `tokenizes placeholders based on provided regex`(tokenizer: TemplateTokenizer, input: String, expectedPlaceholders: List<String>, expectedTextSegments: List<String>) {
            val tokens = tokenizer.tokenize(input)
            assertThat(tokens.filter { it.type == SegmentType.PLACEHOLDER }.map { it.token }).containsExactlyElementsOf(expectedPlaceholders)
            assertThat(tokens.filter { it.type == SegmentType.TEXT }.map { it.token }).containsExactlyElementsOf(expectedTextSegments)
            assertTokenCoverage(input, tokens)
        }
    }

    private fun assertTokenCoverage(input: String, tokens: List<TemplateSegment>) {
        if (input.isEmpty()) {
            assertThat(tokens).isEmpty()
            return
        }

        assertThat(tokens).isNotEmpty

        var cursor = 0
        tokens.forEach { token ->
            assertThat(token.startIndex).isEqualTo(cursor)
            assertThat(token.endIndex).isGreaterThanOrEqualTo(token.startIndex)
            val segment = input.substring(token.startIndex, token.endIndex + 1)

            when (token.type) {
                SegmentType.TEXT -> assertThat(token.token).isEqualTo(segment)
                SegmentType.PLACEHOLDER -> assertThat(segment).contains(token.token)
            }

            cursor = token.endIndex + 1
        }

        assertThat(cursor).isEqualTo(input.length)
    }

    companion object {
        @JvmStatic
        fun noPlaceholderInputs(): List<String> = listOf("/pets/list", "/", "/pets/list/", "/limit-10", "/a/b/c")

        @JvmStatic
        fun openApiOnlyVariableInputs(): List<Arguments> = listOf(
            Arguments.of("{petId}", "petId"),
            Arguments.of("{id}", "id"),
            Arguments.of("{order_id}", "order_id"),
            Arguments.of("{a}", "a")
        )

        @JvmStatic
        fun openApiPathCases(): List<Arguments> = listOf(
            // simple
            Arguments.of("/orders/{orderId}/status", listOf("orderId"), listOf("/orders/", "/status")),
            // multiple placeholders
            Arguments.of("/orders/{orderId}/items/{itemId}/status", listOf("orderId", "itemId"), listOf("/orders/", "/items/", "/status")),
            Arguments.of("/orders/{orderId},{itemId}/status", listOf("orderId", "itemId"), listOf("/orders/", ",", "/status")),
            // placeholder at end
            Arguments.of("/orders/{orderId}", listOf("orderId"), listOf("/orders/")),
            // placeholder at start
            Arguments.of("{orderId}/latest", listOf("orderId"), listOf("/latest")),
            // repeated placeholder name
            Arguments.of("/pets/{id}/owners/{id}", listOf("id", "id"), listOf("/pets/", "/owners/")),
            // braces that are not placeholders should remain literal (no match)
            Arguments.of("/pets/{}/owners", emptyList<String>(), listOf("/pets/{}/owners")),
            // hyphenated literals around placeholders
            Arguments.of("/a-{x}-b/{y}/c-{z}", listOf("x", "y", "z"), listOf("/a-", "-b/", "/c-")),
            // trailing literal only
            Arguments.of("/{tenant}/", listOf("tenant"), listOf("/", "/"))
        )

        @JvmStatic
        fun otherSyntaxCases(): List<Arguments> {
            val colonTokenizer = TemplateTokenizer(Regex(":([A-Za-z_][A-Za-z0-9_]*)"))
            val dollarBraceTokenizer = TemplateTokenizer(Regex("\\$\\{([^}]+)}")) // e.g. ${id}
            return listOf(
                // colon syntax
                Arguments.of(colonTokenizer, "/users/:id/orders/:order_id", listOf("id", "order_id"), listOf("/users/", "/orders/")),
                Arguments.of(colonTokenizer, "/users/:id", listOf("id"), listOf("/users/")),
                Arguments.of(colonTokenizer, "/:tenant:region/pets", listOf("tenant", "region"), listOf("/", "/pets")),
                // ${} syntax
                Arguments.of(dollarBraceTokenizer, $$"/product/product-${id}/order/order-${id}/latest", listOf("id", "id"), listOf("/product/product-", "/order/order-", "/latest")),
                Arguments.of(dollarBraceTokenizer, $$"${id}", listOf("id"), emptyList<String>()),
                Arguments.of(dollarBraceTokenizer, $$"${id}", listOf("id"), emptyList<String>())
            )
        }
    }
}
