package io.specmatic.core.matchers

import io.specmatic.core.BreadCrumb
import io.specmatic.core.Resolver
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
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
        val result = Matcher.parse(BreadCrumb("request.body.user.field"), StringValue($$"$unknown(string)"), context)
        assertThat(result).isNull()
    }

    @Test
    fun `parse should not infer matcher from leaf key for malformed matcher token`() {
        val result = Matcher.parse(BreadCrumb("request.body.user.field"), StringValue($$"$match"), context)
        assertThat(result).isNull()
    }

    @Test
    fun `parse should not infer matcher from leaf key pattern when token has invalid syntax`() {
        val result = Matcher.parse(BreadCrumb("request.body.user.field"), StringValue($$"$match (datType: string)"), context)
        assertThat(result).isNull()
    }

    @Test
    fun `parse should still create pattern matcher when matcher syntax is in value`() {
        val result = Matcher.parse(BreadCrumb("request.body.user.value"), StringValue($$"$match(dataType: string)"), context)
        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue).value).isInstanceOf(CompositeMatcher::class.java)
    }

    @Test
    fun `parse should still create matcher from explicit syntax even when leaf key is pattern`() {
        val result = Matcher.parse(BreadCrumb("request.body.user.match"), StringValue($$"$match(dataType: string)"), context)
        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue).value).isInstanceOf(CompositeMatcher::class.java)
    }

    @Test
    fun `build should throw when defaults contain duplicate matcher keys`() {
        assertThatThrownBy { MatcherRegistry.build(defaults = listOf(PatternMatcher.Companion, PatternMatcher.Companion)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Duplicate matcher key: pattern")
    }
}
