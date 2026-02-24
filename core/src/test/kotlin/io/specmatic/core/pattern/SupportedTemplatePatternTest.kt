package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class SupportedTemplatePatternTest {
    @ParameterizedTest
    @ValueSource(strings = ["\$match(data.id)", "$(string)"])
    fun `supported templates should be treated as valid input by scalar patterns`(template: String) {
        val supportedTemplate = StringValue(template)
        val patterns = listOf(
            Base64StringPattern(),
            BinaryPattern(),
            BooleanPattern(),
            DatePattern,
            DateTimePattern,
            EmailPattern(),
            EnumPattern(listOf(StringValue("one"))),
            NullPattern,
            NumberPattern(),
            StringPattern(),
            TimePattern,
            UUIDPattern
        )

        patterns.forEach { pattern ->
            val result = pattern.matches(supportedTemplate, Resolver())
            assertThat(result)
                .withFailMessage("Expected template $template to pass for ${pattern::class.simpleName}")
                .isInstanceOf(Result.Success::class.java)
        }
    }

    @Test
    fun `malformed matcher template should not bypass regular type checks`() {
        val malformedMatcherTemplate = StringValue("\$match(data.id")

        val result = NumberPattern().matches(malformedMatcherTemplate, Resolver())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }
}
