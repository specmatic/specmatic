package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class PatternKtTest {
    @Test
    fun `patternFromValueUsing should fallback to parseValueToType for non-string values`() {
        val result = patternFromValueUsing(StringPattern(), NumberValue(10), Resolver()) { it.deepPattern() }

        assertThat(result).isEqualTo(NumberPattern())
    }

    @Test
    fun `patternFromValueUsing should fallback to parseValueToType for non-token strings`() {
        val result = patternFromValueUsing(StringPattern(), StringValue("hello"), Resolver()) { it.deepPattern() }

        assertThat(result).isEqualTo(StringPattern())
    }

    @Test
    fun `patternFromValueUsing should allow exactMatchElseType override for non-string values`() {
        val result = patternFromValueUsing(StringPattern(), NumberValue(10), Resolver()) { it.exactMatchElseType() }

        assertThat(result).isEqualTo(ExactValuePattern(NumberValue(10)))
    }

    @Test
    fun `patternFromValueUsing should allow exactMatchElseType override for non-token strings`() {
        val result = patternFromValueUsing(StringPattern(), StringValue("hello"), Resolver()) { it.exactMatchElseType() }

        assertThat(result).isEqualTo(ExactValuePattern(StringValue("hello")))
    }
}
