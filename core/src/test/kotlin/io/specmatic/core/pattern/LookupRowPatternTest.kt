package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.value.NumberValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class LookupRowPatternTest {
    @Test
    fun `it should return a new exact value pattern when generating pattern from row with a matching key`() {
        val pattern = LookupRowPattern(NumberPattern(), "customerId")
        val row = Row(listOf("customerId"), listOf("10"))

        val newPattern = pattern.newBasedOn(row, Resolver()).map { it.value }
        assertThat(newPattern.single()).isEqualTo(ExactValuePattern(NumberValue(10)))
    }

    @Test
    fun `it should return a new exact value pattern when generating pattern from row with no matching key`() {
        val pattern = LookupRowPattern(NumberPattern(), "customerId")
        val row = Row(emptyList(), emptyList())

        val newPattern = pattern.newBasedOn(row, Resolver()).map { it.value }
        assertThat(newPattern.single()).isEqualTo(NumberPattern())
    }

    @Test
    fun `should encompass itself`() {
        val lookupRowPattern = LookupRowPattern(StringPattern(), "name")
        val result = lookupRowPattern.encompasses(lookupRowPattern, Resolver(), Resolver())

        println(result.toReport())
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should resolve deferreds that it has to lookup when testing encompassment`() {
        val lookupRowPattern = LookupRowPattern(DeferredPattern("(string)"), "name")
        assertThat(lookupRowPattern.encompasses(StringPattern(), Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should encompass enclosed pattern`() {
        val lookupRowPattern = LookupRowPattern(StringPattern(), "name")
        assertThat(lookupRowPattern.encompasses(StringPattern(), Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should be encompassed by wider non lookup type`() {
        val anyType = AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap())
        val lookupRowType = LookupRowPattern(StringPattern(), "name")
        assertThat(anyType.encompasses(lookupRowType, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }
}
