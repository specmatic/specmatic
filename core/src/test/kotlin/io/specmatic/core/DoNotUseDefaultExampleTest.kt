package io.specmatic.core

import io.specmatic.core.pattern.StringPattern
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DoNotUseDefaultExampleTest {
    @Test
    fun `resolve scalar example`() {
        val value = DoNotUseDefaultExample.resolveExample("example", StringPattern(), Resolver())
        assertNull(value)
    }

    @Test
    fun `resolve array example`() {
        val value = DoNotUseDefaultExample.resolveExample(listOf("example"), StringPattern(), Resolver())
        assertNull(value)
    }

    @Test
    fun `resolve matching one of the given patterns`() {
        val value = DoNotUseDefaultExample.resolveExample("example", listOf(StringPattern()), Resolver())
        assertNull(value)
    }

    @Test
    fun `the default example for this key is not omit`() {
        val boolean = DoNotUseDefaultExample.theDefaultExampleForThisKeyIsNotOmit(StringPattern())
        assertTrue(boolean)
    }
}