package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.StringValue
import io.specmatic.shouldMatch
import io.specmatic.shouldNotMatch
import org.apache.commons.codec.binary.Base64
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

internal class Base64StringPatternTest {
    @Test
    fun `should fail to match null values gracefully`() {
        NullValue shouldNotMatch Base64StringPattern()
    }

    @Test
    fun `should match base 64 string value`() {
        StringValue("U3AzY200dDFrIFJ1bEV6") shouldMatch Base64StringPattern()
    }

    @Test
    fun `should fail to match non base 64 string value`() {
        StringValue("tH1sIsN0tB64^") shouldNotMatch Base64StringPattern()
    }

    @Test
    fun `should generate base64 random string`() {
        assertThat(Base64.isBase64(Base64StringPattern().generate(Resolver()).toStringLiteral()))
    }

    @Test
    fun `should match empty String`() {
        assertThat(Base64StringPattern().matches(StringValue(""), Resolver()).isSuccess()).isTrue
    }

    @Test
    fun `the parsed StringValue should have the same string value as the original`() {
        val original = "U3AzY200dDFrIFJ1bEV6"
        val parsed = Base64StringPattern().parse(original, Resolver()).toStringLiteral()
        assertThat(parsed).isEqualTo(original)
    }

    @Test
    fun `it should not be possible for this type of Pattern to parse a non-base64 encoded string`() {
        assertThatThrownBy {
            Base64StringPattern().parse(".", Resolver()).toStringLiteral()
        }.isInstanceOf(ContractException::class.java)

    }

    @Test
    fun `should use provided base64 example during generation`() {
        val example = "U3BlY21hdGlj"
        val pattern = Base64StringPattern(example = example)

        val generated = pattern.generate(Resolver())

        assertThat(generated.toStringLiteral()).isEqualTo(example)
    }
}
