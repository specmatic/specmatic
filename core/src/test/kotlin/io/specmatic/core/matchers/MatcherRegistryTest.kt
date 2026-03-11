package io.specmatic.core.matchers

import io.specmatic.core.BreadCrumb
import io.specmatic.core.Resolver
import io.specmatic.core.pattern.AnyPattern
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.RegexConstrainedPattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MatcherRegistryTest {
    private val context = MatcherContext(Resolver())

    @Test
    fun `parse should not infer matcher from leaf key`() {
        val result = Matcher.parse(BreadCrumb("request.body.user.profile.pattern"), StringValue("string"), context)
        assertThat(result).isNull()
    }

    @Test
    fun `parse should not infer matcher from leaf key in array paths`() {
        val result = Matcher.parse(BreadCrumb("request.body.items[0].pattern"), StringValue("string"), context)
        assertThat(result).isNull()
    }

    @Test
    fun `parse should not infer matcher from leaf key when value looks like properties`() {
        val result = Matcher.parse(BreadCrumb("request.body.user.match"), StringValue("times: 2, value: each"), context)
        assertThat(result).isNull()
    }

    @Test
    fun `parse should not infer matcher from leaf key for unknown matcher token`() {
        val result = Matcher.parse(BreadCrumb("request.body.user.field"), StringValue("\$unknown(string)"), context)
        assertThat(result).isNull()
    }

    @Test
    fun `parse should not infer matcher from leaf key for malformed matcher token`() {
        val result = Matcher.parse(BreadCrumb("request.body.user.field"), StringValue("\$match"), context)
        assertThat(result).isNull()
    }

    @Test
    fun `parse should not infer matcher from leaf key pattern when token has invalid syntax`() {
        val result = Matcher.parse(BreadCrumb("request.body.user.field"), StringValue("\$match (datType: string)"), context)
        assertThat(result).isNull()
    }

    @Test
    fun `parse should still create pattern matcher when matcher syntax is in value`() {
        val result = Matcher.parse(BreadCrumb("request.body.user.value"), StringValue("${'$'}match(dataType: string)"), context)
        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue).value).isInstanceOf(CompositeMatcher::class.java)
    }

    @Test
    fun `parse should still create matcher from explicit syntax even when leaf key is pattern`() {
        val result = Matcher.parse(BreadCrumb("request.body.user.match"), StringValue("${'$'}match(dataType: string)"), context)
        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue).value).isInstanceOf(CompositeMatcher::class.java)
    }

    @Test
    fun `build should throw when defaults contain duplicate matcher keys`() {
        assertThatThrownBy { MatcherRegistry.build(defaults = listOf(PatternMatcher.Companion, PatternMatcher.Companion)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Duplicate matcher key: pattern")
    }

    @Test
    fun `patternFrom should return original pattern when value does not resolve to a matcher`() {
        val originalPattern = StringPattern()
        val result = Matcher.patternFrom(StringValue("hello"), originalPattern, Resolver())

        assertThat(result).isSameAs(originalPattern)
    }

    @Test
    fun `patternFrom should unwrap single matcher inside composite`() {
        val originalPattern = StringPattern()
        val result = Matcher.patternFrom(StringValue("\$match(dataType: string)"), originalPattern, Resolver())

        assertThat(result).isInstanceOf(StringPattern::class.java)
        assertThat(result).isNotInstanceOf(AnyPattern::class.java)
    }

    @Test
    fun `patternFrom should build regex constrained pattern`() {
        val originalPattern = StringPattern()
        val result = Matcher.patternFrom(StringValue("${'$'}match(pattern: ^Hello[A-Za-z ]*$)"), originalPattern, Resolver())

        assertThat(result).isInstanceOf(RegexConstrainedPattern::class.java)
        val constrained = result as RegexConstrainedPattern
        assertThat(constrained.basePattern).isSameAs(originalPattern)
        assertThat(constrained.regex).isEqualTo("^Hello[A-Za-z ]*$")
    }

    @Test
    fun `patternFrom should prefer regex matcher when composite includes dataType and regex`() {
        val originalPattern = NumberPattern()
        val result = Matcher.patternFrom(
            StringValue("${'$'}match(dataType: number, pattern: ^[0-9]+$)"),
            originalPattern,
            Resolver()
        )

        assertThat(result).isInstanceOf(RegexConstrainedPattern::class.java)
        val constrained = result as RegexConstrainedPattern
        assertThat(constrained.basePattern).isSameAs(originalPattern)
        assertThat(constrained.regex).isEqualTo("^[0-9]+$")
    }

    @Test
    fun `patternFrom should return the exact value pattern if there is an equality matcher present in the composite matcher`() {
        val originalPattern = StringPattern()
        val result = Matcher.patternFrom(StringValue("${'$'}match(dataType: string, exact: hello)"), originalPattern, Resolver())

        assertThat(result).isInstanceOf(ExactValuePattern::class.java)
        val pattern = result as ExactValuePattern
        assertThat(pattern.pattern.toStringLiteral()).isEqualTo("hello")
    }

    @Test
    fun `patternFrom should return original pattern when equality matcher is not equals`() {
        val originalPattern = StringPattern()
        val value = StringValue("hello")
        val result = Matcher.patternFrom(
            StringValue("\$match(exact: ${value.nativeValue}, matchType: neq)"),
            originalPattern,
            Resolver()
        )

        assertThat(result).isEqualTo(originalPattern)
    }

    @Test
    fun `patternFrom should fall back to the first matcher when composite has no pattern or regex matcher`() {
        val originalPattern = StringPattern()
        val result = Matcher.patternFrom(
            StringValue("\$match(times: 2, value: any)"),
            originalPattern,
            Resolver()
        )

        assertThat(result).isSameAs(originalPattern)
    }
}
