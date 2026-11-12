package io.specmatic.core.utilities

import io.ktor.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

internal class ResponseMediaTypeMatcherTest {
    @Nested
    inner class ExactMediaTypeMatching {
        @ParameterizedTest
        @CsvSource(
            "application/json, application/json",
            "Application/JSON, application/json",
            "application/foo+custom, application/foo+custom",
            "application/*, application/*",
            "*/*, */*",
        )
        fun `matches identical media types and valid wildcard ranges`(previous: String, current: String) {
            assertCanCompare(previous, current, expected = true)
        }
    }

    @Nested
    inner class SupportedStructuredSyntaxFamilies {
        @ParameterizedTest
        @CsvSource(
            "application/json, application/problem+json",
            "application/problem+json, application/json",
            "application/json, application/vnd.api+json",
            "application/vnd.api+json, application/json",
            "application/vnd.a+json, application/vnd.b+json",
            "application/vnd.b+json, application/vnd.a+json",
            "application/xml, application/soap+xml",
            "application/soap+xml, application/xml",
            "application/yaml, application/vnd.example+yaml",
            "application/vnd.example+yaml, application/yaml",
            "APPLICATION/JSON, application/PROBLEM+JSON",
            "application/vnd.api+JSON, application/JSON",
        )
        fun `matches supported structured syntax families in either direction`(previous: String, current: String) {
            assertCanCompare(previous, current, expected = true)
        }
    }

    @Nested
    inner class IncompatibleMediaTypes {
        @ParameterizedTest
        @CsvSource(
            "application/json, application/xml",
            "application/json, text/foo+json",
            "application/foo+custom, application/custom",
            "application/foo+custom, application/foo+other",
            "application/foo+json, application/foo+xml",
        )
        fun `does not match media types with incompatible formats or types`(previous: String, current: String) {
            assertCanCompare(previous, current, expected = false)
        }
    }

    @Nested
    inner class WildcardHandling {
        @ParameterizedTest
        @CsvSource("application/*", "*/*")
        fun `matches identical valid wildcard ranges`(mediaType: String) {
            assertCanCompare(mediaType, mediaType, expected = true)
        }

        @ParameterizedTest
        @CsvSource(
            "application/*, image/*",
            "*/*, application/*",
            "application/*, */*",
            "application/*, application/json",
            "application/json, application/*",
            "*/*, application/json",
            "application/json, */*",
        )
        fun `does not match different wildcard ranges or wildcard and concrete types`(previous: String, current: String) {
            assertCanCompare(previous, current, expected = false)
        }

        @ParameterizedTest
        @ValueSource(strings = ["a*/json", "application/*+json", "*/json", "application/j*son"])
        fun `rejects invalid wildcard syntax even when both values are identical`(mediaType: String) {
            assertCanCompare(mediaType, mediaType, expected = false)
        }
    }

