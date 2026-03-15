package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class RegexConstrainedPatternTest {
    @Test
    fun `matches should enforce regex after base pattern`() {
        val pattern = RegexConstrainedPattern(StringPattern(), "^Hello.*")
        val resolver = Resolver()

        assertThat(pattern.matches(StringValue("Hello world"), resolver)).isInstanceOf(Result.Success::class.java)
        assertThat(pattern.matches(StringValue("Bye"), resolver)).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `matches should fail when base pattern fails even if regex matches`() {
        val pattern = RegexConstrainedPattern(NumberPattern(), "^[A-Za-z]+$")
        val resolver = Resolver()

        assertThat(pattern.matches(StringValue("Alpha"), resolver)).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `generate should return the regex based candidate when it does not get parsed by the basePattern`() {
        val pattern = RegexConstrainedPattern(NumberPattern(), "^[A-Za-z]+$")
        val generated = pattern.generate(Resolver())

        assertThat(generated).isInstanceOf(StringValue::class.java)
    }

    @Test
    fun `generate should return the generated value which is successfully parsed by the basePattern`() {
        val pattern = RegexConstrainedPattern(NumberPattern(), "^1$")
        val generated = pattern.generate(Resolver())

        assertThat(generated).isInstanceOf(NumberValue::class.java)
        assertThat((generated as NumberValue).number).isEqualTo(1)
    }

    @Test
    fun `encompasses should accept exact value that matches regex`() {
        val pattern = RegexConstrainedPattern(StringPattern(), "^Hello$")
        val result = pattern.encompasses(
            ExactValuePattern(StringValue("Hello")),
            Resolver(),
            Resolver(),
            emptySet()
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }
}
