package io.specmatic.core.pattern

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import io.specmatic.core.Resolver
import io.specmatic.core.StringProvider
import io.specmatic.core.StringProviders
import io.specmatic.core.value.StringValue
import io.specmatic.shouldMatch
import io.specmatic.shouldNotMatch
import org.assertj.core.api.Assertions.assertThat
import java.net.URI
import org.junit.jupiter.api.Assertions.assertEquals

internal class URLPatternTest {
    @Test
    fun `should be able to match a url`() {
        val url = StringValue("http://test.com")
        val pattern = URLPattern(URLScheme.HTTP)

        url shouldMatch pattern
    }

    @Test
    fun `should not match a url with the wrong scheme`() {
        val url = StringValue("http://test.com")
        val pattern = URLPattern(URLScheme.HTTPS)

        url shouldNotMatch pattern
    }

    @Test
    fun `should generate a url`() {
        val pattern = URLPattern(URLScheme.HTTPS)
        val url = pattern.generate(Resolver())

        try { URI.create(url.string) } catch (e: Throwable) { fail("${url.string} was not a URL.")}
    }

    @Test
    fun `should return itself when generating a new url based on a row`() {
        val pattern = URLPattern(URLScheme.HTTPS)
        val newPatterns = pattern.newBasedOn(Row(), Resolver()).toList().map { it.value }

        assertEquals(pattern, newPatterns.first())
        assertEquals(1, newPatterns.size)
    }

    @Test
    fun `should be able to use values provided by StringProviders when one is registered`() {
        val pattern = URLPattern()
        val resolver = Resolver()
        val provider = object: StringProvider {
            override fun getFor(pattern: ScalarType, resolver: Resolver, path: List<String>): String = "https://specmatic.io"
        }

        StringProviders.with(provider) {
            val generated = pattern.generate(resolver)
            assertThat(generated).isEqualTo(StringValue("https://specmatic.io"))
        }
    }

    @Test
    fun `should generate a random string if no provider exists or can't provide a value`() {
        val pattern = URLPattern()
        val resolver = Resolver()
        val provider = object: StringProvider {
            override fun getFor(pattern: ScalarType, resolver: Resolver, path: List<String>): String? = null
        }

        StringProviders.with(provider) {
            val generated = pattern.generate(resolver)
            assertThat(generated).isInstanceOf(StringValue::class.java)
        }
    }

    @Test
    fun `invalid value provided by any StringProviders should be halted by resolver and result in random generation`() {
        val pattern = URLPattern(scheme = URLScheme.HTTPS)
        val resolver = Resolver()
        val provider = object: StringProvider {
            override fun getFor(pattern: ScalarType, resolver: Resolver, path: List<String>): String = "http://specmatic.io"
        }

        StringProviders.with(provider) {
            val generated = pattern.generate(resolver)
            assertThat(generated.toStringLiteral()).isNotEqualTo("http://specmatic.io")
            assertThat(generated).isInstanceOf(StringValue::class.java)
        }
    }

    @Test
    fun `should use provided url example during generation`() {
        val example = "https://specmatic.io"
        val pattern = URLPattern(scheme = URLScheme.HTTPS, example = example)

        val generated = pattern.generate(Resolver())

        assertEquals(example, generated.string)
    }
}
