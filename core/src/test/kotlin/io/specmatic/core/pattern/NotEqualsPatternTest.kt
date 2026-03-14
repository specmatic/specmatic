package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NotEqualsPatternTest {
    @Test
    fun `should fail when sample equals excluded value`() {
        val pattern = NotEqualsPattern(StringPattern(), StringValue("hello"))

        val result = pattern.matches(StringValue("hello"), Resolver())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `should match when sample is different from excluded value`() {
        val pattern = NotEqualsPattern(StringPattern(), StringValue("hello"))

        val result = pattern.matches(StringValue("world"), Resolver())

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `generate should return a value not equal to excluded`() {
        val base = EnumPattern(listOf(StringValue("a"), StringValue("b")))
        val pattern = NotEqualsPattern(base, StringValue("b"))

        val generated = pattern.generate(Resolver())

        assertThat(generated).isEqualTo(StringValue("a"))
    }

    @Test
    fun `generate should throw when no other value can be generated`() {
        val base = ExactValuePattern(StringValue("a"))
        val pattern = NotEqualsPattern(base, StringValue("a"))

        assertThrows<ContractException> { pattern.generate(Resolver()) }
    }

    @Test
    fun `encompasses should fail when other pattern is exact excluded value`() {
        val base = StringPattern()
        val pattern = NotEqualsPattern(base, StringValue("x"))

        val result = pattern.encompasses(ExactValuePattern(StringValue("x")), Resolver(), Resolver(), emptySet())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `patternFrom should keep excluded value and update base pattern`() {
        val pattern = NotEqualsPattern(StringPattern(), StringValue("hello"))

        val result = pattern.patternFrom(StringValue("ignored"), Resolver())

        assertThat(result).isEqualTo(NotEqualsPattern(ExactValuePattern(StringValue("ignored")), StringValue("hello")))
    }
}
