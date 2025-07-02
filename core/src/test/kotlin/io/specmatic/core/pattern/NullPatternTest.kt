package io.specmatic.core.pattern

import org.junit.jupiter.api.Test
import io.specmatic.core.Resolver
import io.specmatic.core.pattern.NullPattern.newBasedOn
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.StringValue
import io.specmatic.shouldMatch
import org.junit.jupiter.api.Assertions.assertEquals

internal class NullPatternTest {
    @Test
    fun `should match null value`() {
        val nullValue = NullValue
        val pattern = NullPattern

        nullValue shouldMatch pattern
    }

    @Test
    fun `should match empty string`() {
        val emptyString = StringValue("")
        val pattern = NullPattern

        emptyString shouldMatch pattern
    }

    @Test
    fun `should generate null value`() {
        assertEquals(NullValue,  NullPattern.generate(Resolver()))
    }

    @Test
    fun `should create a new array of patterns containing itself`() {
        assertEquals(listOf(NullPattern), newBasedOn(Row(), Resolver()).map { it.value }.toList())
    }
}