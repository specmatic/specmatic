package io.specmatic.core.value

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.DeferredPattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.optionalPattern
import io.specmatic.shouldMatch
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

internal class StringValueTest {
    @Test
    fun `should generate pattern matching an empty string from pattern with question suffix`() {
        val pattern = DeferredPattern("(string?)").resolvePattern(Resolver())

        val constructedPattern = optionalPattern(StringPattern())

        assertThat(pattern.encompasses(constructedPattern, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)

        StringValue("data") shouldMatch  pattern
        EmptyString shouldMatch  pattern
    }

    @Test
    fun `value to be shown in a snippet should be quoted but type not mentioned`() {
        assertThat(StringValue("test").valueErrorSnippet()).isEqualTo("\"test\"")
    }

    @Nested
    inner class ToStringLiteralWithXmlFlagTests {

        @Test
        fun `should return plain string when xml flag is false`() {
            val stringValue = StringValue("<data>test</data>", xml = false)

            assertThat(stringValue.toStringLiteral()).isEqualTo("<data>test</data>")
        }

        @Test
        fun `should escape XML entities when xml flag is true`() {
            val stringValue = StringValue("<data>test</data>", xml = true)

            assertThat(stringValue.toStringLiteral()).isEqualTo("&lt;data&gt;test&lt;/data&gt;")
        }

        @ParameterizedTest
        @CsvSource(
            "'<', '&lt;'",
            "'>', '&gt;'",
            "'&', '&amp;'",
            "'\"', '&quot;'",
            "'''', '&apos;'",
            delimiter = ','
        )
        fun `should escape individual XML special characters`(input: String, expected: String) {
            val stringValue = StringValue(input, xml = true)

            assertThat(stringValue.toStringLiteral()).isEqualTo(expected)
        }

        @Test
        fun `should escape complete XML tag with attributes`() {
            val stringValue = StringValue("<person name=\"John\" age='30'>Content</person>", xml = true)

            val result = stringValue.toStringLiteral()
            assertThat(result).contains("&lt;person")
            assertThat(result).contains("name=&quot;John&quot;")
            assertThat(result).contains("age=&apos;30&apos;")
            assertThat(result).contains("&gt;Content&lt;/person&gt;")
        }

        @Test
        fun `should escape nested XML structure`() {
            val stringValue = StringValue("<root><child attr=\"value\">text & more</child></root>", xml = true)

            val result = stringValue.toStringLiteral()
            assertThat(result).contains("&lt;root&gt;")
            assertThat(result).contains("&lt;child")
            assertThat(result).contains("attr=&quot;value&quot;")
            assertThat(result).contains("text &amp; more")
            assertThat(result).contains("&lt;/child&gt;")
            assertThat(result).contains("&lt;/root&gt;")
        }

        @Test
        fun `should escape already escaped entities`() {
            val stringValue = StringValue("&lt;tag&gt;", xml = true)

            assertThat(stringValue.toStringLiteral()).isEqualTo("&amp;lt;tag&amp;gt;")
        }
    }
}