    @Nested
    inner class ConfigurableSupportedFormats {
        @ParameterizedTest
        @CsvSource(
            "application/json, application/problem+json",
            "application/xml, application/soap+xml",
            "application/yaml, application/vnd.example+yaml",
        )
        fun `uses json xml and yaml by default`(previous: String, current: String) {
            assertCanCompare(previous, current, expected = true)
        }

        @Test
        fun `explicit supported formats replace the defaults`() {
            val matcher = ResponseMediaTypeMatcher(setOf("cbor"))
            assertCanCompare("application/json", "application/problem+json", expected = false, matcher = matcher)
            assertCanCompare("application/cbor", "application/vnd.example+cbor", expected = true, matcher = matcher)
        }

        @Test
        fun `empty supported formats disable structured syntax matching but preserve exact matching`() {
            val matcher = ResponseMediaTypeMatcher(emptySet())
            assertCanCompare("application/json", "application/problem+json", expected = false, matcher = matcher)
            assertCanCompare("application/foo+custom", "application/foo+custom", expected = true, matcher = matcher)
        }

        @Test
        fun `trims and normalizes supported format entries`() {
            val matcher = ResponseMediaTypeMatcher(setOf(" JSON ", " Xml ", " YAML "))
            assertCanCompare("application/json", "application/problem+json", expected = true, matcher = matcher)
            assertCanCompare("application/xml", "application/soap+xml", expected = true, matcher = matcher)
            assertCanCompare("application/yaml", "application/vnd.example+yaml", expected = true, matcher = matcher)
        }

        @Test
        fun `normalizes duplicate supported formats`() {
            val matcher = ResponseMediaTypeMatcher(setOf("json", " JSON ", "json "))
            assertCanCompare("application/json", "application/problem+json", expected = true, matcher = matcher)
        }

        @Test
        fun `supports additional custom formats when supplied`() {
            val matcher = ResponseMediaTypeMatcher(setOf("json", "xml", "yaml", "cbor"))
            assertCanCompare("application/cbor", "application/vnd.example+cbor", expected = true, matcher = matcher)
            assertCanCompare("application/json", "application/problem+json", expected = true, matcher = matcher)
        }

        @Test
        fun `does not recognize an unknown suffix unless it is configured`() {
            val defaultMatcher = ResponseMediaTypeMatcher()
            val configuredMatcher = ResponseMediaTypeMatcher(setOf("custom"))
            assertCanCompare("application/foo+custom", "application/custom", expected = false, matcher = defaultMatcher)
            assertCanCompare("application/foo+custom", "application/custom", expected = true, matcher = configuredMatcher)
        }

        @Test
        fun `exact media type matching is independent of supported formats`() {
            val matcher = ResponseMediaTypeMatcher(emptySet())
            assertCanCompare("application/foo+custom", "application/foo+custom", expected = true, matcher = matcher)
            assertCanCompare("application/custom", "application/custom", expected = true, matcher = matcher)
        }
    }

    @Nested
    @Execution(ExecutionMode.SAME_THREAD)
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    inner class SystemPropertyConfiguration {
        @Test
        fun `reads configured formats from the system property through Flags`() {
            withSupportedFormatsProperty("cbor") {
                val matcher = ResponseMediaTypeMatcher()
                assertCanCompare("application/json", "application/problem+json", expected = false, matcher = matcher)
                assertCanCompare("application/cbor", "application/vnd.example+cbor", expected = true, matcher = matcher)
            }
        }

        @Test
        fun `uses defaults when the system property is missing`() {
            withSupportedFormatsProperty(null) {
                assertCanCompare("application/json", "application/problem+json", expected = true)
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "   ", ", ,"])
        fun `uses defaults when the system property contains no formats`(configuredFormats: String) {
            withSupportedFormatsProperty(configuredFormats) {
                assertCanCompare("application/json", "application/problem+json", expected = true)
            }
        }

        @Test
        fun `restores the previous system property after each configuration`() {
            val propertyName = ResponseMediaTypeMatcher.FORMATS_PROPERTY
            val originalValue = System.getProperty(propertyName)

            withSupportedFormatsProperty("cbor") {}
            assertThat(System.getProperty(propertyName)).isEqualTo(originalValue)
        }
    }

    private fun assertCanCompare(
        previous: String,
        current: String,
        expected: Boolean,
        matcher: ResponseMediaTypeMatcher = ResponseMediaTypeMatcher(),
    ) {
        val previous = ContentType.parse(previous)
        val current = ContentType.parse(current)
        assertThat(matcher.canCompareSchemas(previous, current)).isEqualTo(expected)
    }

    private fun withSupportedFormatsProperty(value: String?, action: () -> Unit) {
        val propertyName = ResponseMediaTypeMatcher.FORMATS_PROPERTY
        val originalValue = System.getProperty(propertyName)
        try {
            if (value == null) System.clearProperty(propertyName) else System.setProperty(propertyName, value)
            action()
        } finally {
            if (originalValue == null) System.clearProperty(propertyName) else System.setProperty(propertyName, originalValue)
        }
    }
}
