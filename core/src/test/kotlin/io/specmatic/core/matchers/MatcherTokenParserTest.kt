package io.specmatic.core.matchers

import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MatcherTokenParserTest {
    @Test
    fun `should return empty map for blank input`() {
        val properties = parseMatcherTokenProperties("   ")

        assertThat(properties).isEmpty()
    }

    @Test
    fun `should parse matcher token properties`() {
        val properties = parseMatcherTokenProperties("exact: test, times: 2")

        assertThat(properties).containsEntry("exact", StringValue("test"))
        assertThat(properties).containsEntry("times", NumberValue(2))
    }

    @Test
    fun `should preserve regex pattern with commas and colons`() {
        val properties = parseMatcherTokenProperties("pattern: ^https?://[A-Za-z0-9, :/.?-]+$, dataType: string")

        assertThat(properties).containsEntry(RegexMatcher.PATTERN_PROPERTY_KEY, StringValue("^https?://[A-Za-z0-9, :/.?-]+$"))
        assertThat(properties).containsEntry("dataType", StringValue("string"))
    }

    @Test
    fun `should fail for malformed property token with no colon`() {
        assertThatThrownBy { parseMatcherTokenProperties("invalid-token") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Invalid matcher token property 'invalid-token'")
    }

    @Test
    fun `should fail for property token with blank key`() {
        assertThatThrownBy { parseMatcherTokenProperties(": ignored, exact: test") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Invalid matcher token property ': ignored'")
    }
}
